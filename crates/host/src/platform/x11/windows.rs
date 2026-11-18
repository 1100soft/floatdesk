//! Visible managed-window metadata and user-requested EWMH activation. No window titles are sent.
use super::X11Link;
use crate::error::{Error, Result};
use serde::Serialize;
use x11rb::connection::Connection;
use x11rb::protocol::xproto::{
    AtomEnum, ClientMessageEvent, ConnectionExt as _, EventMask, MapState,
};

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct FocusWindow {
    pub id: u32,
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
    pub panel: bool,
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
                panel: false,
            }))
        })();
        // Windows can disappear between catalogue queries.
        if let Ok(Some(window)) = row {
            windows.push(window);
        }
    }
    Ok((true, windows))
}
/// A popup can extend beyond its owner; preserve that extent in capture and input mapping.
pub fn capture_bounds(base: (i32, i32, u32, u32), popups: &[FocusWindow]) -> (i32, i32, u32, u32) {
    let mut left = base.0;
    let mut top = base.1;
    let mut right = i64::from(base.0) + i64::from(base.2);
    let mut bottom = i64::from(base.1) + i64::from(base.3);
    for p in popups {
        left = left.min(p.x);
        top = top.min(p.y);
        right = right.max(i64::from(p.x) + i64::from(p.width));
        bottom = bottom.max(i64::from(p.y) + i64::from(p.height));
    }
    (
        left,
        top,
        (right - i64::from(left)) as u32,
        (bottom - i64::from(top)) as u32,
    )
}

/// Separately mapped menus are not part of a client's Composite backing pixmap.
/// Include only menus owned by this client (transient chain or same PID), never
/// unrelated application windows merely because they overlap its rectangle.
pub fn window_popups(link: &X11Link, client: u32) -> Result<Vec<FocusWindow>> {
    let pid_atom = atom(link, b"_NET_WM_PID")?;
    let owner_pid = values(link, client, pid_atom, AtomEnum::CARDINAL)?
        .first()
        .copied();
    let active = values(
        link,
        link.root,
        atom(link, b"_NET_ACTIVE_WINDOW")?,
        AtomEnum::WINDOW,
    )?
    .first()
    .copied();
    let transient = atom(link, b"WM_TRANSIENT_FOR")?;
    let types = atom(link, b"_NET_WM_WINDOW_TYPE")?;
    let owner_is_dock = values(link, client, types, AtomEnum::ATOM)?
        .contains(&atom(link, b"_NET_WM_WINDOW_TYPE_DOCK")?);
    let menu_types = [
        b"_NET_WM_WINDOW_TYPE_POPUP_MENU".as_slice(),
        b"_NET_WM_WINDOW_TYPE_DROPDOWN_MENU",
        b"_NET_WM_WINDOW_TYPE_MENU",
        b"_NET_WM_WINDOW_TYPE_COMBO",
    ]
    .iter()
    .map(|name| atom(link, name))
    .collect::<Result<Vec<_>>>()?;
    let clients = values(
        link,
        link.root,
        atom(link, b"_NET_CLIENT_LIST_STACKING")?,
        AtomEnum::WINDOW,
    )?;
    let tree = link
        .conn
        .query_tree(link.root)
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    let mut result = Vec::new();
    for id in tree.children.into_iter().chain(clients) {
        if id == client || result.iter().any(|w: &FocusWindow| w.id == id) {
            continue;
        }
        let item = (|| -> Result<Option<FocusWindow>> {
            let a = link
                .conn
                .get_window_attributes(id)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            if a.map_state != MapState::VIEWABLE
                || a.class != x11rb::protocol::xproto::WindowClass::INPUT_OUTPUT
            {
                return Ok(None);
            }
            let kinds = values(link, id, types, AtomEnum::ATOM)?;
            let mut parent = values(link, id, transient, AtomEnum::WINDOW)?
                .first()
                .copied();
            let has_parent = parent.is_some_and(|p| p != link.root);
            let mut owned = false;
            for _ in 0..12 {
                match parent {
                    Some(p) if p == client => {
                        owned = true;
                        break;
                    }
                    Some(p) if p != id => {
                        parent = values(link, p, transient, AtomEnum::WINDOW)?
                            .first()
                            .copied()
                    }
                    _ => break,
                }
            }
            let menu = kinds.iter().any(|t| menu_types.contains(t));
            if owner_is_dock && !menu {
                return Ok(None);
            }
            let same_pid = owner_pid.is_some()
                && owner_pid
                    == values(link, id, pid_atom, AtomEnum::CARDINAL)?
                        .first()
                        .copied();
            if !(owned && (menu || a.override_redirect)
                || menu && same_pid && !has_parent && active == Some(client))
            {
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
                x: p.dst_x.into(),
                y: p.dst_y.into(),
                width: g.width.into(),
                height: g.height.into(),
                panel: false,
            }))
        })();
        if let Ok(Some(window)) = item {
            result.push(window);
        }
        if result.len() >= 16 {
            break;
        }
    }
    Ok(result)
}

