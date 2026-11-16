//! Visible managed-window metadata and user-requested EWMH activation. No window titles are sent.
use super::X11Link;
use crate::error::{Error, Result};
use serde::Serialize;
use x11rb::connection::Connection;
use x11rb::protocol::xproto::{
    AtomEnum, ClientMessageEvent, ConnectionExt as _, EventMask, MapState,
};

#[derive(Clone, Debug, Serialize)]
pub struct FocusWindow {
    pub id: u32,
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}
fn atom(link: &X11Link, name: &[u8]) -> Result<u32> {
    Ok(link
        .conn
        .intern_atom(false, name)
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?
        .atom)
}
fn values(link: &X11Link, window: u32, property: u32, kind: AtomEnum) -> Result<Vec<u32>> {
    let reply = link
        .conn
        .get_property(false, window, property, kind, 0, 4096)
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    Ok(reply.value32().map(|v| v.collect()).unwrap_or_default())
}
pub fn catalogue() -> Result<(bool, Vec<FocusWindow>)> {
    let link = X11Link::open()?;
    let supported = values(
        &link,
        link.root,
        atom(&link, b"_NET_SUPPORTED")?,
        AtomEnum::ATOM,
    )?;
    let stacking = atom(&link, b"_NET_CLIENT_LIST_STACKING")?;
    let active = atom(&link, b"_NET_ACTIVE_WINDOW")?;
    if !supported.contains(&stacking) || !supported.contains(&active) {
        return Ok((false, Vec::new()));
    }
    let dock = atom(&link, b"_NET_WM_WINDOW_TYPE_DOCK")?;
    let desktop = atom(&link, b"_NET_WM_WINDOW_TYPE_DESKTOP")?;
    let types = atom(&link, b"_NET_WM_WINDOW_TYPE")?;
    let desktop_property = atom(&link, b"_NET_WM_DESKTOP")?;
    let current = values(
        &link,
        link.root,
        atom(&link, b"_NET_CURRENT_DESKTOP")?,
        AtomEnum::CARDINAL,
    )?
    .first()
    .copied();
    let state = atom(&link, b"_NET_WM_STATE")?;
    let hidden = atom(&link, b"_NET_WM_STATE_HIDDEN")?;
    let mut windows = Vec::new();
    for id in values(&link, link.root, stacking, AtomEnum::WINDOW)?
        .into_iter()
        .rev()
    {
        let row = (|| -> Result<Option<FocusWindow>> {
            let attrs = link
                .conn
                .get_window_attributes(id)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            if attrs.map_state != MapState::VIEWABLE || attrs.override_redirect {
                return Ok(None);
            }
            if let (Some(current), Some(desktop)) = (
                current,
                values(&link, id, desktop_property, AtomEnum::CARDINAL)?
                    .first()
                    .copied(),
            ) {
                if desktop != current && desktop != u32::MAX {
                    return Ok(None);
                }
            }
            if values(&link, id, state, AtomEnum::ATOM)?.contains(&hidden) {
                return Ok(None);
            }
            let kinds = values(&link, id, types, AtomEnum::ATOM)?;
            if kinds.contains(&dock) || kinds.contains(&desktop) {
                return Ok(None);
            }
            let g = link
                .conn
                .get_geometry(id)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            let p = link
                .conn
                .translate_coordinates(id, link.root, 0, 0)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            Ok((g.width > 0 && g.height > 0).then_some(FocusWindow {
                id,
                x: i32::from(p.dst_x),
                y: i32::from(p.dst_y),
                width: u32::from(g.width),
                height: u32::from(g.height),
            }))
        })();
        // Windows can disappear between catalogue queries.
        if let Ok(Some(window)) = row {
            windows.push(window);
        }
    }
    Ok((true, windows))
}
/// Metadata collection never blocks the control/input loop. Selection validates live state separately.
pub fn observed_catalogue() -> (bool, Vec<FocusWindow>) {
    type Snapshot = std::sync::Arc<std::sync::RwLock<(bool, Vec<FocusWindow>)>>;
    static SNAPSHOT: std::sync::OnceLock<Snapshot> = std::sync::OnceLock::new();
    let snapshot = SNAPSHOT.get_or_init(|| {
        let snapshot = std::sync::Arc::new(std::sync::RwLock::new((false, Vec::new())));
        let worker = std::sync::Arc::clone(&snapshot);
        std::thread::spawn(move || loop {
            let mut value = catalogue().unwrap_or_default();
            value.1.truncate(256);
            *worker.write().unwrap_or_else(|e| e.into_inner()) = value;
            std::thread::sleep(std::time::Duration::from_millis(500));
        });
        snapshot
    });
    snapshot.read().unwrap_or_else(|e| e.into_inner()).clone()
}

