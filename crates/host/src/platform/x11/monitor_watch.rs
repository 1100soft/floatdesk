//! Persistent RandR observation: idle desktops cause no probes or child processes.
use super::{monitor_pool::virtual_name, X11Link};
use crate::{
    core::MonitorRect,
    error::{Error, Result},
};
use x11rb::{
    connection::Connection,
    protocol::{
        randr::{Connection as OutputConnection, ConnectionExt as _, NotifyMask},
        Event,
    },
};

pub struct MonitorWatch {
    link: X11Link,
    cached: Vec<(String, MonitorRect)>,
    dirty: bool,
    #[cfg(test)]
    queries: usize,
}
impl MonitorWatch {
    pub fn open(initial: &[(String, MonitorRect)]) -> Result<Self> {
        let link = X11Link::open()?;
        link.conn
            .randr_query_version(1, 3)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        link.conn
            .randr_select_input(
                link.root,
                NotifyMask::SCREEN_CHANGE
                    | NotifyMask::CRTC_CHANGE
                    | NotifyMask::OUTPUT_CHANGE
                    | NotifyMask::RESOURCE_CHANGE,
            )
            .map_err(Error::x11)?
            .check()
            .map_err(Error::x11)?;
        let mut watch = Self {
            link,
            cached: Vec::new(),
            dirty: true,
            #[cfg(test)]
            queries: 0,
        };
        let current = watch.sources()?;
        // Preserve the CLI fallback for unsupported drivers and synthetic command fixtures.
        if current != initial {
            return Err(Error::Msg(
                "Native RandR catalogue differs from initial outputs".into(),
            ));
        }
        Ok(watch)
    }
    pub fn sources(&mut self) -> Result<Vec<(String, MonitorRect)>> {
        while let Some(event) = self.link.conn.poll_for_event().map_err(Error::x11)? {
            if matches!(
                event,
                Event::RandrNotify(_) | Event::RandrScreenChangeNotify(_)
            ) {
                self.dirty = true;
            }
        }
        if self.dirty {
            #[cfg(test)]
            {
                self.queries += 1;
            }
            let resources = self
                .link
                .conn
                .randr_get_screen_resources_current(self.link.root)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            let mut outputs = Vec::new();
            for id in resources.outputs {
                let output = self
                    .link
                    .conn
                    .randr_get_output_info(id, resources.config_timestamp)
                    .map_err(Error::x11)?
                    .reply()
                    .map_err(Error::x11)?;
                if output.connection != OutputConnection::CONNECTED || output.crtc == 0 {
                    continue;
                }
                let crtc = self
                    .link
                    .conn
                    .randr_get_crtc_info(output.crtc, resources.config_timestamp)
                    .map_err(Error::x11)?
                    .reply()
                    .map_err(Error::x11)?;
                if crtc.mode == 0 || crtc.width == 0 || crtc.height == 0 {
                    continue;
                }
                outputs.push((
                    String::from_utf8_lossy(&output.name).into_owned(),
                    MonitorRect {
                        index: 0,
                        x: i32::from(crtc.x),
                        y: i32::from(crtc.y),
                        width: u32::from(crtc.width),
                        height: u32::from(crtc.height),
                    },
                ));
            }
            outputs.sort_by_key(|(name, _)| (virtual_name(name), name.clone()));
            if outputs.is_empty() {
                return Err(Error::Msg("No active RandR outputs".into()));
            }
            for (i, (_, r)) in outputs.iter_mut().enumerate() {
                r.index = u8::try_from(i).unwrap_or(u8::MAX);
            }
            self.cached = outputs;
            self.dirty = false;
        }
        Ok(self.cached.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "private Xvfb only"]
    fn monitor_watch_private_kwin() {
        assert_eq!(
            std::env::var("FLOATDESK_SYNTHETIC_DISPLAY").as_deref(),
            Ok("1")
        );
        let initial = super::super::monitor_pool::MonitorPool::observer()
            .sources()
            .unwrap();
        let mut watch = MonitorWatch::open(&initial).unwrap();
        for _ in 0..32 {
            assert_eq!(watch.sources().unwrap(), initial);
        }
        assert_eq!(
            watch.queries, 1,
            "Idle reads must use cache, not re-query X11"
        );
        let link = X11Link::open().unwrap();
        let old = link
            .conn
            .randr_get_output_primary(link.root)
            .unwrap()
            .reply()
            .unwrap()
            .output;
        let outputs = link
            .conn
            .randr_get_screen_resources_current(link.root)
            .unwrap()
            .reply()
            .unwrap()
            .outputs;
        let changed = if old == 0 { outputs[0] } else { 0 };
        link.conn
            .randr_set_output_primary(link.root, changed)
            .unwrap()
            .check()
            .unwrap();
        for _ in 0..100 {
            watch.sources().unwrap();
            if watch.queries > 1 {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        link.conn
            .randr_set_output_primary(link.root, old)
            .unwrap()
            .check()
            .unwrap();
        assert!(watch.queries > 1, "Real RandR events must invalidate cache");
        assert_eq!(watch.sources().unwrap(), initial);
    }
}