/// Trim a dock's transparent/shadow canvas to its declared reserved strip.
/// The same intersection rule handles horizontal and vertical panels.
fn dock_content_bounds(window: [i32; 4], root: (u32, u32), strut: &[u32]) -> [i32; 4] {
    if strut.len() < 4 {
        return window;
    }
    let (rw, rh) = (i64::from(root.0), i64::from(root.1));
    let range = |start: usize, end: usize, extent: i64| {
        if strut.len() >= 12 {
            (
                i64::from(strut[start]),
                (i64::from(strut[end]) + 1).min(extent),
            )
        } else {
            (0, extent)
        }
    };
    let mut best = window;
    let mut area = 0;
    for side in 0..4 {
        if strut[side] == 0 {
            continue;
        }
        let (x0, y0, x1, y1) = match side {
            0 => {
                let (a, b) = range(4, 5, rh);
                (0, a, i64::from(strut[0]).min(rw), b)
            }
            1 => {
                let (a, b) = range(6, 7, rh);
                ((rw - i64::from(strut[1])).max(0), a, rw, b)
            }
            2 => {
                let (a, b) = range(8, 9, rw);
                (a, 0, b, i64::from(strut[2]).min(rh))
            }
            _ => {
                let (a, b) = range(10, 11, rw);
                (a, (rh - i64::from(strut[3])).max(0), b, rh)
            }
        };
        let left = x0.max(i64::from(window[0]));
        let top = y0.max(i64::from(window[1]));
        let width = x1.min(i64::from(window[0]) + i64::from(window[2])) - left;
        let height = y1.min(i64::from(window[1]) + i64::from(window[3])) - top;
        if width > 0 && height > 0 && width * height > area {
            area = width * height;
            best = [left as i32, top as i32, width as i32, height as i32];
        }
    }
    best
}

