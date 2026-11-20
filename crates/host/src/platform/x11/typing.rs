//! Observe only keystroke timestamps. No keycodes or typed text leave this module.
use super::*;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    OnceLock,
};
use std::time::{Duration, Instant};
use x11rb::protocol::xinput::{ConnectionExt as _, EventMask, XIEventMask};
use x11rb::protocol::xproto::AtomEnum;
static START: OnceLock<Instant> = OnceLock::new();
static LAST: AtomicU64 = AtomicU64::new(0);
static WINDOW: AtomicU64 = AtomicU64::new(0);
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
                        mask: vec![XIEventMask::RAW_KEY_PRESS],
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
                std::thread::spawn(move || loop {
                    match link.conn.poll_for_event() {
                        Ok(Some(x11rb::protocol::Event::XinputRawKeyPress(_))) => {
                            LAST.store(now(), Ordering::Relaxed);
                            if let Ok(cookie) = link.conn.get_property(
                                false,
                                link.root,
                                active,
                                AtomEnum::WINDOW,
                                0,
                                1,
                            ) {
                                if let Ok(reply) = cookie.reply() {
                                    WINDOW.store(
                                        reply
                                            .value32()
                                            .and_then(|mut v| v.next())
                                            .unwrap_or(0)
                                            .into(),
                                        Ordering::Relaxed,
                                    );
                                }
                            }
                        }
                        Ok(Some(_)) => {}
                        Ok(None) => std::thread::sleep(Duration::from_millis(5)),
                        Err(_) => break,
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
        std::thread::sleep(Duration::from_millis(25));
        assert!(age_ms() >= 20);
    }
}
