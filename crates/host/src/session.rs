//! Session loop. Each monitor has its own capture thread, encoder, and RTP
//! stream. The cursor is polled independently and composited into captured frames.
//! Nothing is encoded until a client hello arrives.

mod cursor;
mod cursor_map;
mod detail;
mod receiver;
mod resources;
mod streaming;
mod transport;
mod window;
use cursor::*;
pub use receiver::receive;
use streaming::*;
use transport::*;

use crate::core::{
    epoch_ns, monotonic_ns, CaptureBackend, CursorBackend, DisplayBackend, EncodeBackend,
    MonitorRect,
};
use crate::error::{Error, Result};
use crate::media::GstEncoder;
use crate::platform::x11::monitor_pool::{desktop_rect, stream_size, MonitorPool};
use crate::platform::x11::{X11Capture, X11Cursor, X11Display};
use floatdesk_protocol::{
    allocate_bitrate, rtp_timestamp, ControlPacket, LayoutEntry, MonitorHello, MonitorLoad,
    RtpH264Packetizer, BEACON_PORT,
};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::thread;
use std::time::{Duration, Instant};

pub struct ServeOpts {
    pub listen: SocketAddr,
    pub monitors: u8,
    pub width: u16,
    pub height: u16,
    pub fps: u32,
    pub bitrate_bps: u32,
    pub existing: bool,
    pub encoder: String,
    pub duration: Option<Duration>,
}

#[derive(Clone)]
struct ClientTarget {
    control: SocketAddr,
    video_port: u16,
    generation: u64,
    pointer_sequence: Option<u32>,
    click_sequence: Option<u32>,
    touchpad_sequence: Option<u32>,
}

struct InputDevices {
    mouse: crate::platform::x11::X11Mouse,
    touchpad: Option<crate::platform::touchpad::VirtualTouchpad>,
}
impl InputDevices {
    fn open() -> Result<Self> {
        let touchpad = match crate::platform::touchpad::VirtualTouchpad::open() {
            Ok(device) => {
                tracing::info!(syspath=?device.syspath(),"native virtual touchpad ready");
                Some(device)
            }
            Err(error) => {
                tracing::warn!(%error,"native touchpad unavailable; mouse input remains available");
                None
            }
        };
        Ok(Self {
            mouse: crate::platform::x11::X11Mouse::open()?,
            touchpad,
        })
    }
    fn move_pointer(&mut self, x: i32, y: i32, down: bool) -> Result<()> {
        self.mouse.move_pointer(x, y, down)
    }
    fn click(&mut self, x: i32, y: i32, button: u8) -> Result<()> {
        self.mouse.click(x, y, button)
    }
    fn release(&mut self) {
        let _ = self.mouse.release();
        if let Some(pad) = &mut self.touchpad {
            let _ = pad.release();
        }
    }
    fn expire(&mut self) {
        self.mouse.expire();
        if let Some(pad) = &mut self.touchpad {
            pad.expire();
        }
    }
}

struct CursorSprite {
    root_x: i32,
    root_y: i32,
    hotspot_x: i16,
    hotspot_y: i16,
    width: u16,
    height: u16,
    /// BGRA, length width * height * 4. Empty until the first shape arrives.
    pixels: Vec<u8>,
}

#[derive(Clone)]
struct CachedRtp {
    monitor: u8,
    ssrc: u32,
    sequence: u16,
    peer: SocketAddr,
    dest: SocketAddr,
    bytes: Vec<u8>,
}
struct Shared {
    stop: AtomicBool,
    repairs: Mutex<std::collections::VecDeque<CachedRtp>>,
    repair_window: Mutex<(Instant, u16)>,
    repaired: AtomicU64,
    screens: RwLock<Vec<MonitorRect>>,
    client: RwLock<Option<ClientTarget>>,
    session_id: u64,
    view_until_ns: AtomicU64,
    encoder_quality: AtomicU32,
    encoder_bitrate: AtomicU32,
    encoder_generation: AtomicU64,
    encoded_frames: AtomicU64,
    view_samples: AtomicU64,
    view_capture_ns: AtomicU64,
    view_encode_ns: AtomicU64,
    keys: [AtomicBool; 6],
    captured: AtomicU64,
    skipped: AtomicU64,
    bytes: AtomicU64,
    cursor: Mutex<CursorSprite>,
    cursor_generation: AtomicU64,
    rendering: Mutex<(Option<SocketAddr>, bool)>,
    cursor_map: Mutex<(Vec<cursor_map::Surface>, [f64; 2], Instant)>,
    /// X11 socket of the capture thread. -1 when capture is not connected.
    capture_fd: [AtomicI32; 6],
    stream_active: [AtomicBool; 6],
    stream_ssrc: [AtomicU32; 6],
    stream_progress: [AtomicU64; 6],
}

