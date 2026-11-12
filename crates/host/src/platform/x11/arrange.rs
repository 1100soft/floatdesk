//! Position-only RandR transaction. Suspend virtual scanout before resizing the root
//! pixmap, avoiding repeated sub-Hz vblank waits for each intermediate modeset.
use super::X11Link;
use crate::error::{Error, Result};
use x11rb::connection::Connection;
use x11rb::protocol::randr::{ConnectionExt as _, GetCrtcInfoReply, SetConfig};
use x11rb::protocol::xproto::ConnectionExt as _;

struct Crtc {
    id: u32,
    before: GetCrtcInfoReply,
    position: (i16, i16),
    suspend: bool,
}
fn set(link: &X11Link, row: &Crtc, off: bool, restore: bool) -> Result<()> {
    let resources = link
        .conn
        .randr_get_screen_resources_current(link.root)
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    let (x, y) = if restore {
        (row.before.x, row.before.y)
    } else {
        row.position
    };
    let reply = link
        .conn
        .randr_set_crtc_config(
            row.id,
            x11rb::CURRENT_TIME,
            resources.config_timestamp,
            x,
            y,
            if off { 0 } else { row.before.mode },
            row.before.rotation,
            if off { &[] } else { &row.before.outputs },
        )
        .map_err(Error::x11)?
        .reply()
        .map_err(Error::x11)?;
    if reply.status != SetConfig::SUCCESS {
        return Err(Error::Msg(format!(
            "RandR position transaction rejected: {:?}",
            reply.status
        )));
    }
    Ok(())
}
fn size(link: &X11Link, width: u16, height: u16, mm: (u32, u32)) -> Result<()> {
    link.conn
        .randr_set_screen_size(link.root, width, height, mm.0.max(1), mm.1.max(1))
        .map_err(Error::x11)?
        .check()
        .map_err(Error::x11)
}
/// Returns false when no virtual scanout is present; retain the desktop backend then.
pub fn move_positions(positions: &[(String, i32, i32)]) -> Result<bool> {
    let link = X11Link::open()?;
    // A dropped connection also releases the server if collection or rollback fails.
    link.conn
        .grab_server()
        .map_err(Error::x11)?
        .check()
        .map_err(Error::x11)?;
    let result = (|| {
        let geometry = link
            .conn
            .get_geometry(link.root)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let screen = &link.conn.setup().roots[link.screen];
        let mm = (
            u32::from(screen.width_in_millimeters),
            u32::from(screen.height_in_millimeters),
        );
        let resources = link
            .conn
            .randr_get_screen_resources_current(link.root)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let mut rows = Vec::new();
        let mut width = 0u16;
        let mut height = 0u16;
        let mut virtual_present = false;
        for id in &resources.crtcs {
            let before = link
                .conn
                .randr_get_crtc_info(*id, resources.config_timestamp)
                .map_err(Error::x11)?
                .reply()
                .map_err(Error::x11)?;
            if before.mode == 0 {
                continue;
            }
            let mut target = None;
            let mut virtual_crtc = false;
            for output in &before.outputs {
                let info = link
                    .conn
                    .randr_get_output_info(*output, resources.config_timestamp)
                    .map_err(Error::x11)?
                    .reply()
                    .map_err(Error::x11)?;
                let name = String::from_utf8_lossy(&info.name);
                virtual_crtc |= super::monitor_pool::virtual_name(&name);
                let Some((_, x, y)) = positions.iter().find(|(n, _, _)| n == name.as_ref()) else {
                    return Err(Error::Msg(
                        "Arrangement changed before applying move".into(),
                    ));
                };
                let p = (
                    i16::try_from(*x).map_err(Error::x11)?,
                    i16::try_from(*y).map_err(Error::x11)?,
                );
                if target.is_some_and(|previous| previous != p) {
                    return Ok(false);
                } // Mirrored outputs need the desktop backend.
                target = Some(p);
            }
            let position = target.unwrap_or((before.x, before.y));
            width = width.max(
                u16::try_from(i32::from(position.0) + i32::from(before.width))
                    .map_err(Error::x11)?,
            );
            height = height.max(
                u16::try_from(i32::from(position.1) + i32::from(before.height))
                    .map_err(Error::x11)?,
            );
            virtual_present |= virtual_crtc;
            let suspend = virtual_crtc || position != (before.x, before.y);
            rows.push(Crtc {
                id: *id,
                before,
                position,
                suspend,
            });
        }
        if !virtual_present {
            return Ok(false);
        }
        if rows.iter().all(|r| r.position == (r.before.x, r.before.y)) {
            return Ok(true);
        }
        let range = link
            .conn
            .randr_get_screen_size_range(link.root)
            .map_err(Error::x11)?
            .reply()
            .map_err(Error::x11)?;
        let (width, height) = (width.max(range.min_width), height.max(range.min_height));
        if width > range.max_width || height > range.max_height {
            return Err(Error::Msg("Arrangement exceeds RandR bounds".into()));
        }
        let apply: Result<()> = (|| {
            for row in rows.iter().filter(|r| r.suspend) {
                set(&link, row, true, false)?;
            }
            size(
                &link,
                width,
                height,
                (
                    mm.0 * u32::from(width) / u32::from(geometry.width.max(1)),
                    mm.1 * u32::from(height) / u32::from(geometry.height.max(1)),
                ),
            )?;
            for row in rows.iter().filter(|r| r.suspend) {
                set(&link, row, false, false)?;
            }
            Ok(())
        })();
        if let Err(error) = apply {
            // Restore every touched CRTC and the original root, even after partial application.
            let mut failed = false;
            for row in rows.iter().filter(|r| r.suspend) {
                failed |= set(&link, row, true, true).is_err();
            }
            failed |= size(&link, geometry.width, geometry.height, mm).is_err();
            for row in rows.iter().filter(|r| r.suspend) {
                failed |= set(&link, row, false, true).is_err();
            }
            return Err(Error::Msg(format!(
                "{error}; original arrangement {}",
                if failed {
                    "could not be fully restored"
                } else {
                    "restored"
                }
            )));
        }
        Ok(true)
    })();
    link.conn
        .ungrab_server()
        .map_err(Error::x11)?
        .check()
        .map_err(Error::x11)?;
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "temporarily moves a live virtual output; explicit FLOATDESK_TEST_LIVE_ARRANGE=1 required"]
    fn live_move_and_restore() {
        assert_eq!(
            std::env::var("FLOATDESK_TEST_LIVE_ARRANGE").as_deref(),
            Ok("1")
        );
        let outputs = super::super::monitor_pool::query().unwrap();
        let original: Vec<_> = outputs
            .iter()
            .filter_map(|o| o.geometry.map(|g| (o.name.clone(), g.0, g.1)))
            .collect();
        let target = outputs
            .iter()
            .filter(|o| o.name.starts_with("Virtual-") && o.geometry.is_some())
            .max_by_key(|o| o.geometry.unwrap().0)
            .unwrap();
        let mut moved = original.clone();
        moved.iter_mut().find(|r| r.0 == target.name).unwrap().1 += 64;
        let start = std::time::Instant::now();
        let result = move_positions(&moved);
        let elapsed = start.elapsed();
        let observed = super::super::monitor_pool::query();
        let restored = move_positions(&original);
        assert!(restored.unwrap());
        assert!(result.unwrap());
        for o in observed.unwrap() {
            if let Some((x, y, w, h)) = o.geometry {
                let (_, tx, ty) = moved.iter().find(|r| r.0 == o.name).unwrap();
                assert_eq!((x, y), (*tx, *ty));
                let before = outputs.iter().find(|r| r.name == o.name).unwrap();
                assert_eq!(before.geometry.map(|g| (g.2, g.3)), Some((w, h)));
                assert_eq!(before.current_rate, o.current_rate);
                assert_eq!(before.primary, o.primary);
            }
        }
        let after = super::super::monitor_pool::query().unwrap();
        for before in &outputs {
            let current = after.iter().find(|o| o.name == before.name).unwrap();
            assert_eq!(before.geometry, current.geometry);
            assert_eq!(before.current_rate, current.current_rate);
            assert_eq!(before.primary, current.primary);
        }
        println!("Guarded live layout move: {elapsed:?}; original geometry, modes, rates and primary restored");
    }
}
