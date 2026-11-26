//! Observe typing activity and retain only the latest key label for the connected headset.
use super::*;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Mutex, OnceLock,
};
use std::time::{Duration, Instant};
use x11rb::protocol::xinput::{ConnectionExt as _, EventMask, XIEventMask};
use x11rb::protocol::xproto::{AtomEnum, ConnectionExt as _};
static START: OnceLock<Instant> = OnceLock::new();
static LAST: AtomicU64 = AtomicU64::new(0);
static WINDOW: AtomicU64 = AtomicU64::new(0);
static LABEL: Mutex<Option<(u64, u64, String)>> = Mutex::new(None);

pub fn key_label(window: u64) -> Option<String> {
    let mut latest = LABEL.lock().ok()?;
    if latest
        .as_ref()
        .is_some_and(|(at, _, _)| now().saturating_sub(*at) >= 1500)
    {
        *latest = None;
    }
    latest
        .as_ref()
        .filter(|(_, owner, _)| *owner == window)
        .map(|(_, _, label)| label.clone())
}

fn keyboard_map(link: &X11Link) -> Option<Vec<u32>> {
    use x11rb::connection::Connection;
    let first = link.conn.setup().min_keycode;
    let last = link.conn.setup().max_keycode;
    let map = link
        .conn
        .get_keyboard_mapping(first, last.checked_sub(first)?.checked_add(1)?)
        .ok()?
        .reply()
        .ok()?;
    let mut keys = vec![0; 256];
    for (index, symbols) in map
        .keysyms
        .chunks_exact(usize::from(map.keysyms_per_keycode.max(1)))
        .enumerate()
    {
        keys[usize::from(first) + index] = symbols[0];
    }
    Some(keys)
}

fn format_key_label(symbol: u32, held: impl Fn(u32, u32) -> bool) -> Option<String> {
    let ctrl = held(0xffe3, 0xffe4);
    let alt = held(0xffe9, 0xffea);
    let super_key = held(0xffeb, 0xffec);
    let shift = held(0xffe1, 0xffe2);
    let level_shift = held(0xfe03, 0xfe11) || held(0xff7e, 0xff7e);
    let mut parts = Vec::new();
    if ctrl {
        parts.push("Ctrl".to_owned());
    }
    if alt {
        parts.push("Alt".to_owned());
    }
    if super_key {
        parts.push("Super".to_owned());
    }
    if level_shift {
        parts.push("AltGr".to_owned());
    }
    if shift {
        parts.push("Shift".to_owned());
    }
    let key = match symbol {
        0xffe1..=0xffee | 0xfe03 | 0xfe11 | 0xff7e => None,
        0xff08 => Some("Backspace".into()),
        0xff09 => Some("Tab".into()),
        0xff0d => Some("Enter".into()),
        0xff1b => Some("Esc".into()),
        0xffff => Some("Delete".into()),
        0xff50 => Some("Home".into()),
        0xff51 => Some("Left".into()),
        0xff52 => Some("Up".into()),
        0xff53 => Some("Right".into()),
        0xff54 => Some("Down".into()),
        0xff55 => Some("Page Up".into()),
        0xff56 => Some("Page Down".into()),
        0xff57 => Some("End".into()),
        0xffbe..=0xffd5 => Some(format!("F{}", symbol - 0xffbe + 1)),
        0x20 => Some("Space".into()),
        0x21..=0x7e | 0xa0..=0xff | 0x01000100..=0x0110ffff => {
            let letter = char::from_u32(if symbol >= 0x01000100 {
                symbol & 0x00ffffff
            } else {
                symbol
            })?;
            Some(if shift || ctrl || alt || super_key {
                letter.to_uppercase().collect()
            } else {
                letter.to_string()
            })
        }
        _ => return None,
    };
    if let Some(key) = key {
        parts.push(key);
    }
    (!parts.is_empty()).then(|| parts.join(" + "))
}