pub fn serve(opts: ServeOpts) -> Result<()> {
    let _epoch = epoch_ns();
    let mut display = X11Display::open()?;
    let mut pool = MonitorPool::new();
    if opts.existing {
        let root = std::env::var_os("FLOATDESK_DATA_DIR")
            .map(std::path::PathBuf::from)
            .unwrap_or_else(|| std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../.."));
        let _ = std::process::Command::new("python3")
            .arg(root.join("scripts/auto-virtual-displays.py"))
            .status();
    }
    let sources = if opts.existing {
        match pool.sources() {
            Ok(sources) => sources,
            Err(error) => {
                tracing::warn!(%error,"XRandR catalogue unavailable; streaming primary display only");
                vec![("existing-0".into(), display.primary()?)]
            }
        }
    } else {
        Vec::new()
    };
    let monitors = if opts.existing {
        vec![desktop_rect(&sources)?]
    } else {
        display.create_virtual(opts.monitors, opts.width, opts.height)?
    };
    if monitors.is_empty() {
        return Err(Error::Msg("no monitors to stream".into()));
    }
    let result = run(
        &monitors,
        &opts,
        if opts.existing { Some(&mut pool) } else { None },
        sources,
    );
    if !opts.existing {
        display.restore()?;
    }
    result
}

pub fn trial() -> Result<()> {
    let mut display = X11Display::open()?;
    let created = display.create_virtual(1, 1280, 720)?;
    println!("created {} logical monitor(s):", created.len());
    for (name, info) in display.list_monitors()? {
        println!(
            "  {name} {}x{}+{}+{} outputs {}",
            info.width,
            info.height,
            info.x,
            info.y,
            info.outputs.len()
        );
    }
    display.restore()?;
    let fb = display.link().geometry()?;
    println!("restored framebuffer {}x{}", fb.width, fb.height);
    Ok(())
}

fn send_reload(shared: &Shared, socket: &UdpSocket, reloading: bool) {
    if let Some(client) = shared
        .client
        .read()
        .unwrap_or_else(|e| e.into_inner())
        .as_ref()
    {
        let _ = socket.send_to(
            &ControlPacket::StreamState { reloading }.encode(),
            client.control,
        );
    }
}
fn source_hellos(monitors: &[MonitorRect], sources: &[(String, MonitorRect)]) -> Vec<MonitorHello> {
    monitors
        .iter()
        .map(|r| MonitorHello {
            index: r.index,
            width: stream_size(*r).0,
            height: stream_size(*r).1,
            port: 0,
            ssrc: ssrc_for(r.index),
            name: if sources.is_empty() {
                r.name()
            } else {
                "existing-0".into()
            },
        })
        .collect()
}
fn display_status(
    pool: Option<&MonitorPool>,
    sources: &[(String, MonitorRect)],
    request_id: u32,
    ok: bool,
    message: String,
) -> ControlPacket {
    let sources = &sources[..sources.len().min(6)];
    let (names, roles, primary) = pool.map_or((Vec::new(), Vec::new(), 0), |p| p.identity(sources));
    ControlPacket::MonitorStatus {
        request_id,
        names,
        roles,
        primary,
        ok,
        available: pool.map_or(0, |p| p.capacity()),
        owned: pool.map_or(0, |p| p.owned_mask(sources)),
        screens: sources
            .iter()
            .map(|(_, r)| (r.width as u16, r.height as u16))
            .collect(),
        positions: sources
            .iter()
            .map(|(_, r)| (r.x as i16, r.y as i16))
            .collect(),
        message,
    }
}

fn publish_workspace(
    pool: Option<&MonitorPool>,
    socket: &UdpSocket,
    to: SocketAddr,
    id: u32,
    busy: bool,
    fps: u32,
) {
    if let Some(pool) = pool {
        let json = pool.workspace_info(id, busy, fps).to_string();
        if json.len() <= 60000 {
            let _ = socket.send_to(&ControlPacket::Workspace { json }.encode(), to);
        }
    }
}
fn focus_desktop_target(
    value: &serde_json::Value,
    sources: &[(String, MonitorRect)],
    pointer: &mut InputDevices,
) -> Result<[f32; 4]> {
    let bounds = desktop_rect(sources)?;
    let (x, y, width, height) = if let Some(id) = value["windowId"]
        .as_u64()
        .and_then(|v| u32::try_from(v).ok())
    {
        let (_, windows) = crate::platform::x11::windows::catalogue()?;
        let window = windows
            .iter()
            .find(|w| w.id == id)
            .ok_or_else(|| Error::Msg("Selected window is no longer visible".into()))?;
        crate::platform::x11::windows::activate(id)?;
        (window.x, window.y, window.width, window.height)
    } else {
        let name = value["screen"]
            .as_str()
            .ok_or_else(|| Error::Msg("No screen selected".into()))?;
        let rect = sources
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, r)| r)
            .ok_or_else(|| Error::Msg("Selected screen is no longer available".into()))?;
        (rect.x, rect.y, rect.width, rect.height)
    };
    let left = x.max(bounds.x);
    let top = y.max(bounds.y);
    let right =
        (i64::from(x) + i64::from(width)).min(i64::from(bounds.x) + i64::from(bounds.width)) as i32;
    let bottom = (i64::from(y) + i64::from(height))
        .min(i64::from(bounds.y) + i64::from(bounds.height)) as i32;
    if right <= left || bottom <= top {
        return Err(Error::Msg(
            "Selected target is outside the captured desktop".into(),
        ));
    }
    pointer.release();
    // Preserve the contact captured at pinch-down; framing still uses the full target.
    let contact = value["contactU"]
        .as_f64()
        .zip(value["contactV"].as_f64())
        .filter(|(u, v)| {
            u.is_finite() && v.is_finite() && (0.0..=1.0).contains(u) && (0.0..=1.0).contains(v)
        });
    let (cursor_x, cursor_y) = contact
        .map(|(u, v)| {
            (
                (bounds.x as f64 + u * bounds.width as f64).round() as i32,
                (bounds.y as f64 + v * bounds.height as f64).round() as i32,
            )
        })
        .unwrap_or((left + (right - left) / 2, top + (bottom - top) / 2));
    pointer.move_pointer(
        cursor_x.clamp(left, right - 1),
        cursor_y.clamp(top, bottom - 1),
        false,
    )?;
    Ok([
        (left - bounds.x) as f32 / bounds.width as f32,
        (top - bounds.y) as f32 / bounds.height as f32,
        (right - bounds.x) as f32 / bounds.width as f32,
        (bottom - bounds.y) as f32 / bounds.height as f32,
    ])
}

