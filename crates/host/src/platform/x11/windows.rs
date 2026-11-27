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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub transient_for: Option<u32>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub modal: bool,
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
/// Thunderbird identifies Compose by role, but does not set WM_TRANSIENT_FOR.
fn thunderbird_compose_links(roles: &[(u32, u32, Vec<u8>)]) -> Vec<(u32, u32)> {
    roles
        .iter()
        .filter_map(|(id, leader, role)| {
            if role != b"Msgcompose" {
                return None;
            }
            let mut parents = roles
                .iter()
                .filter(|(_, group, role)| group == leader && role == b"3pane");
            let parent = parents.next()?.0;
            parents.next().is_none().then_some((*id, parent))
        })
        .collect()
}

/// Chromium also uses the extension popup role for detached top-level app windows.
fn brave_extension_links(windows: &[(FocusWindow, u32, bool)]) -> Vec<(u32, u32)> {
    windows
        .iter()
        .filter_map(|(popup, pid, extension)| {
            if !extension {
                return None;
            }
            let mut owners = windows.iter().filter(|(owner, owner_pid, is_extension)| {
                !is_extension
                    && owner_pid == pid
                    && owner.width > popup.width
                    && owner.height > popup.height
                    && owner.x <= popup.x
                    && owner.y <= popup.y
                    && i64::from(owner.x) + i64::from(owner.width)
                        >= i64::from(popup.x) + i64::from(popup.width)
                    && i64::from(owner.y) + i64::from(owner.height)
                        >= i64::from(popup.y) + i64::from(popup.height)
            });
            let owner = owners.next()?.0.id;
            owners.next().is_none().then_some((popup.id, owner))
        })
        .collect()
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
    let modal = atom(&link, b"_NET_WM_STATE_MODAL")?;
    let transient = atom(&link, b"WM_TRANSIENT_FOR")?;
    let role = atom(&link, b"WM_WINDOW_ROLE")?;
    let leader = atom(&link, b"WM_CLIENT_LEADER")?;
    let mut thunderbird_roles = Vec::new();
    let pid_atom = atom(&link, b"_NET_WM_PID")?;
    let mut brave_roles = Vec::new();
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
            let states = values(&link, id, state, AtomEnum::ATOM)?;
            if states.contains(&hidden) {
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
                modal: states.contains(&modal),
                transient_for: values(&link, id, transient, AtomEnum::WINDOW)?
                    .first()
                    .copied()
                    .filter(|parent| *parent != id && *parent != link.root),
            }))
        })();
        // Windows can disappear between catalogue queries.
        if let Ok(Some(window)) = row {
            if let Ok(reply) =
                link.conn
                    .get_property(false, id, AtomEnum::WM_CLASS, AtomEnum::STRING, 0, 64)
            {
                if reply.reply().ok().is_some_and(|reply| {
                    reply.value.split(|b| *b == 0).any(|field| {
                        field.eq_ignore_ascii_case(b"thunderbird")
                            || field.eq_ignore_ascii_case(b"thunderbird_thunderbird")
                    })
                }) {
                    if let (Some(group), Some(role)) = (
                        values(&link, id, leader, AtomEnum::WINDOW)
                            .ok()
                            .and_then(|v| v.first().copied())
                            .filter(|id| *id != 0),
                        link.conn
                            .get_property(false, id, role, AtomEnum::STRING, 0, 32)
                            .ok()
                            .and_then(|reply| reply.reply().ok()),
                    ) {
                        thunderbird_roles.push((id, group, role.value));
                    }
                }
            }
            let class = link
                .conn
                .get_property(false, id, AtomEnum::WM_CLASS, AtomEnum::STRING, 0, 64)
                .ok()
                .and_then(|cookie| cookie.reply().ok());
            if let Some(class) = class {
                let mut fields = class.value.split(|byte| *byte == 0);
                let instance = fields.next().unwrap_or_default();
                let class = fields.next().unwrap_or_default();
                if class.eq_ignore_ascii_case(b"brave-browser") {
                    let role = link
                        .conn
                        .get_property(false, id, role, AtomEnum::STRING, 0, 32)
                        .ok()
                        .and_then(|cookie| cookie.reply().ok())
                        .map(|reply| reply.value);
                    let extension =
                        instance.starts_with(b"crx_") && role.as_deref() == Some(b"pop-up");
                    if extension || role.as_deref() == Some(b"browser") {
                        if let Some(pid) = values(&link, id, pid_atom, AtomEnum::CARDINAL)
                            .ok()
                            .and_then(|ids| ids.first().copied())
                            .filter(|id| *id != 0)
                        {
                            brave_roles.push((window.clone(), pid, extension));
                        }
                    }
                }
            }
            windows.push(window);
        }
    }
    for (child, owner) in thunderbird_compose_links(&thunderbird_roles)
        .into_iter()
        .chain(brave_extension_links(&brave_roles))
    {
        if let Some(row) = windows.iter_mut().find(|row| row.id == child) {
            row.transient_for.get_or_insert(owner);
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

/// Capture explicitly owned companion windows using the same atlas as transient menus.
/// Independent top-level windows never qualify based on PID or application class.
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
    let companion_types = [
        b"_NET_WM_WINDOW_TYPE_NORMAL".as_slice(),
        b"_NET_WM_WINDOW_TYPE_DIALOG",
        b"_NET_WM_WINDOW_TYPE_UTILITY",
    ]
    .iter()
    .map(|name| atom(link, name))
    .collect::<Result<Vec<_>>>()?;
    let state = atom(link, b"_NET_WM_STATE")?;
    let hidden = atom(link, b"_NET_WM_STATE_HIDDEN")?;
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
    let (_, catalogue) = observed_catalogue();
    let fallback_parent = |id| {
        catalogue
            .iter()
            .find(|row| row.id == id)
            .and_then(|row| row.transient_for)
    };
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
            let parent_of = |id| {
                values(link, id, transient, AtomEnum::WINDOW).map(|parent| {
                    parent
                        .first()
                        .copied()
                        .filter(|p| *p != link.root && *p != id)
                        .or_else(|| fallback_parent(id))
                })
            };
            let belongs_to_client = |mut parent| -> Result<bool> {
                let mut visited = std::collections::HashSet::from([id]);
                while let Some(p) = parent {
                    if p == client {
                        return Ok(true);
                    }
                    if !visited.insert(p) {
                        break;
                    }
                    parent = parent_of(p)?;
                }
                Ok(false)
            };
            let direct_parent = parent_of(id)?;
            let owned = belongs_to_client(direct_parent)?;
            let menu = kinds.iter().any(|t| menu_types.contains(t));
            if owner_is_dock && !menu {
                return Ok(None);
            }
            let companion = !a.override_redirect
                && (kinds.is_empty() || kinds.iter().any(|t| companion_types.contains(t)))
                && !values(link, id, state, AtomEnum::ATOM)?.contains(&hidden);
            let capture_parent = if owned && (menu || a.override_redirect || companion) {
                direct_parent
            } else if menu && direct_parent.is_none() && belongs_to_client(active)? {
                // Some toolkits leave the context menu's parent unset. Resolve it
                // to the active window within this already-validated capture tree.
                let active = active.unwrap_or(client);
                let active_pid = if active == client {
                    owner_pid
                } else {
                    values(link, active, pid_atom, AtomEnum::CARDINAL)?
                        .first()
                        .copied()
                };
                let menu_pid = values(link, id, pid_atom, AtomEnum::CARDINAL)?
                    .first()
                    .copied();
                active_pid
                    .filter(|pid| Some(*pid) == menu_pid)
                    .map(|_| active)
            } else {
                None
            };
            if capture_parent.is_none() {
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
                transient_for: capture_parent,
                modal: false,
            }))
        })();
        if let Ok(Some(window)) = item {
            result.push(window);
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
            if kinds.contains(&desktop) || kinds.contains(&notification) || kinds.contains(&osd) {
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
            // Plasma task thumbnails are tooltip windows. Leave small textual hints in
            // the desktop image and mirror the substantial previews next to their panel.
            if kinds.contains(&tooltip) && (!plasma || g.width < 64 || g.height < 64) {
                return Ok(None);
            }
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
                transient_for: None,
                modal: false,
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

fn kwin(link: &X11Link) -> Result<bool> {
    let root = link.root;
    let check = atom(link, b"_NET_SUPPORTING_WM_CHECK")?;
    let Some(manager) = values(link, root, check, AtomEnum::WINDOW)?
        .first()
        .copied()
    else {
        return Ok(false);
    };
    let name = link
        .conn
        .get_property(
            false,
            manager,
            atom(link, b"_NET_WM_NAME")?,
            atom(link, b"UTF8_STRING")?,
            0,
            32,
        )
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    Ok(name.value.starts_with(b"KWin"))
}

fn run_kwin_script(source: &str) -> Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let plugin = format!(
        "floatdesk-geometry-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    );
    let directory = std::env::var_os("XDG_RUNTIME_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(std::env::temp_dir);
    let path = directory.join(format!("{plugin}.js"));
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&path)?;
    let call = |object: &str, method: &str, args: &[&std::ffi::OsStr]| -> Result<String> {
        let output = std::process::Command::new("gdbus")
            .args([
                "call",
                "--session",
                "--timeout=1",
                "--dest",
                "org.kde.KWin",
                "--object-path",
                object,
                "--method",
                method,
            ])
            .args(args)
            .output()?;
        if !output.status.success() {
            return Err(Error::Msg(
                String::from_utf8_lossy(&output.stderr).trim().to_owned(),
            ));
        }
        Ok(String::from_utf8_lossy(&output.stdout).into_owned())
    };
    let result = (|| {
        file.write_all(source.as_bytes())?;
        let output = call(
            "/Scripting",
            "org.kde.kwin.Scripting.loadScript",
            &[path.as_os_str(), plugin.as_ref()],
        )?;
        let id = output
            .trim()
            .trim_start_matches('(')
            .split(',')
            .next()
            .and_then(|v| v.parse::<u32>().ok())
            .ok_or_else(|| Error::Msg("KWin did not load the placement script".into()))?;
        let result = call(
            &format!("/Scripting/Script{id}"),
            "org.kde.kwin.Script.run",
            &[],
        )
        .or_else(|_| call(&format!("/{id}"), "org.kde.kwin.Script.run", &[]));
        let _ = call(
            "/Scripting",
            "org.kde.kwin.Scripting.unloadScript",
            &[plugin.as_ref()],
        );
        result.map(|_| ())
    })();
    let _ = std::fs::remove_file(path);
    result
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
    if action == "close" {
        let protocols = atom(&link, b"WM_PROTOCOLS")?;
        let delete = atom(&link, b"WM_DELETE_WINDOW")?;
        if values(&link, id, protocols, AtomEnum::ATOM)?.contains(&delete) {
            link.conn
                .send_event(
                    false,
                    id,
                    EventMask::NO_EVENT,
                    ClientMessageEvent::new(
                        32,
                        id,
                        protocols,
                        [delete, x11rb::CURRENT_TIME, 0, 0, 0],
                    ),
                )
                .map_err(Error::x11)?
                .check()
                .map_err(Error::x11)?;
            return link.conn.flush().map_err(Error::x11);
        }
    }
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
            let state = atom(&link, b"_NET_WM_STATE")?;
            let fullscreen = atom(&link, b"_NET_WM_STATE_FULLSCREEN")?;
            if values(&link, id, state, AtomEnum::ATOM)?.contains(&fullscreen) {
                link.conn
                    .send_event(
                        false,
                        link.root,
                        mask,
                        ClientMessageEvent::new(32, id, state, [0, fullscreen, 0, 2, 0]),
                    )
                    .map_err(Error::x11)?
                    .check()
                    .map_err(Error::x11)?;
            }
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
            if kwin(&link).unwrap_or(false) {
                // KWin ignores EWMH geometry while a window is quick-tiled.
                // Clear its tile without a native drag, then apply the release geometry below.
                let script = format!(
                    "const w = workspace.getClient({id}); if (w && w.tile) {{ w.tile = null; }}"
                );
                if let Err(error) = run_kwin_script(&script) {
                    tracing::debug!(%error, "KWin tile reset unavailable; trying EWMH geometry");
                }
            }
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

    #[test]
    fn brave_popup_requires_an_unambiguous_containing_window() {
        let row = |id, x, y, width, height| FocusWindow {
            id,
            x,
            y,
            width,
            height,
            panel: false,
            transient_for: None,
            modal: false,
        };
        let mut windows = vec![
            (row(1, 0, 0, 854, 533), 5, false),
            (row(2, 1706, 2133, 854, 533), 5, false),
            (row(3, 4236, 1078, 838, 522), 5, true),
            (row(4, 1800, 2200, 400, 400), 5, true),
        ];
        assert_eq!(brave_extension_links(&windows), vec![(4, 2)]);
        windows.push((row(5, 1710, 2140, 854, 533), 5, false));
        assert!(brave_extension_links(&windows).is_empty());
    }

    #[test]
    fn compose_requires_a_unique_matching_thunderbird_leader() {
        let mut roles = vec![
            (1, 10, b"3pane".to_vec()),
            (2, 10, b"Msgcompose".to_vec()),
            (3, 11, b"Msgcompose".to_vec()),
            (4, 10, b"MessageWindow".to_vec()),
        ];
        assert_eq!(thunderbird_compose_links(&roles), vec![(2, 1)]);
        roles.push((5, 10, b"3pane".to_vec()));
        assert!(thunderbird_compose_links(&roles).is_empty());
    }
    use x11rb::protocol::xproto::{CreateWindowAux, PropMode, WindowClass};
    use x11rb::wrapper::ConnectionExt as _;
    #[test]
    #[ignore = "private Xvfb/KWin only"]
    fn taskbar_preview_private_kwin() {
        assert_eq!(
            std::env::var("FLOATDESK_SYNTHETIC_DISPLAY").as_deref(),
            Ok("1")
        );
        let link = X11Link::open().unwrap();
        let create_tooltip = |class: &[u8], width, height| {
            let id = link.conn.generate_id().unwrap();
            link.conn
                .create_window(
                    x11rb::COPY_DEPTH_FROM_PARENT,
                    id,
                    link.root,
                    40,
                    40,
                    width,
                    height,
                    0,
                    WindowClass::INPUT_OUTPUT,
                    0,
                    &CreateWindowAux::new().override_redirect(1),
                )
                .unwrap()
                .check()
                .unwrap();
            link.conn
                .change_property8(
                    PropMode::REPLACE,
                    id,
                    atom(&link, b"WM_CLASS").unwrap(),
                    AtomEnum::STRING,
                    class,
                )
                .unwrap()
                .check()
                .unwrap();
            link.conn
                .change_property32(
                    PropMode::REPLACE,
                    id,
                    atom(&link, b"_NET_WM_WINDOW_TYPE").unwrap(),
                    AtomEnum::ATOM,
                    &[atom(&link, b"_NET_WM_WINDOW_TYPE_TOOLTIP").unwrap()],
                )
                .unwrap()
                .check()
                .unwrap();
            link.conn.map_window(id).unwrap().check().unwrap();
            id
        };
        let preview = create_tooltip(b"plasmashell\0plasmashell\0", 280, 160);
        let text = create_tooltip(b"plasmashell\0plasmashell\0", 120, 28);
        let unrelated = create_tooltip(b"unrelated\0unrelated\0", 280, 160);
        let catalogue = system_catalogue().unwrap();
        assert!(catalogue
            .iter()
            .any(|w| w.id == preview && !w.panel && w.width == 280 && w.height == 160));
        assert!(!catalogue.iter().any(|w| w.id == text || w.id == unrelated));
    }

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
        let fullscreen = atom(&link, b"_NET_WM_STATE_FULLSCREEN").unwrap();
        let state = atom(&link, b"_NET_WM_STATE").unwrap();
        link.conn
            .send_event(
                false,
                link.root,
                EventMask::SUBSTRUCTURE_REDIRECT | EventMask::SUBSTRUCTURE_NOTIFY,
                ClientMessageEvent::new(32, id, state, [1, fullscreen, 0, 2, 0]),
            )
            .unwrap()
            .check()
            .unwrap();
        link.conn.flush().unwrap();
        wait(&|| {
            values(&link, id, state, AtomEnum::ATOM)
                .unwrap()
                .contains(&fullscreen)
        });
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
        run_kwin_script(&format!("workspace.activeWindow = workspace.getClient({id}); workspace.slotWindowQuickTileLeft();")).unwrap();
        wait(&|| link.conn.get_geometry(id).unwrap().reply().unwrap().width > 500);
        window_action(&serde_json::json!({"windowId":id,"action":"geometry","x":270,"y":170,"width":330,"height":240})).unwrap();
        wait(&|| {
            let g = link.conn.get_geometry(id).unwrap().reply().unwrap();
            g.width == 330 && g.height == 240
        });
        run_kwin_script("workspace.slotWindowQuickTileRight();").unwrap();
        wait(&|| link.conn.get_geometry(id).unwrap().reply().unwrap().width > 500);
        window_action(&serde_json::json!({"windowId":id,"action":"move","x":160,"y":120})).unwrap();
        wait(&|| {
            let p = link
                .conn
                .translate_coordinates(id, link.root, 0, 0)
                .unwrap()
                .reply()
                .unwrap();
            (i32::from(p.dst_x) - 160).abs() <= 3 && (i32::from(p.dst_y) - 120).abs() <= 3
        });
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
        assert!(super::super::typing::start());
        use x11rb::protocol::xtest::ConnectionExt as _;
        link.conn
            .xtest_fake_input(
                x11rb::protocol::xproto::KEY_PRESS_EVENT,
                36,
                0,
                link.root,
                0,
                0,
                0,
            )
            .unwrap()
            .check()
            .unwrap();
        link.conn
            .xtest_fake_input(
                x11rb::protocol::xproto::KEY_RELEASE_EVENT,
                36,
                0,
                link.root,
                0,
                0,
                0,
            )
            .unwrap()
            .check()
            .unwrap();
        link.conn.flush().unwrap();
        wait(&|| {
            super::super::typing::age_ms() < 1000
                && super::super::typing::window_id() == u64::from(ids[0])
        });
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
        let role = atom(&link, b"WM_WINDOW_ROLE").unwrap();
        let leader = atom(&link, b"WM_CLIENT_LEADER").unwrap();
        let protocols = atom(&link, b"WM_PROTOCOLS").unwrap();
        let delete = atom(&link, b"WM_DELETE_WINDOW").unwrap();
        for (id, window_role) in [(ids[0], b"3pane".as_slice()), (ids[1], b"Msgcompose")] {
            link.conn
                .change_property8(
                    PropMode::REPLACE,
                    id,
                    AtomEnum::WM_CLASS,
                    AtomEnum::STRING,
                    b"Thunderbird\0thunderbird_thunderbird\0",
                )
                .unwrap()
                .check()
                .unwrap();
            link.conn
                .change_property8(PropMode::REPLACE, id, role, AtomEnum::STRING, window_role)
                .unwrap()
                .check()
                .unwrap();
            link.conn
                .change_property32(PropMode::REPLACE, id, leader, AtomEnum::WINDOW, &[ids[0]])
                .unwrap()
                .check()
                .unwrap();
        }
        link.conn
            .change_property32(
                PropMode::REPLACE,
                ids[1],
                protocols,
                AtomEnum::ATOM,
                &[delete],
            )
            .unwrap()
            .check()
            .unwrap();
        link.conn
            .change_property32(
                PropMode::REPLACE,
                ids[1],
                atom(&link, b"_NET_WM_STATE").unwrap(),
                AtomEnum::ATOM,
                &[atom(&link, b"_NET_WM_STATE_MODAL").unwrap()],
            )
            .unwrap()
            .check()
            .unwrap();
        let (cap, rows) = catalogue().unwrap();
        assert!(cap);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].id, ids[1]);
        assert_eq!(rows[0].transient_for, Some(ids[0]));
        assert!(rows[0].modal);
        window_action(&serde_json::json!({"windowId":ids[1],"action":"close"})).unwrap();
        let event = link.conn.wait_for_event().unwrap();
        assert!(matches!(event, x11rb::protocol::Event::ClientMessage(event)
            if event.window == ids[1] && event.type_ == protocols && event.data.as_data32()[0] == delete));
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