/// Panels and Plasma launcher surfaces live outside the normal focus catalogue.
pub fn system_catalogue() -> Result<Vec<FocusWindow>> {
    let link = X11Link::open()?;
    let types = atom(&link, b"_NET_WM_WINDOW_TYPE")?;
    let dock = atom(&link, b"_NET_WM_WINDOW_TYPE_DOCK")?;
    let desktop = atom(&link, b"_NET_WM_WINDOW_TYPE_DESKTOP")?;
    let tooltip = atom(&link, b"_NET_WM_WINDOW_TYPE_TOOLTIP")?;
    let notification = atom(&link, b"_NET_WM_WINDOW_TYPE_NOTIFICATION")?;
    let osd = atom(&link, b"_KDE_NET_WM_WINDOW_TYPE_ON_SCREEN_DISPLAY")?;
    let strut = atom(&link, b"_NET_WM_STRUT_PARTIAL")?;
    let class = atom(&link, b"WM_CLASS")?;
    let mut ids = values(
        &link,
        link.root,
        atom(&link, b"_NET_CLIENT_LIST_STACKING")?,
        AtomEnum::WINDOW,
    )?;
    ids.extend(
        link.conn
            .query_tree(link.root)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?
            .children,
    );
    ids.sort_unstable();
    ids.dedup();
    let mut result = Vec::new();
    for id in ids {
        let row = (|| -> Result<Option<FocusWindow>> {
            let a = link
                .conn
                .get_window_attributes(id)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            if a.map_state != MapState::VIEWABLE
                || a.class != x11rb::protocol::xproto::WindowClass::INPUT_OUTPUT
            {
                return Ok(None);
            }
            let kinds = values(&link, id, types, AtomEnum::ATOM)?;
            if kinds.contains(&desktop)
                || kinds.contains(&tooltip)
                || kinds.contains(&notification)
                || kinds.contains(&osd)
            {
                return Ok(None);
            }
            let wmclass = link
                .conn
                .get_property(false, id, class, AtomEnum::STRING, 0, 128)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?
                .value;
            let plasma = wmclass
                .split(|v| *v == 0)
                .any(|v| v.eq_ignore_ascii_case(b"plasmashell"));
            if !kinds.contains(&dock) && !plasma {
                return Ok(None);
            }
            let g = link
                .conn
                .get_geometry(id)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            let root = &link.conn.setup().roots[link.screen];
            if g.width <= 1
                || g.height <= 1
                || (g.width >= root.width_in_pixels && g.height >= root.height_in_pixels)
            {
                return Ok(None);
            }
            let p = link
                .conn
                .translate_coordinates(id, link.root, 0, 0)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            let raw = [
                i32::from(p.dst_x),
                i32::from(p.dst_y),
                i32::from(g.width),
                i32::from(g.height),
            ];
            let bounds = if kinds.contains(&dock) {
                dock_content_bounds(
                    raw,
                    (root.width_in_pixels.into(), root.height_in_pixels.into()),
                    &values(&link, id, strut, AtomEnum::CARDINAL)?,
                )
            } else {
                raw
            };
            Ok(Some(FocusWindow {
                id,
                x: bounds[0],
                y: bounds[1],
                width: bounds[2] as u32,
                height: bounds[3] as u32,
                panel: kinds.contains(&dock),
            }))
        })();
        if let Ok(Some(row)) = row {
            result.push(row);
        }
        if result.len() >= 16 {
            break;
        }
    }
    Ok(result)
}