type DisplayWorker = (
    SocketAddr,
    u32,
    thread::JoinHandle<(MonitorPool, Result<()>)>,
);

fn run(
    initial: &[MonitorRect],
    opts: &ServeOpts,
    mut pool: Option<&mut MonitorPool>,
    mut sources: Vec<(String, MonitorRect)>,
) -> Result<()> {
    let mut monitors = initial.to_vec();
    if let Err(error) = crate::platform::x11::notify_layout_clients() {
        tracing::warn!(%error,"Could not synchronize desktop layout consumers")
    }

    let mut pointer = InputDevices::open()?;
    crate::platform::x11::typing::start();
    let socket = UdpSocket::bind(opts.listen)?;
    socket.set_nonblocking(true)?;
    let session_id = monotonic_ns();
    let mut hellos = source_hellos(&monitors, &sources);
    let shared = Arc::new(Shared {
        stop: AtomicBool::new(false),
        repairs: Mutex::new(std::collections::VecDeque::new()),
        repair_window: Mutex::new((Instant::now(), 0)),
        repaired: AtomicU64::new(0),
        screens: RwLock::new(if sources.is_empty() {
            monitors.clone()
        } else {
            sources.iter().map(|(_, r)| *r).collect()
        }),
        client: RwLock::new(None),
        session_id,
        view_until_ns: AtomicU64::new(0),
        encoder_quality: AtomicU32::new(0),
        encoder_bitrate: AtomicU32::new(opts.bitrate_bps),
        encoder_generation: AtomicU64::new(0),
        encoded_frames: AtomicU64::new(0),
        view_samples: AtomicU64::new(0),
        view_capture_ns: AtomicU64::new(0),
        view_encode_ns: AtomicU64::new(0),
        keys: std::array::from_fn(|_| AtomicBool::new(false)),
        captured: AtomicU64::new(0),
        skipped: AtomicU64::new(0),
        bytes: AtomicU64::new(0),
        cursor: Mutex::new(CursorSprite {
            root_x: 0,
            root_y: 0,
            hotspot_x: 0,
            hotspot_y: 0,
            width: 0,
            height: 0,
            pixels: Vec::new(),
        }),
        cursor_generation: AtomicU64::new(0),
        rendering: Mutex::new((None, true)),
        cursor_map: Mutex::new((Vec::new(), [0.001, 0.001], Instant::now())),
        capture_fd: std::array::from_fn(|_| AtomicI32::new(-1)),
        stream_active: std::array::from_fn(|_| AtomicBool::new(true)),
        stream_ssrc: std::array::from_fn(|_| AtomicU32::new(0)),
        stream_progress: std::array::from_fn(|_| AtomicU64::new(0)),
    });
    let _detail_worker = detail::start(
        Arc::clone(&shared),
        opts.listen,
        opts.encoder.clone(),
        opts.fps,
        opts.bitrate_bps,
    );
    let _window_worker = opts.listen.port().checked_add(2).and_then(|port| {
        window::start(
            Arc::clone(&shared),
            SocketAddr::new(opts.listen.ip(), port),
            opts.encoder.clone(),
            opts.fps,
            opts.bitrate_bps,
        )
    });
    let stop_flag = Arc::clone(&shared);
    let _ = ctrlc::set_handler(move || {
        stop_flag.stop.store(true, Ordering::Relaxed);
    });

    let mut threads = launch_streams(&shared, &monitors, &hellos, opts);
    tracing::info!(listen = %opts.listen, "waiting for a client hello");
    let cursor_shared = Arc::clone(&shared);
    let cursor_thread = thread::spawn(move || {
        cursor_loop(cursor_shared);
    });
    let (topology_tx, topology_rx) = std::sync::mpsc::sync_channel(1);
    let topology_shared = Arc::clone(&shared);
    let initial_sources = sources.clone();
    let watch_topology = opts.existing;
    let topology_socket = socket.try_clone()?;
    let topology_thread = thread::spawn(move || {
        if !watch_topology {
            return;
        }
        let observer = MonitorPool::observer();
        let mut native = match crate::platform::x11::monitor_watch::MonitorWatch::open(
            &initial_sources,
        ) {
            Ok(watch) => {
                tracing::info!("Watching display changes through native RandR events");
                Some(watch)
            }
            Err(error) => {
                tracing::warn!(%error,"Native display watch unavailable; using cached-state CLI fallback");
                None
            }
        };
        let mut published = initial_sources;
        let mut previous = published.clone();
        let mut announced = false;
        while !topology_shared.stop.load(Ordering::Relaxed) {
            let observed = Instant::now();
            let next = match native.as_mut() {
                Some(watch) => watch.sources(),
                None => observer.sources(),
            };
            if let Ok(next) = next {
                if next != published && !announced {
                    send_reload(&topology_shared, &topology_socket, true);
                    announced = true;
                }
                if next == published && announced {
                    send_reload(&topology_shared, &topology_socket, false);
                    announced = false;
                }
                if next == previous
                    && next != published
                    && topology_tx.try_send((observed, next.clone())).is_ok()
                {
                    published = next.clone();
                    announced = false;
                }
                previous = next;
            }
            for _ in 0..if native.is_some() { 2 } else { 8 } {
                if topology_shared.stop.load(Ordering::Relaxed) {
                    return;
                }
                thread::sleep(Duration::from_millis(75));
            }
        }
    });
    let mut topology_epoch = Instant::now();
    let mut window_action_sequence = std::collections::HashMap::<(SocketAddr, u32), u32>::new();
    let mut last_cursor_map: Option<(SocketAddr, u64)> = None;
    let mut last_gaze_focus: Option<(SocketAddr, u32)> = None;
    let mut replies: std::collections::VecDeque<(SocketAddr, u32, Vec<u8>)> =
        std::collections::VecDeque::new();
    let mut resources = resources::Sampler::new();
    let mut last_hello = Instant::now() - Duration::from_secs(2);
    let mut shell_revision = crate::platform::x11::windows::system_revision();
    let mut last_stats = Instant::now();
    let mut last_progress = [0u64; 6];
    let started = Instant::now();
    let mut pending_display: Option<DisplayWorker> = None;
    let mut scratch = [0u8; 65535];
    while !shared.stop.load(Ordering::Relaxed) {
        if opts
            .duration
            .is_some_and(|limit| started.elapsed() >= limit)
        {
            break;
        }
        loop {
            match socket.recv_from(&mut scratch) {
                Ok((size, from)) => {
                    let packet = floatdesk_protocol::decode_packet(&scratch[..size]);
                    if let Ok(ControlPacket::Workspace { json }) = &packet {
                        if let Ok(value) = serde_json::from_str::<serde_json::Value>(json) {
                            if value["type"] == "renderState" {
                                if shared
                                    .client
                                    .read()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .as_ref()
                                    .is_some_and(|c| c.control == from)
                                {
                                    if let Some(active) = value["active"].as_bool() {
                                        *shared
                                            .rendering
                                            .lock()
                                            .unwrap_or_else(|e| e.into_inner()) =
                                            (Some(from), active);
                                    }
                                }
                                continue;
                            }
                            if value["type"] == "cursorMap" {
                                let sequence = value["sequence"].as_u64().unwrap_or(0);
                                if last_cursor_map
                                    .is_some_and(|(peer, old)| peer == from && sequence < old)
                                {
                                    continue;
                                }
                                if shared
                                    .client
                                    .read()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .as_ref()
                                    .is_some_and(|c| c.control == from)
                                {
                                    if let (Ok(surfaces), Ok(scale)) = (
                                        serde_json::from_value::<Vec<cursor_map::Surface>>(
                                            value["surfaces"].clone(),
                                        ),
                                        serde_json::from_value::<[f64; 2]>(value["scale"].clone()),
                                    ) {
                                        if surfaces.iter().all(|s| s.valid())
                                            && scale.iter().all(|s| s.is_finite() && *s > 0.0)
                                        {
                                            *shared
                                                .cursor_map
                                                .lock()
                                                .unwrap_or_else(|e| e.into_inner()) =
                                                (surfaces, scale, Instant::now());
                                            last_cursor_map = Some((from, sequence));
                                        }
                                    }
                                }
                                continue;
                            }
                            if value["type"] == "windowAction" {
                                let allowed = shared
                                    .client
                                    .read()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .as_ref()
                                    .is_some_and(|c| c.control == from);
                                if !allowed {
                                    continue;
                                }
                                if let Some(id) = value["requestId"]
                                    .as_u64()
                                    .and_then(|v| u32::try_from(v).ok())
                                {
                                    if let Some((_, _, reply)) = replies
                                        .iter()
                                        .find(|(peer, key, _)| *peer == from && *key == id)
                                    {
                                        let _ = socket.send_to(reply, from);
                                        continue;
                                    }
                                    let window = value["windowId"]
                                        .as_u64()
                                        .and_then(|w| u32::try_from(w).ok())
                                        .unwrap_or(0);
                                    window_action_sequence.retain(|(peer, _), _| *peer == from);
                                    let stale = window_action_sequence
                                        .get(&(from, window))
                                        .is_some_and(|old| id < *old);
                                    let result = if stale {
                                        Err(Error::Msg("Superseded window action".into()))
                                    } else {
                                        window_action_sequence.insert((from, window), id);
                                        crate::platform::x11::windows::window_action(&value)
                                    };
                                    let reply=ControlPacket::Workspace{json:serde_json::json!({"type":"windowActionResult","requestId":id,"ok":result.is_ok(),"message":result.err().map(|e|e.to_string()).unwrap_or_default()}).to_string()}.encode();
                                    let _ = socket.send_to(&reply, from);
                                    replies.push_back((from, id, reply));
                                    if replies.len() > 32 {
                                        replies.pop_front();
                                    }
                                }
                                continue;
                            }
                            if value["type"] == "encoderSettings" {
                                if shared
                                    .client
                                    .read()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .as_ref()
                                    .is_some_and(|c| c.control == from)
                                {
                                    let quality =
                                        value["quality"].as_u64().unwrap_or(0).min(2) as u32;
                                    let mbps = value["bitrateMbps"]
                                        .as_f64()
                                        .filter(|v| v.is_finite() && *v > 0.0);
                                    let rate = mbps
                                        .map(|v| (v.clamp(1.0, 200.0) * 1_000_000.0) as u32)
                                        .unwrap_or(opts.bitrate_bps);
                                    let changed =
                                        shared.encoder_quality.swap(quality, Ordering::Relaxed)
                                            != quality;
                                    let changed =
                                        (shared.encoder_bitrate.swap(rate, Ordering::Relaxed)
                                            != rate)
                                            || changed;
                                    if changed {
                                        shared.encoder_generation.fetch_add(1, Ordering::Relaxed);
                                        for key in &shared.keys {
                                            key.store(true, Ordering::Relaxed);
                                        }
                                    }
                                    let _=socket.send_to(&ControlPacket::Workspace{json:serde_json::json!({"type":"encoderAck","quality":quality,"bitrate":rate}).to_string()}.encode(),from);
                                }
                                continue;
                            }
                            if value["type"] == "viewStop" || value["type"] == "viewIdle" {
                                if shared
                                    .client
                                    .read()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .as_ref()
                                    .is_some_and(|c| c.control == from)
                                {
                                    shared.view_until_ns.store(
                                        if value["type"] == "viewIdle" {
                                            monotonic_ns() + 500_000_000
                                        } else {
                                            0
                                        },
                                        Ordering::Relaxed,
                                    );
                                    shared.keys[0].store(true, Ordering::Relaxed);
                                }
                                continue;
                            }
                            if value["type"] == "typingState" {
                                if shared
                                    .client
                                    .read()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .as_ref()
                                    .is_some_and(|c| c.control == from)
                                {
                                    let age = crate::platform::x11::typing::age_ms().min(60_000);
                                    let reply=ControlPacket::Workspace{json:serde_json::json!({"type":"typingState","windowId":crate::platform::x11::typing::window_id(),"ageMs":age,"available":crate::platform::x11::typing::start()}).to_string()}.encode();
                                    let _ = socket.send_to(&reply, from);
                                }
                                continue;
                            }
                            if value["type"] == "gazeFocus" {
                                let allowed = shared
                                    .client
                                    .read()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .as_ref()
                                    .is_some_and(|c| c.control == from);
                                let id = value["requestId"]
                                    .as_u64()
                                    .and_then(|v| u32::try_from(v).ok())
                                    .filter(|v| *v > 0);
                                if !allowed || id.is_none() {
                                    continue;
                                }
                                let id = id.unwrap();
                                if let Some((_, _, reply)) = replies
                                    .iter()
                                    .find(|(peer, key, _)| *peer == from && *key == id)
                                {
                                    let _ = socket.send_to(reply, from);
                                    continue;
                                }
                                let stale = last_gaze_focus
                                    .is_some_and(|(peer, key)| peer == from && id < key);
                                if !stale {
                                    last_gaze_focus = Some((from, id));
                                }
                                let quiet =
                                    value["quietMs"].as_u64().unwrap_or(1000).clamp(0, 10_000);
                                let result = if crate::platform::x11::typing::age_ms() < quiet {
                                    Err(Error::Msg("Typing: gaze focus paused".into()))
                                } else if stale {
                                    Err(Error::Msg("Superseded gaze focus".into()))
                                } else {
                                    value["windowId"]
                                        .as_u64()
                                        .and_then(|v| u32::try_from(v).ok())
                                        .ok_or_else(|| Error::Msg("Missing gaze window".into()))
                                        .and_then(
                                            crate::platform::x11::windows::focus_without_raise,
                                        )
                                };
                                let reply=ControlPacket::Workspace{json:serde_json::json!({"type":"gazeFocusResult","requestId":id,"ok":result.is_ok(),"message":result.err().map(|e|e.to_string()).unwrap_or_default()}).to_string()}.encode();
                                let _ = socket.send_to(&reply, from);
                                replies.push_back((from, id, reply));
                                if replies.len() > 32 {
                                    replies.pop_front();
                                }
                                continue;
                            }
                            if value["type"] == "focus" {
                                let allowed = shared
                                    .client
                                    .read()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .as_ref()
                                    .is_some_and(|c| c.control == from);
                                let id = value["requestId"]
                                    .as_u64()
                                    .and_then(|v| u32::try_from(v).ok())
                                    .filter(|v| *v > 0);
                                if !allowed || id.is_none() {
                                    continue;
                                }
                                let id = id.unwrap();
                                if let Some((_, _, reply)) = replies
                                    .iter()
                                    .find(|(peer, key, _)| *peer == from && *key == id)
                                {
                                    let _ = socket.send_to(reply, from);
                                    continue;
                                }
                                let result = focus_desktop_target(&value, &sources, &mut pointer);
                                let reply = match result {
                                    Ok(rect) => {
                                        serde_json::json!({"type":"focusResult","requestId":id,"ok":true,"rect":rect})
                                    }
                                    Err(error) => {
                                        serde_json::json!({"type":"focusResult","requestId":id,"ok":false,"message":error.to_string()})
                                    }
                                };
                                let reply = ControlPacket::Workspace {
                                    json: reply.to_string(),
                                }
                                .encode();
                                let _ = socket.send_to(&reply, from);
                                replies.push_back((from, id, reply));
                                if replies.len() > 32 {
                                    replies.pop_front();
                                }
                                continue;
                            }
                        }
                    }
                    let command = match &packet {
                        Ok(ControlPacket::MonitorCommand {
                            request_id,
                            action,
                            index,
                            width,
                            height,
                        }) => Some((*request_id, *action, usize::from(*index), *width, *height)),
                        Ok(ControlPacket::Workspace { json }) => {
                            serde_json::from_str::<serde_json::Value>(json)
                                .ok()
                                .and_then(|v| {
                                    if v["type"] != "command" {
                                        return None;
                                    }
                                    let id = u32::try_from(v["requestId"].as_u64()?).ok()?;
                                    let action = u8::try_from(v["action"].as_u64()?).ok()?;
                                    if action > 7 {
                                        return None;
                                    }
                                    let index = if action == 0 || action == 1 || action >= 5 {
                                        0
                                    } else {
                                        sources.iter().position(|(n, _)| {
                                            Some(n.as_str()) == v["name"].as_str()
                                        })?
                                    };
                                    let width = if action >= 5 {
                                        0
                                    } else if action == 4 {
                                        i16::try_from(v["width"].as_i64()?).ok()? as u16
                                    } else {
                                        u16::try_from(v["width"].as_u64()?).ok()?
                                    };
                                    let height = if action >= 5 {
                                        0
                                    } else if action == 4 {
                                        i16::try_from(v["height"].as_i64()?).ok()? as u16
                                    } else {
                                        u16::try_from(v["height"].as_u64()?).ok()?
                                    };
                                    Some((id, action, index, width, height))
                                })
                        }
                        _ => None,
                    };
                    if let Some((request_id, action, index, width, height)) = command {
                        let allowed = shared
                            .client
                            .read()
                            .unwrap_or_else(|e| e.into_inner())
                            .as_ref()
                            .is_some_and(|c| c.control == from);
                        if !allowed {
                            continue;
                        }
                        if let Some((_, _, reply)) = replies
                            .iter()
                            .find(|(peer, id, _)| *peer == from && *id == request_id)
                        {
                            let _ = socket.send_to(reply, from);
                            continue;
                        }
                        if pending_display.is_some() {
                            continue;
                        }
                        if action != 0 {
                            if let Some(owner) = pool.as_deref_mut() {
                                let mut worker_pool = std::mem::replace(owner, MonitorPool::new());
                                let source_snapshot = sources.clone();
                                pointer.release();
                                send_reload(&shared, &socket, true);
                                let arrangement = if action == 5 {
                                    match &packet {
                                        Ok(ControlPacket::Workspace { json }) => {
                                            serde_json::from_str::<serde_json::Value>(json)
                                                .ok()
                                                .map(|v| v["positions"].clone())
                                        }
                                        _ => None,
                                    }
                                } else {
                                    None
                                };
                                let worker = thread::spawn(move || {
                                    let result = if action == 5 {
                                        worker_pool
                                            .apply_arrangement(&arrangement.unwrap_or_default())
                                    } else {
                                        worker_pool.apply(
                                            action,
                                            index,
                                            width,
                                            height,
                                            &source_snapshot,
                                        )
                                    };
                                    (worker_pool, result)
                                });
                                pending_display = Some((from, request_id, worker));
                                continue;
                            }
                        }
                        let mut ok = true;
                        let mut message = String::from("Displays ready");
                        if let Some(p) = pool.as_deref() {
                            match p.sources() {
                                Ok(next) => {
                                    sources = next;
                                    update_streams(
                                        &shared,
                                        &socket,
                                        opts,
                                        &sources,
                                        &mut monitors,
                                        &mut hellos,
                                        &mut threads,
                                    )?;
                                }
                                Err(error) => {
                                    ok = false;
                                    message = error.to_string();
                                }
                            }
                        } else if action != 0 {
                            ok = false;
                            message = "Display management requires --existing".into();
                        }
                        let reply =
                            display_status(pool.as_deref(), &sources, request_id, ok, message)
                                .encode();
                        let _ = socket.send_to(&reply, from);
                        publish_workspace(
                            pool.as_deref(),
                            &socket,
                            from,
                            request_id,
                            false,
                            opts.fps,
                        );
                        replies.push_back((from, request_id, reply));
                        if replies.len() > 32 {
                            replies.pop_front();
                        }
                    } else {
                        handle_datagram(
                            &shared,
                            &socket,
                            &hellos,
                            &scratch[..size],
                            from,
                            &monitors,
                            &mut pointer,
                        );
                    }
                }
                Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(err) => return Err(err.into()),
            }
        }
        if pending_display
            .as_ref()
            .is_some_and(|(_, _, w)| w.is_finished())
        {
            let (from, id, worker) = pending_display.take().unwrap();
            let (finished, result) = worker
                .join()
                .map_err(|_| Error::Msg("Display worker panicked".into()))?;
            if let Some(owner) = pool.as_deref_mut() {
                *owner = finished;
            }
            let mut ok = result.is_ok();
            let mut message = result
                .err()
                .map_or_else(|| "Displays ready".into(), |e| e.to_string());
            if let Some(p) = pool.as_deref() {
                match p.sources() {
                    Ok(next) => sources = next,
                    Err(e) => {
                        ok = false;
                        message = e.to_string();
                    }
                }
            }
            update_streams(
                &shared,
                &socket,
                opts,
                &sources,
                &mut monitors,
                &mut hellos,
                &mut threads,
            )?;
            topology_epoch = Instant::now();
            let reply = display_status(pool.as_deref(), &sources, id, ok, message).encode();
            let _ = socket.send_to(&reply, from);
            publish_workspace(pool.as_deref(), &socket, from, id, false, opts.fps);
            replies.push_back((from, id, reply));
            if replies.len() > 32 {
                replies.pop_front();
            }
        }
        if let Ok((observed, next)) = topology_rx.try_recv() {
            if pending_display.is_none() && observed > topology_epoch && next != sources {
                tracing::info!("desktop arrangement changed; reconfiguring capture");
                pointer.release();
                send_reload(&shared, &socket, true);
                let previous_bounds = monitors.clone();
                sources = next;
                if let Err(error) = crate::platform::x11::notify_layout_clients() {
                    tracing::warn!(%error,"Could not notify external layout change")
                }
                *shared.screens.write().unwrap_or_else(|e| e.into_inner()) =
                    sources.iter().map(|(_, r)| *r).collect();
                monitors = vec![desktop_rect(&sources)?];
                if monitors != previous_bounds {
                    stop_streams(&shared, &mut threads);
                    hellos = source_hellos(&monitors, &sources);
                    for r in &monitors {
                        shared.stream_active[r.index as usize].store(true, Ordering::Relaxed);
                    }
                    threads = launch_streams(&shared, &monitors, &hellos, opts);
                    send_hello(&shared, &socket, &hellos);
                } else {
                    send_reload(&shared, &socket, false);
                }
                if let Some(client) = shared
                    .client
                    .read()
                    .unwrap_or_else(|e| e.into_inner())
                    .as_ref()
                {
                    let status = display_status(
                        pool.as_deref(),
                        &sources,
                        0,
                        true,
                        "Desktop arrangement updated".into(),
                    );
                    let _ = socket.send_to(&status.encode(), client.control);
                }
            } else if pending_display.is_none() {
                send_reload(&shared, &socket, false);
            }
        }
        pointer.expire();
        let shell_now = crate::platform::x11::windows::system_revision();
        if pending_display.is_none() && shell_now != shell_revision {
            if let Some(client) = shared
                .client
                .read()
                .unwrap_or_else(|e| e.into_inner())
                .as_ref()
            {
                publish_workspace(pool.as_deref(), &socket, client.control, 0, false, opts.fps);
            }
            shell_revision = shell_now;
        }
        if last_hello.elapsed() >= Duration::from_secs(1) {
            send_hello(&shared, &socket, &hellos);
            let stats = resources.sample(&shared);
            if let Some(client) = shared
                .client
                .read()
                .unwrap_or_else(|e| e.into_inner())
                .as_ref()
            {
                let _ = socket.send_to(
                    &ControlPacket::Workspace {
                        json: stats.to_string(),
                    }
                    .encode(),
                    client.control,
                );
            }
            if !opts.listen.ip().is_loopback() {
                send_beacon(opts.listen.port());
            }
            if pending_display.is_none() {
                if let Some(client) = shared
                    .client
                    .read()
                    .unwrap_or_else(|e| e.into_inner())
                    .as_ref()
                {
                    publish_workspace(pool.as_deref(), &socket, client.control, 0, false, opts.fps);
                }
            } else {
                send_reload(&shared, &socket, true);
            }
            last_hello = Instant::now();
        }
        if last_stats.elapsed() >= Duration::from_secs(2) {
            let frames = shared.captured.load(Ordering::Relaxed);
            let skipped = shared.skipped.load(Ordering::Relaxed);
            let bytes = shared.bytes.load(Ordering::Relaxed);
            let client_waiting = shared
                .client
                .read()
                .unwrap_or_else(|e| e.into_inner())
                .is_some();
            for (index, previous) in last_progress.iter_mut().enumerate() {
                let progress = shared.stream_progress[index].load(Ordering::Relaxed);
                if client_waiting
                    && shared.stream_active[index].load(Ordering::Relaxed)
                    && progress == *previous
                {
                    let fd = shared.capture_fd[index].load(Ordering::Relaxed);
                    if fd >= 0 {
                        tracing::error!(
                            monitor = index,
                            "capture made no progress, resetting connection"
                        );
                        unsafe {
                            libc::shutdown(fd, libc::SHUT_RDWR);
                        }
                    }
                }
                *previous = progress;
            }
            tracing::info!(
                frames,
                skipped,
                bytes,
                repairs = shared.repaired.load(Ordering::Relaxed),
                "session"
            );
            last_stats = Instant::now();
        }
        thread::sleep(Duration::from_millis(8));
    }
    if let Some((_, _, worker)) = pending_display.take() {
        if let Ok((finished, _)) = worker.join() {
            if let Some(owner) = pool {
                *owner = finished;
            }
        }
    }
    shared.stop.store(true, Ordering::Relaxed);
    pointer.release();
    stop_streams(&shared, &mut threads);
    let _ = cursor_thread.join();
    let _ = topology_thread.join();
    Ok(())
}