pub fn window_id() -> u64 {
    WINDOW.load(Ordering::Relaxed)
}
static WATCH: OnceLock<bool> = OnceLock::new();
fn now() -> u64 {
    START.get_or_init(Instant::now).elapsed().as_millis() as u64 + 1
}
pub fn age_ms() -> u64 {
    let last = LAST.load(Ordering::Relaxed);
    if last == 0 {
        u64::MAX
    } else {
        now().saturating_sub(last)
    }
}
pub fn start() -> bool {
    *WATCH.get_or_init(|| {
        let setup = (|| -> Result<X11Link> {
            let link = X11Link::open()?;
            link.conn
                .xinput_xi_query_version(2, 0)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            link.conn
                .xinput_xi_select_events(
                    link.root,
                    &[EventMask {
                        deviceid: 1,
                        mask: vec![XIEventMask::RAW_KEY_PRESS | XIEventMask::RAW_KEY_RELEASE],
                    }],
                )
                .map_err(Error::x11)?
                .check()
                .map_err(Error::x11)?;
            link.conn.flush().map_err(Error::x11)?;
            Ok(link)
        })();
        match setup {
            Ok(link) => {
                let active = link
                    .conn
                    .intern_atom(false, b"_NET_ACTIVE_WINDOW")
                    .ok()
                    .and_then(|cookie| cookie.reply().ok())
                    .map(|reply| reply.atom)
                    .unwrap_or(0);
                std::thread::spawn(move || {
                    let mut map = keyboard_map(&link);
                    let mut pressed = link
                        .conn
                        .query_keymap()
                        .ok()
                        .and_then(|cookie| cookie.reply().ok())
                        .map(|reply| reply.keys)
                        .unwrap_or([0; 32]);
                    loop {
                        match link.conn.poll_for_event() {
                            Ok(Some(x11rb::protocol::Event::XinputRawKeyPress(event))) => {
                                let at = now();
                                let owner = link
                                    .conn
                                    .get_property(false, link.root, active, AtomEnum::WINDOW, 0, 1)
                                    .ok()
                                    .and_then(|reply| reply.reply().ok())
                                    .and_then(|reply| reply.value32().and_then(|mut v| v.next()))
                                    .map(u64::from)
                                    .unwrap_or(0);
                                if let Some(byte) = pressed.get_mut(event.detail as usize / 8) {
                                    *byte |= 1 << (event.detail % 8);
                                }
                                let label = map.as_ref().and_then(|map| {
                                    let symbol = *map.get(event.detail as usize)?;
                                    format_key_label(symbol, |left, right| {
                                        map.iter().enumerate().any(|(code, symbol)| {
                                            (*symbol == left || *symbol == right)
                                                && (code == event.detail as usize
                                                    || pressed[code / 8] & (1 << (code % 8)) != 0)
                                        })
                                    })
                                });
                                if let Ok(mut latest) = LABEL.lock() {
                                    *latest = label.map(|text| (at, owner, text));
                                }
                                WINDOW.store(owner, Ordering::Relaxed);
                                LAST.store(at, Ordering::Relaxed);
                            }
                            Ok(Some(x11rb::protocol::Event::XinputRawKeyRelease(event))) => {
                                if let Some(byte) = pressed.get_mut(event.detail as usize / 8) {
                                    *byte &= !(1 << (event.detail % 8));
                                }
                            }
                            Ok(Some(x11rb::protocol::Event::MappingNotify(_))) => {
                                map = keyboard_map(&link)
                            }
                            Ok(Some(_)) => {}
                            Ok(None) => std::thread::sleep(Duration::from_millis(5)),
                            Err(_) => break,
                        }
                    }
                });
                true
            }
            Err(e) => {
                tracing::warn!(%e,"Typing protection unavailable");
                false
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn labels_normal_keys_and_shortcuts() {
        assert_eq!(
            format_key_label(b'a'.into(), |_, _| false).as_deref(),
            Some("a")
        );
        assert_eq!(
            format_key_label(b'a'.into(), |left, _| left == 0xffe1).as_deref(),
            Some("Shift + A")
        );
        assert_eq!(
            format_key_label(b'v'.into(), |left, _| left == 0xffe3).as_deref(),
            Some("Ctrl + V")
        );
        assert_eq!(
            format_key_label(0xff0d, |left, _| left == 0xffe1).as_deref(),
            Some("Shift + Enter")
        );
    }
    #[test]
    #[ignore = "private Xvfb only; observes test-generated raw keyboard timestamps"]
    fn typing_private_kwin() {
        assert_eq!(
            std::env::var("FLOATDESK_SYNTHETIC_DISPLAY").as_deref(),
            Ok("1")
        );
        assert!(start());
        let link = X11Link::open().unwrap();
        link.conn
            .xtest_fake_input(
                x11rb::protocol::xproto::KEY_PRESS_EVENT,
                38,
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
                38,
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
        for _ in 0..100 {
            if age_ms() < 1000 {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(age_ms() < 1000, "raw keystroke not observed");
        assert_eq!(key_label(window_id()).as_deref(), Some("a"));
        std::thread::sleep(Duration::from_millis(25));
        assert!(age_ms() >= 20);
    }
}
