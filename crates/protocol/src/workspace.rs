//! One workspace anchor, many monitor-local poses.
//!
//! `world_from_monitor = world_from_workspace ∘ workspace_from_monitor`.
//! Only the workspace pose is anchored to the room. Grabbing one monitor
//! changes that monitor's child pose. Moving the workspace leaves every
//! child pose alone.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const SCHEMA_VERSION: u32 = 1;

/// Meters, right-handed. `+X` right, `+Y` up, `-Z` toward the user when the
/// workspace faces them. Rotation is a unit quaternion in `xyzw` order.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Pose {
    pub position: [f32; 3],
    pub rotation: [f32; 4],
}

impl Pose {
    pub fn identity() -> Self {
        Self {
            position: [0.0, 0.0, 0.0],
            rotation: [0.0, 0.0, 0.0, 1.0],
        }
    }

    pub fn translation(x: f32, y: f32, z: f32) -> Self {
        Self {
            position: [x, y, z],
            rotation: [0.0, 0.0, 0.0, 1.0],
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WorkspaceMonitor {
    pub logical_monitor_uuid: String,
    pub host_source_id: String,
    pub pose_in_workspace: Pose,
    pub physical_width_m: f32,
    pub physical_height_m: f32,
    pub curvature: f32,
    pub pixel_width: u32,
    pub pixel_height: u32,
    pub preferred_refresh_hz: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WorkspaceFile {
    pub schema_version: u32,
    pub active_layout: String,
    pub anchor_uuid: Option<String>,
    pub layouts: BTreeMap<String, Vec<WorkspaceMonitor>>,
}

/// `parent` is the workspace in world space. `child` is the monitor in workspace space.
pub fn compose_pose(parent: Pose, child: Pose) -> Pose {
    Pose {
        position: add(parent.position, rotate(parent.rotation, child.position)),
        rotation: quat_mul(parent.rotation, child.rotation),
    }
}

/// Desk layout: a row of 0.60 m wide panels, 1.2 m in front of the workspace origin.
pub fn desk_layout(monitors: &[(String, u32, u32)]) -> Vec<WorkspaceMonitor> {
    const WIDTH_M: f32 = 0.60;
    const GAP_M: f32 = 0.04;
    const Z: f32 = -1.20;
    let n = monitors.len() as f32;
    let span = if monitors.is_empty() {
        0.0
    } else {
        n * WIDTH_M + (n - 1.0) * GAP_M
    };
    let mut x = -span / 2.0 + WIDTH_M / 2.0;
    monitors
        .iter()
        .map(|(id, px_w, px_h)| {
            let aspect = (*px_w as f32 / (*px_h).max(1) as f32).max(0.1);
            let height = WIDTH_M / aspect;
            let pose = Pose::translation(x, 0.0, Z);
            x += WIDTH_M + GAP_M;
            WorkspaceMonitor {
                logical_monitor_uuid: format!("monitor-{id}"),
                host_source_id: id.clone(),
                pose_in_workspace: pose,
                physical_width_m: WIDTH_M,
                physical_height_m: height,
                curvature: 0.0,
                pixel_width: *px_w,
                pixel_height: *px_h,
                preferred_refresh_hz: 60,
            }
        })
        .collect()
}

fn quat_mul(a: [f32; 4], b: [f32; 4]) -> [f32; 4] {
    let (ax, ay, az, aw) = (a[0], a[1], a[2], a[3]);
    let (bx, by, bz, bw) = (b[0], b[1], b[2], b[3]);
    [
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
        aw * bw - ax * bx - ay * by - az * bz,
    ]
}

fn rotate(q: [f32; 4], v: [f32; 3]) -> [f32; 3] {
    let qv = [v[0], v[1], v[2], 0.0];
    let inv = [-q[0], -q[1], -q[2], q[3]];
    let r = quat_mul(quat_mul(q, qv), inv);
    [r[0], r[1], r[2]]
}

fn add(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_composition_keeps_the_child_pose() {
        let child = Pose::translation(0.3, -0.1, -1.2);
        let world = compose_pose(Pose::identity(), child);
        assert_eq!(world.position, child.position);
        assert_eq!(world.rotation, child.rotation);
    }

    #[test]
    fn workspace_translation_moves_every_monitor_together() {
        let monitors = desk_layout(&[("a".into(), 1920, 1080), ("b".into(), 1920, 1080)]);
        let workspace = Pose::translation(1.0, 0.0, 0.5);
        let left = compose_pose(workspace, monitors[0].pose_in_workspace);
        let right = compose_pose(workspace, monitors[1].pose_in_workspace);
        let dx = right.position[0] - left.position[0];
        assert!((dx - 0.64).abs() < 1e-4, "inter-monitor gap drifted: {dx}");
        assert!(
            (left.position[0] - (monitors[0].pose_in_workspace.position[0] + 1.0)).abs() < 1e-4
        );
    }

    #[test]
    fn yaw_rotates_child_offsets_around_the_workspace() {
        // 90 degrees about Y: +X goes to -Z.
        let half = std::f32::consts::FRAC_1_SQRT_2;
        let workspace = Pose {
            position: [0.0, 0.0, 0.0],
            rotation: [0.0, half, 0.0, half],
        };
        let child = Pose::translation(1.0, 0.0, 0.0);
        let world = compose_pose(workspace, child);
        assert!(world.position[0].abs() < 1e-5);
        assert!((world.position[2] + 1.0).abs() < 1e-5);
    }

    #[test]
    fn desk_row_is_centered() {
        let row = desk_layout(&[
            ("0".into(), 1920, 1080),
            ("1".into(), 1920, 1080),
            ("2".into(), 1920, 1080),
        ]);
        assert_eq!(row.len(), 3);
        assert!(row[1].pose_in_workspace.position[0].abs() < 1e-5);
        assert!(row[0].pose_in_workspace.position[0] < 0.0);
        assert!(row[2].pose_in_workspace.position[0] > 0.0);
        assert!((row[0].physical_height_m - 0.3375).abs() < 1e-4);
        let json = serde_json::to_string(&row).unwrap();
        let back: Vec<WorkspaceMonitor> = serde_json::from_str(&json).unwrap();
        assert_eq!(back, row);
    }
}