/// Bound UDP bursts while allowing a short packet train; do not delay control input.
#[cfg(test)]
mod pointer_tests {
    use super::*;
    #[test]
    fn pointer_requires_active_peer_and_fresh_sequence_and_maps_monitor_bounds() {
        let peer: SocketAddr = "127.0.0.1:4000".parse().unwrap();
        let mut client = Some(ClientTarget {
            control: peer,
            video_port: 48000,
            generation: 1,
            pointer_sequence: None,
            click_sequence: None,
            touchpad_sequence: None,
        });
        let monitors = [MonitorRect {
            index: 2,
            x: 2560,
            y: 100,
            width: 1920,
            height: 1080,
        }];
        assert_eq!(
            pointer_position(&mut None, &monitors, peer, 2, 0.0, 0.0, 1),
            None
        );
        assert_eq!(
            pointer_position(
                &mut client,
                &monitors,
                "127.0.0.1:4001".parse().unwrap(),
                2,
                0.0,
                0.0,
                1
            ),
            None
        );
        assert_eq!(
            pointer_position(&mut client, &monitors, peer, 0, 0.0, 0.0, 1),
            None
        );
        assert_eq!(
            pointer_position(&mut client, &monitors, peer, 2, f32::NAN, 0.0, 1),
            None
        );
        assert_eq!(
            pointer_position(&mut client, &monitors, peer, 2, 0.0, 0.0, u32::MAX),
            Some((2560, 100))
        );
        assert_eq!(
            pointer_position(&mut client, &monitors, peer, 2, 1.0, 1.0, 0),
            Some((4479, 1179))
        );
        assert_eq!(
            pointer_position(&mut client, &monitors, peer, 2, 0.5, 0.5, 0),
            None
        );
        assert_eq!(
            pointer_position(&mut client, &monitors, peer, 2, 0.5, 0.5, u32::MAX),
            None
        );
    }
    #[test]
    fn atomic_click_retries_are_deduplicated_independently_of_motion() {
        let peer: SocketAddr = "127.0.0.1:4000".parse().unwrap();
        let mut client = Some(ClientTarget {
            control: peer,
            video_port: 48000,
            generation: 1,
            pointer_sequence: Some(900),
            click_sequence: None,
            touchpad_sequence: None,
        });
        let monitors = [MonitorRect {
            index: 0,
            x: 100,
            y: 200,
            width: 101,
            height: 51,
        }];
        assert_eq!(
            click_position(&mut client, &monitors, peer, 0, 0.5, 0.5, 1),
            Some((150, 225))
        );
        assert_eq!(
            click_position(&mut client, &monitors, peer, 0, 0.5, 0.5, 1),
            None
        );
        assert_eq!(
            click_position(&mut client, &monitors, peer, 0, 0.5, 0.5, 1),
            None
        );
        assert_eq!(
            click_position(&mut client, &monitors, peer, 0, 0.5, 0.5, 2),
            Some((150, 225))
        );
    }
}