fn observed_system_snapshot() -> &'static std::sync::RwLock<Vec<FocusWindow>> {
    static SYSTEM: std::sync::OnceLock<std::sync::RwLock<Vec<FocusWindow>>> =
        std::sync::OnceLock::new();
    SYSTEM.get_or_init(|| std::sync::RwLock::new(Vec::new()))
}
static SYSTEM_REVISION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
fn start_system_observer() {
    static WATCH: std::sync::OnceLock<()> = std::sync::OnceLock::new();
    WATCH.get_or_init(|| {
        std::thread::spawn(|| loop {
            let Ok(link) = X11Link::open() else {
                std::thread::sleep(std::time::Duration::from_millis(250));
                continue;
            };
            let selected = link
                .conn
                .change_window_attributes(
                    link.root,
                    &x11rb::protocol::xproto::ChangeWindowAttributesAux::new()
                        .event_mask(EventMask::SUBSTRUCTURE_NOTIFY),
                )
                .ok()
                .and_then(|c| c.check().ok());
            if selected.is_none() {
                std::thread::sleep(std::time::Duration::from_millis(250));
                continue;
            }
            let mut dirty = true;
            let mut recent_maps = std::collections::HashMap::new();
            let mut rows = Vec::<FocusWindow>::new();
            let mut sampled = std::time::Instant::now() - std::time::Duration::from_secs(1);
            loop {
                let mut failed = false;
                loop {
                    match link.conn.poll_for_event() {
                        Ok(Some(event)) => {
                            use x11rb::protocol::Event;
                            match event {
                                Event::MapNotify(e) => {
                                    dirty = true;
                                    recent_maps.insert(e.window, std::time::Instant::now());
                                }
                                Event::UnmapNotify(_)
                                | Event::DestroyNotify(_)
                                | Event::ReparentNotify(_) => dirty = true,
                                Event::ConfigureNotify(e)
                                    if rows.iter().any(|r| r.id == e.window)
                                        || recent_maps.contains_key(&e.window) =>
                                {
                                    dirty = true
                                }
                                Event::PropertyNotify(e)
                                    if rows.iter().any(|r| r.id == e.window) =>
                                {
                                    dirty = true
                                }
                                _ => {}
                            }
                        }
                        Ok(None) => break,
                        Err(_) => {
                            failed = true;
                            break;
                        }
                    }
                }
                if failed {
                    break;
                }
                recent_maps.retain(|_, at| at.elapsed() < std::time::Duration::from_secs(1));
                if dirty && sampled.elapsed() >= std::time::Duration::from_millis(40) {
                    if let Ok(next) = system_catalogue() {
                        if next != rows {
                            for row in &next {
                                let _ = link.conn.change_window_attributes(
                                    row.id,
                                    &x11rb::protocol::xproto::ChangeWindowAttributesAux::new()
                                        .event_mask(
                                            EventMask::PROPERTY_CHANGE
                                                | EventMask::STRUCTURE_NOTIFY,
                                        ),
                                );
                            }
                            let _ = link.conn.flush();
                            *observed_system_snapshot()
                                .write()
                                .unwrap_or_else(|e| e.into_inner()) = next.clone();
                            SYSTEM_REVISION.fetch_add(1, std::sync::atomic::Ordering::Release);
                            rows = next;
                        }
                        dirty = false;
                    }
                    sampled = std::time::Instant::now();
                }
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
        });
    });
}
pub fn system_revision() -> u64 {
    start_system_observer();
    SYSTEM_REVISION.load(std::sync::atomic::Ordering::Acquire)
}
pub fn observed_system_catalogue() -> Vec<FocusWindow> {
    start_system_observer();
    observed_system_snapshot()
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
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
            let system = observed_system_catalogue();
            value.1.retain(|w| !system.iter().any(|s| s.id == w.id));
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

/// Apply application-selected geometry/actions; never enter the WM's interactive drag.
pub fn window_action(value: &serde_json::Value) -> Result<()> {
    let id = value["windowId"]
        .as_u64()
        .and_then(|v| u32::try_from(v).ok())
        .ok_or_else(|| Error::Msg("Missing window".into()))?;
    let (_, cached) = observed_catalogue();
    let rows = if cached.iter().any(|w| w.id == id) {
        cached
    } else {
        catalogue()?.1
    };
    let window = rows
        .iter()
        .find(|w| w.id == id)
        .ok_or_else(|| Error::Msg("Window is no longer available".into()))?;
    let link = X11Link::open()?;
    let mask = EventMask::SUBSTRUCTURE_REDIRECT | EventMask::SUBSTRUCTURE_NOTIFY;
    let action = value["action"].as_str().unwrap_or("");
    let (name, data) = match action {
        "close" => ("_NET_CLOSE_WINDOW", [x11rb::CURRENT_TIME, 2, 0, 0, 0]),
        "minimize" => ("WM_CHANGE_STATE", [3, 0, 0, 0, 0]),
        "maximize" => (
            "_NET_WM_STATE",
            [
                2,
                atom(&link, b"_NET_WM_STATE_MAXIMIZED_HORZ")?,
                atom(&link, b"_NET_WM_STATE_MAXIMIZED_VERT")?,
                2,
                0,
            ],
        ),
        "move" | "geometry" => {
            let integer = |key: &str, default: i64| value[key].as_i64().unwrap_or(default);
            let x = i32::try_from(integer("x", window.x.into()))
                .map_err(|_| Error::Msg("Invalid window x".into()))?;
            let y = i32::try_from(integer("y", window.y.into()))
                .map_err(|_| Error::Msg("Invalid window y".into()))?;
            let w = u32::try_from(integer("width", window.width.into()))
                .ok()
                .filter(|v| *v > 0 && *v <= u16::MAX.into())
                .ok_or_else(|| Error::Msg("Invalid window width".into()))?;
            let h = u32::try_from(integer("height", window.height.into()))
                .ok()
                .filter(|v| *v > 0 && *v <= u16::MAX.into())
                .ok_or_else(|| Error::Msg("Invalid window height".into()))?;
            // Remove maximization before applying an explicit application snap.
            link.conn
                .send_event(
                    false,
                    link.root,
                    mask,
                    ClientMessageEvent::new(
                        32,
                        id,
                        atom(&link, b"_NET_WM_STATE")?,
                        [
                            0,
                            atom(&link, b"_NET_WM_STATE_MAXIMIZED_HORZ")?,
                            atom(&link, b"_NET_WM_STATE_MAXIMIZED_VERT")?,
                            2,
                            0,
                        ],
                    ),
                )
                .map_err(Error::x11)?;
            let flags = 10
                | (2 << 12)
                | (1 << 8)
                | (1 << 9)
                | if action == "geometry" {
                    (1 << 10) | (1 << 11)
                } else {
                    0
                };
            ("_NET_MOVERESIZE_WINDOW", [flags, x as u32, y as u32, w, h])
        }
        _ => return Err(Error::Msg("Unsupported window action".into())),
    };
    link.conn
        .send_event(
            false,
            link.root,
            mask,
            ClientMessageEvent::new(32, id, atom(&link, name.as_bytes())?, data),
        )
        .map_err(Error::x11)?
        .check()
        .map_err(Error::x11)?;
    link.conn.flush().map_err(Error::x11)
}

#[cfg(test)]
mod tests {
    use super::*;
    use x11rb::protocol::xproto::{CreateWindowAux, PropMode, WindowClass};
    use x11rb::wrapper::ConnectionExt as _;
    #[test]
    #[ignore = "private Xvfb/KWin only"]
    fn window_actions_private_kwin() {
        assert_eq!(
            std::env::var("FLOATDESK_SYNTHETIC_DISPLAY").as_deref(),
            Ok("1")
        );
        let link = X11Link::open().unwrap();
        // Real XI2 input must survive a root-edge clamp, while our own warps
        // must not feed back as another physical mouse movement.
        use crate::core::CursorBackend;
        use x11rb::protocol::xtest::ConnectionExt as _;
        let mut cursor = crate::platform::x11::X11Cursor::open().unwrap();
        cursor.move_to(1279, 799).unwrap();
        let _ = cursor.poll().unwrap();
        cursor.take_motion();
        link.conn
            .xtest_fake_input(
                x11rb::protocol::xproto::MOTION_NOTIFY_EVENT,
                1,
                0,
                link.root,
                13,
                7,
                0,
            )
            .unwrap()
            .check()
            .unwrap();
        link.conn.flush().unwrap();
        let mut motion = [0.0, 0.0];
        for _ in 0..100 {
            let _ = cursor.poll().unwrap();
            let delta = cursor.take_motion();
            motion[0] += delta[0];
            motion[1] += delta[1];
            if motion[0] > 0.0 && motion[1] > 0.0 {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        assert!(
            motion[0] > 0.0 && motion[1] > 0.0,
            "Raw edge movement was lost: {motion:?}"
        );
        cursor.move_to(50, 50).unwrap();
        let _ = cursor.poll().unwrap();
        assert_eq!(
            cursor.take_motion(),
            [0.0, 0.0],
            "Warp fed back into relative motion"
        );

        let id = link.conn.generate_id().unwrap();
        let screen = &link.conn.setup().roots[link.screen];
        link.conn
            .create_window(
                screen.root_depth,
                id,
                link.root,
                40,
                40,
                200,
                160,
                0,
                WindowClass::INPUT_OUTPUT,
                0,
                &CreateWindowAux::new().background_pixel(0x404040),
            )
            .unwrap()
            .check()
            .unwrap();
        let protocols = atom(&link, b"WM_PROTOCOLS").unwrap();
        let delete = atom(&link, b"WM_DELETE_WINDOW").unwrap();
        link.conn
            .change_property32(PropMode::REPLACE, id, protocols, AtomEnum::ATOM, &[delete])
            .unwrap()
            .check()
            .unwrap();
        link.conn.map_window(id).unwrap().check().unwrap();
        link.conn.flush().unwrap();
        let wait = |test: &dyn Fn() -> bool| {
            let until = std::time::Instant::now() + std::time::Duration::from_secs(5);
            while !test() {
                assert!(
                    std::time::Instant::now() < until,
                    "Window action did not converge"
                );
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
        };
        wait(&|| catalogue().unwrap().1.iter().any(|w| w.id == id));
        window_action(&serde_json::json!({"windowId":id,"action":"geometry","x":190,"y":140,"width":300,"height":220})).unwrap();
        wait(&|| {
            let g = link.conn.get_geometry(id).unwrap().reply().unwrap();
            g.width == 300 && g.height == 220
        });
        let p = link
            .conn
            .translate_coordinates(id, link.root, 0, 0)
            .unwrap()
            .reply()
            .unwrap();
        assert!(
            (i32::from(p.dst_x) - 190).abs() <= 3 && (i32::from(p.dst_y) - 140).abs() <= 3,
            "{p:?}"
        );
        window_action(&serde_json::json!({"windowId":id,"action":"maximize"})).unwrap();
        let maximized = atom(&link, b"_NET_WM_STATE_MAXIMIZED_HORZ").unwrap();
        let state = atom(&link, b"_NET_WM_STATE").unwrap();
        wait(&|| {
            values(&link, id, state, AtomEnum::ATOM)
                .unwrap()
                .contains(&maximized)
        });
        window_action(&serde_json::json!({"windowId":id,"action":"minimize"})).unwrap();
        wait(&|| {
            link.conn
                .get_window_attributes(id)
                .unwrap()
                .reply()
                .unwrap()
                .map_state
                != MapState::VIEWABLE
        });
        link.conn.map_window(id).unwrap().check().unwrap();
        link.conn.flush().unwrap();
        wait(&|| catalogue().unwrap().1.iter().any(|w| w.id == id));
        window_action(&serde_json::json!({"windowId":id,"action":"close"})).unwrap();
        wait(&|| {
            while let Some(event) = link.conn.poll_for_event().unwrap() {
                if let x11rb::protocol::Event::ClientMessage(message) = event {
                    if message.type_ == protocols && message.data.as_data32()[0] == delete {
                        return true;
                    }
                }
            }
            false
        });
        assert!(window_action(&serde_json::json!({"windowId":id,"action":"invalid"})).is_err());
        link.conn.destroy_window(id).unwrap().check().unwrap();
    }
    #[test]
    fn dock_strips_trim_both_orientations_consistently() {
        let mut strut = vec![0; 12];
        strut[1] = 46;
        strut[6] = 0;
        strut[7] = 1599;
        assert_eq!(
            dock_content_bounds([5058, 0, 62, 1600], (5120, 3200), &strut),
            [5074, 0, 46, 1600]
        );
        strut.fill(0);
        strut[2] = 36;
        strut[8] = 4529;
        strut[9] = 5119;
        assert_eq!(
            dock_content_bounds([4529, 0, 591, 36], (5120, 3200), &strut),
            [4529, 0, 591, 36]
        );
        assert_eq!(
            dock_content_bounds([5058, 0, 62, 1600], (5120, 3200), &[]),
            [5058, 0, 62, 1600]
        );
    }
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