pub fn activate(id: u32) -> Result<()> {
    let (supported, windows) = catalogue()?;
    if !supported || !windows.iter().any(|w| w.id == id) {
        return Err(Error::Msg("Selected window is no longer visible".into()));
    }
    let link = X11Link::open()?;
    let mask = EventMask::SUBSTRUCTURE_REDIRECT | EventMask::SUBSTRUCTURE_NOTIFY;
    for (name, data) in [
        (b"_NET_RESTACK_WINDOW".as_slice(), [2, 0, 0, 0, 0]),
        (
            b"_NET_ACTIVE_WINDOW".as_slice(),
            [2, x11rb::CURRENT_TIME, 0, 0, 0],
        ),
    ] {
        link.conn
            .send_event(
                false,
                link.root,
                mask,
                ClientMessageEvent::new(32, id, atom(&link, name)?, data),
            )
            .map_err(Error::x11)?
            .check()
            .map_err(Error::x11)?;
    }
    link.conn.flush().map_err(Error::x11)
}

/// Focus a visible managed client without sending activation/restacking or moving the pointer.
pub fn focus_without_raise(id: u32) -> Result<()> {
    use x11rb::protocol::{
        xproto::{CreateWindowAux, InputFocus, PropMode, WindowClass},
        Event,
    };
    use x11rb::wrapper::ConnectionExt as _;
    let (supported, windows) = catalogue()?;
    if !supported || !windows.iter().any(|w| w.id == id) {
        return Err(Error::Msg("Gaze target is no longer visible".into()));
    }
    let link = X11Link::open()?;
    let mut focused = link
        .conn
        .get_input_focus()
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?
        .focus;
    // Preserve the application's current text field/subwindow when it already owns focus.
    for _ in 0..32 {
        if focused == id {
            return Ok(());
        }
        if focused <= 1 || focused == link.root {
            break;
        }
        focused = match link.conn.query_tree(focused).map_err(Error::x11)?.reply() {
            Ok(tree) => tree.parent,
            Err(_) => break,
        };
    }
    let hints = values(&link, id, atom(&link, b"WM_HINTS")?, AtomEnum::ANY)?;
    let accepts =
        hints.first().is_none_or(|flags| flags & 1 == 0) || hints.get(1).copied().unwrap_or(1) != 0;
    let protocols = values(&link, id, atom(&link, b"WM_PROTOCOLS")?, AtomEnum::ATOM)?;
    let take_focus = atom(&link, b"WM_TAKE_FOCUS")?;
    let takes = protocols.contains(&take_focus);
    if !accepts && !takes {
        return Err(Error::Msg(
            "This window does not accept keyboard focus".into(),
        ));
    }
    // ICCCM WM_TAKE_FOCUS requires a real server timestamp, never CurrentTime.
    let helper = link.conn.generate_id().map_err(Error::x11)?;
    link.conn
        .create_window(
            0,
            helper,
            link.root,
            0,
            0,
            1,
            1,
            0,
            WindowClass::INPUT_ONLY,
            0,
            &CreateWindowAux::new().event_mask(EventMask::PROPERTY_CHANGE),
        )
        .map_err(Error::x11)?
        .check()
        .map_err(Error::x11)?;
    link.conn
        .change_property32(
            PropMode::REPLACE,
            helper,
            atom(&link, b"_FLOATDESK_FOCUS_TIME")?,
            AtomEnum::CARDINAL,
            &[0],
        )
        .map_err(Error::x11)?
        .check()
        .map_err(Error::x11)?;
    let timestamp = loop {
        if let Event::PropertyNotify(e) = link.conn.wait_for_event().map_err(Error::x11)? {
            if e.window == helper {
                break e.time;
            }
        }
    };
    // This request represents an explicit user gaze action. Publish its server
    // time before FocusIn so KWin's focus-stealing check can recognize it.
    let user_time_window = values(
        &link,
        id,
        atom(&link, b"_NET_WM_USER_TIME_WINDOW")?,
        AtomEnum::WINDOW,
    )?
    .first()
    .copied()
    .unwrap_or(id);
    link.conn
        .change_property32(
            PropMode::REPLACE,
            user_time_window,
            atom(&link, b"_NET_WM_USER_TIME")?,
            AtomEnum::CARDINAL,
            &[timestamp],
        )
        .map_err(Error::x11)?
        .check()
        .map_err(Error::x11)?;
    if accepts {
        link.conn
            .set_input_focus(InputFocus::PARENT, id, timestamp)
            .map_err(Error::x11)?
            .check()
            .map_err(Error::x11)?;
    }
    if takes {
        link.conn
            .send_event(
                false,
                id,
                EventMask::NO_EVENT,
                ClientMessageEvent::new(
                    32,
                    id,
                    atom(&link, b"WM_PROTOCOLS")?,
                    [take_focus, timestamp, 0, 0, 0],
                ),
            )
            .map_err(Error::x11)?
            .check()
            .map_err(Error::x11)?;
    }
    link.conn
        .destroy_window(helper)
        .map_err(Error::x11)?
        .check()
        .map_err(Error::x11)?;
    link.conn.flush().map_err(Error::x11)?;
    let active = atom(&link, b"_NET_ACTIVE_WINDOW")?;
    for _ in 0..40 {
        if values(&link, link.root, active, AtomEnum::WINDOW)?.first() == Some(&id) {
            return Ok(());
        }
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    Err(Error::Msg("Window manager declined gaze focus".into()))
}

/// Rectangles above the client's top-level frame, including unmanaged menus.
/// No names/content are read. Fullscreen unnamed redirect windows are compositor overlays.
pub fn occluders(link: &X11Link, client: u32) -> Result<Vec<[i32; 4]>> {
    let mut frame = client;
    for _ in 0..12 {
        let tree = link
            .conn
            .query_tree(frame)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        if tree.parent == link.root {
            break;
        }
        if tree.parent == 0 {
            break;
        }
        frame = tree.parent;
    }
    let tree = link
        .conn
        .query_tree(link.root)
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    let Some(index) = tree.children.iter().position(|w| *w == frame) else {
        return Ok(Vec::new());
    };
    let screen = &link.conn.setup().roots[link.screen];
    let mut result = Vec::new();
    for id in tree.children.iter().skip(index + 1) {
        let entry = (|| -> Result<Option<[i32; 4]>> {
            let attrs = link
                .conn
                .get_window_attributes(*id)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            if attrs.map_state != MapState::VIEWABLE
                || attrs.class != x11rb::protocol::xproto::WindowClass::INPUT_OUTPUT
            {
                return Ok(None);
            }
            let g = link
                .conn
                .get_geometry(*id)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            if attrs.override_redirect
                && g.width >= screen.width_in_pixels
                && g.height >= screen.height_in_pixels
            {
                let types = link
                    .conn
                    .get_property(
                        false,
                        *id,
                        atom(link, b"_NET_WM_WINDOW_TYPE")?,
                        AtomEnum::ATOM,
                        0,
                        16,
                    )
                    .map_err(Error::x11)?
                    .reply()
                    .map_err(Error::x11)?;
                if types.value.is_empty() {
                    return Ok(None);
                }
            }
            let p = link
                .conn
                .translate_coordinates(*id, link.root, 0, 0)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            Ok(Some([
                p.dst_x.into(),
                p.dst_y.into(),
                g.width.into(),
                g.height.into(),
            ]))
        })();
        if let Ok(Some(rect)) = entry {
            result.push(rect)
        }
        if result.len() == 16 {
            break;
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use x11rb::protocol::xproto::{CreateWindowAux, PropMode, WindowClass};
    use x11rb::wrapper::ConnectionExt as _;
    #[test]
    #[ignore = "requires private Xvfb with KWin and FLOATDESK_SYNTHETIC_DISPLAY=1"]
    fn focus_without_raise_private_kwin() {
        assert_eq!(
            std::env::var("FLOATDESK_SYNTHETIC_DISPLAY").as_deref(),
            Ok("1")
        );
        let link = X11Link::open().unwrap();
        let active = atom(&link, b"_NET_ACTIVE_WINDOW").unwrap();
        let stacking = atom(&link, b"_NET_CLIENT_LIST_STACKING").unwrap();
        let ids = [
            link.conn.generate_id().unwrap(),
            link.conn.generate_id().unwrap(),
        ];
        for (i, &id) in ids.iter().enumerate() {
            link.conn
                .create_window(
                    0,
                    id,
                    link.root,
                    100 + i as i16 * 200,
                    100,
                    400,
                    300,
                    0,
                    WindowClass::INPUT_OUTPUT,
                    0,
                    &CreateWindowAux::new().background_pixel(0x808080),
                )
                .unwrap()
                .check()
                .unwrap();
            link.conn.map_window(id).unwrap().check().unwrap();
        }
        let wait = |test: &dyn Fn() -> bool| {
            let until = std::time::Instant::now() + std::time::Duration::from_secs(5);
            while !test() {
                assert!(
                    std::time::Instant::now() < until,
                    "KWin state did not converge: input={:?} active={:?} stack={:?} catalogue={:?}",
                    link.conn.get_input_focus().unwrap().reply().unwrap().focus,
                    values(&link, link.root, active, AtomEnum::WINDOW).unwrap(),
                    values(&link, link.root, stacking, AtomEnum::WINDOW).unwrap(),
                    catalogue().unwrap()
                );
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
        };
        wait(&|| {
            catalogue()
                .unwrap()
                .1
                .iter()
                .filter(|w| ids.contains(&w.id))
                .count()
                == 2
        });
        activate(ids[1]).unwrap();
        wait(&|| {
            values(&link, link.root, active, AtomEnum::WINDOW)
                .unwrap()
                .first()
                == Some(&ids[1])
        });
        let before = values(&link, link.root, stacking, AtomEnum::WINDOW).unwrap();
        let pointer = link.conn.query_pointer(link.root).unwrap().reply().unwrap();
        focus_without_raise(ids[0]).unwrap();
        wait(&|| {
            link.conn.get_input_focus().unwrap().reply().unwrap().focus == ids[0]
                && values(&link, link.root, active, AtomEnum::WINDOW)
                    .unwrap()
                    .first()
                    == Some(&ids[0])
        });
        std::thread::sleep(std::time::Duration::from_millis(200));
        assert_eq!(
            values(&link, link.root, stacking, AtomEnum::WINDOW).unwrap(),
            before
        );
        let after = link.conn.query_pointer(link.root).unwrap().reply().unwrap();
        assert_eq!(
            (pointer.root_x, pointer.root_y),
            (after.root_x, after.root_y)
        );
        let child = link.conn.generate_id().unwrap();
        link.conn
            .create_window(
                0,
                child,
                ids[0],
                5,
                5,
                20,
                20,
                0,
                WindowClass::INPUT_OUTPUT,
                0,
                &CreateWindowAux::new(),
            )
            .unwrap()
            .check()
            .unwrap();
        link.conn.map_window(child).unwrap().check().unwrap();
        link.conn
            .set_input_focus(
                x11rb::protocol::xproto::InputFocus::PARENT,
                child,
                x11rb::CURRENT_TIME,
            )
            .unwrap()
            .check()
            .unwrap();
        focus_without_raise(ids[0]).unwrap();
        assert_eq!(
            link.conn.get_input_focus().unwrap().reply().unwrap().focus,
            child
        );
        for id in ids {
            link.conn.destroy_window(id).unwrap().check().unwrap();
        }
    }
    #[test]
    #[ignore = "requires a private Xvfb and FLOATDESK_SYNTHETIC_DISPLAY=1"]
    fn catalogue_and_activation_private_x11() {
        assert_eq!(
            std::env::var("FLOATDESK_SYNTHETIC_DISPLAY").as_deref(),
            Ok("1")
        );
        let link = X11Link::open().unwrap();
        let stack = atom(&link, b"_NET_CLIENT_LIST_STACKING").unwrap();
        let active = atom(&link, b"_NET_ACTIVE_WINDOW").unwrap();
        let supported = atom(&link, b"_NET_SUPPORTED").unwrap();
        link.conn
            .change_property32(
                PropMode::REPLACE,
                link.root,
                supported,
                AtomEnum::ATOM,
                &[stack, active],
            )
            .unwrap()
            .check()
            .unwrap();
        let mut ids = Vec::new();
        for (x, y) in [(40, 50), (100, 120), (0, 0)] {
            let id = link.conn.generate_id().unwrap();
            link.conn
                .create_window(
                    x11rb::COPY_DEPTH_FROM_PARENT,
                    id,
                    link.root,
                    x,
                    y,
                    300,
                    200,
                    0,
                    WindowClass::INPUT_OUTPUT,
                    0,
                    &CreateWindowAux::new(),
                )
                .unwrap()
                .check()
                .unwrap();
            link.conn.map_window(id).unwrap().check().unwrap();
            ids.push(id);
        }
        let types = atom(&link, b"_NET_WM_WINDOW_TYPE").unwrap();
        let dock = atom(&link, b"_NET_WM_WINDOW_TYPE_DOCK").unwrap();
        link.conn
            .change_property32(PropMode::REPLACE, ids[2], types, AtomEnum::ATOM, &[dock])
            .unwrap()
            .check()
            .unwrap();
        link.conn
            .change_property32(PropMode::REPLACE, link.root, stack, AtomEnum::WINDOW, &ids)
            .unwrap()
            .check()
            .unwrap();
        let (cap, rows) = catalogue().unwrap();
        assert!(cap);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].id, ids[1]);
        assert_eq!(
            (rows[0].x, rows[0].y, rows[0].width, rows[0].height),
            (100, 120, 300, 200)
        );
        link.conn
            .change_window_attributes(
                link.root,
                &x11rb::protocol::xproto::ChangeWindowAttributesAux::new()
                    .event_mask(EventMask::SUBSTRUCTURE_NOTIFY),
            )
            .unwrap()
            .check()
            .unwrap();
        // Exercise the production LAN focus route against this private desktop.
        let probe = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        let port = probe.local_addr().unwrap().port();
        drop(probe);
        let binary =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../target/release/floatdesk");
        let mut host = std::process::Command::new(binary)
            .args([
                "serve",
                "--existing",
                "--listen",
                &format!("127.0.0.1:{port}"),
                "--encoder",
                "x264enc",
                "--fps",
                "1",
                "--duration",
                "8",
            ])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap();
        let socket = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        socket
            .set_read_timeout(Some(std::time::Duration::from_millis(200)))
            .unwrap();
        let server = format!("127.0.0.1:{port}");
        let mut buffer = [0u8; 65535];
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(4);
        loop {
            socket
                .send_to(
                    &floatdesk_protocol::ControlPacket::ClientHello {
                        name: "focus-test".into(),
                        video_port: 49990,
                    }
                    .encode(),
                    &server,
                )
                .unwrap();
            if let Ok((n, _)) = socket.recv_from(&mut buffer) {
                if matches!(
                    floatdesk_protocol::decode_packet(&buffer[..n]),
                    Ok(floatdesk_protocol::ControlPacket::HostHello { .. })
                ) {
                    break;
                }
            }
            assert!(
                std::time::Instant::now() < deadline,
                "Private focus host did not start"
            );
        }
        let geometry = link.conn.get_geometry(link.root).unwrap().reply().unwrap();
        let request = floatdesk_protocol::ControlPacket::Workspace {
            json: serde_json::json!({"type":"focus","requestId":100,"windowId":ids[1],"contactU":140.0/f64::from(geometry.width),"contactV":150.0/f64::from(geometry.height)}).to_string(),
        }
        .encode();
        socket.send_to(&request, &server).unwrap();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        loop {
            if let Ok((n, _)) = socket.recv_from(&mut buffer) {
                if let Ok(floatdesk_protocol::ControlPacket::Workspace { json }) =
                    floatdesk_protocol::decode_packet(&buffer[..n])
                {
                    let value: serde_json::Value = serde_json::from_str(&json).unwrap();
                    if value["type"] == "focusResult" && value["requestId"] == 100 {
                        assert_eq!(value["ok"], true);
                        break;
                    }
                }
            }
            assert!(
                std::time::Instant::now() < deadline,
                "No production focus acknowledgement"
            );
        }
        let cursor = link.conn.query_pointer(link.root).unwrap().reply().unwrap();
        assert_eq!((cursor.root_x, cursor.root_y), (140, 150));
        link.move_pointer(20, 30).unwrap();
        socket.send_to(&request, &server).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(100));
        let cursor = link.conn.query_pointer(link.root).unwrap().reply().unwrap();
        assert_eq!((cursor.root_x, cursor.root_y), (20, 30)); // Retry returns cached acknowledgement without reapplying focus.
        unsafe {
            libc::kill(host.id() as i32, libc::SIGTERM);
        }
        assert!(host.wait().unwrap().success());
        // Drain the production focus messages before checking the direct EWMH request.
        while link.conn.poll_for_event().unwrap().is_some() {}
        activate(ids[1]).unwrap();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        let mut messages = Vec::new();
        while std::time::Instant::now() < deadline && messages.len() < 2 {
            if let Some(x11rb::protocol::Event::ClientMessage(e)) =
                link.conn.poll_for_event().unwrap()
            {
                messages.push(e);
            }
            std::thread::sleep(std::time::Duration::from_millis(2));
        }
        assert_eq!(messages.len(), 2);
        assert!(messages
            .iter()
            .any(|m| m.type_ == active && m.window == ids[1] && m.data.as_data32()[0] == 2));
        link.conn.unmap_window(ids[1]).unwrap().check().unwrap();
        assert!(activate(ids[1]).is_err());
        for id in ids {
            let _ = link.conn.destroy_window(id).unwrap().check();
        }
    }
}