#[cfg(test)]
mod video_pacing_tests {
    use super::*;
    #[test]
    fn cursor_is_offset_into_its_atlas_tile_and_clipped_at_the_tile_edge() {
        let mut frame = crate::core::CapturedFrame {
            width: 12,
            height: 8,
            stride: 48,
            bgra: vec![0; 384],
            monotonic_ns: 0,
            damage_generation: 0,
        };
        let rect = MonitorRect {
            index: 0,
            x: 0,
            y: 0,
            width: 8,
            height: 8,
        };
        let cursor = CursorSprite {
            root_x: 6,
            root_y: 4,
            hotspot_x: 0,
            hotspot_y: 0,
            width: 4,
            height: 4,
            pixels: [0, 255, 0, 255].repeat(16),
        };
        paint_cursor_scaled_at(&mut frame, rect, &cursor, (4, 2, 4, 4));
        for y in 0..8 {
            for x in 0..12 {
                assert_eq!(
                    frame.bgra[y * 48 + x * 4 + 1],
                    if x == 7 && (4..6).contains(&y) {
                        255
                    } else {
                        0
                    }
                );
            }
        }
    }
    #[test]
    fn scaled_cursor_tracks_full_desktop_coordinates() {
        let mut frame = crate::core::CapturedFrame {
            width: 100,
            height: 50,
            stride: 400,
            bgra: vec![0; 20000],
            monotonic_ns: 0,
            damage_generation: 0,
        };
        let rect = MonitorRect {
            index: 0,
            x: 200,
            y: 300,
            width: 200,
            height: 100,
        };
        let cursor = CursorSprite {
            root_x: 300,
            root_y: 350,
            hotspot_x: 0,
            hotspot_y: 0,
            width: 2,
            height: 2,
            pixels: vec![
                0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255,
            ],
        };
        paint_cursor_scaled(&mut frame, rect, &cursor);
        assert_eq!(
            frame.bgra[25 * 400 + 50 * 4..25 * 400 + 50 * 4 + 3],
            [0, 255, 0]
        );
    }
    #[test]
    fn packet_train_is_bounded_without_saving_idle_credit() {
        let mut pacer = VideoPacer::new(12_000_000);
        let now = Instant::now();
        pacer.next = now;
        assert_eq!(pacer.reserve(now, 9600), Duration::ZERO);
        assert_eq!(pacer.reserve(now, 1200), Duration::ZERO);
        assert!(pacer.reserve(now, 1200) >= Duration::from_micros(199));
        assert_eq!(
            pacer.reserve(now + Duration::from_secs(1), 1200),
            Duration::ZERO
        );
        assert!(pacer.next < now + Duration::from_millis(1001));
    }
}
