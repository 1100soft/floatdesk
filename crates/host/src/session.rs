//! Session loop. Each monitor has its own capture thread, encoder, and RTP
//! stream. The cursor is polled independently and composited into captured frames.
//! Nothing is encoded until a client hello arrives.

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
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering};
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

struct Shared {
    stop: AtomicBool,
    client: RwLock<Option<ClientTarget>>,
    session_id: u64,
    keys: [AtomicBool; 6],
    captured: AtomicU64,
    skipped: AtomicU64,
    bytes: AtomicU64,
    cursor: Mutex<CursorSprite>,
    cursor_generation: AtomicU64,
    /// X11 socket of the capture thread. -1 when capture is not connected.
    capture_fd: [AtomicI32; 6],
    stream_active: [AtomicBool; 6],
    stream_progress: [AtomicU64; 6],
}

pub fn serve(opts: ServeOpts) -> Result<()> {
    let _epoch = epoch_ns();
    let mut display = X11Display::open()?;
    let mut pool = MonitorPool::new();
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

fn launch_streams(
    shared: &Arc<Shared>,
    monitors: &[MonitorRect],
    hellos: &[MonitorHello],
    opts: &ServeOpts,
) -> Vec<thread::JoinHandle<()>> {
    let loads: Vec<_> = monitors
        .iter()
        .enumerate()
        .map(|(i, r)| MonitorLoad {
            index: r.index,
            focused: i == 0,
            damaged: false,
            visible: true,
        })
        .collect();
    let bitrates = allocate_bitrate(opts.bitrate_bps, &loads);
    let mut threads = Vec::new();
    for (monitor, (hello, bitrate)) in monitors.iter().zip(hellos.iter().zip(bitrates.iter())) {
        let shared = Arc::clone(&shared);
        let rect = *monitor;
        let ssrc = hello.ssrc;
        let encoder_name = opts.encoder.clone();
        let fps = opts.fps.max(1);
        let bitrate = *bitrate;
        threads.push(thread::spawn(move || {
            // Preserve RTP sequence numbers across capture/encoder reconnections.
            let mut packetizer = RtpH264Packetizer::new(ssrc);
            while !shared.stop.load(Ordering::Relaxed)
                && shared.stream_active[rect.index as usize].load(Ordering::Relaxed)
            {
                if let Err(err) = monitor_thread(
                    Arc::clone(&shared),
                    rect,
                    &mut packetizer,
                    encoder_name.clone(),
                    fps,
                    bitrate,
                ) {
                    if shared.stream_active[rect.index as usize].load(Ordering::Relaxed)
                        && !shared.stop.load(Ordering::Relaxed)
                    {
                        tracing::error!(
                            monitor = rect.index,
                            "capture stopped, reconnecting: {err}"
                        );
                    }
                }
                if shared.stop.load(Ordering::Relaxed) {
                    break;
                }
                thread::sleep(Duration::from_millis(200));
            }
        }));
    }

    threads
}
fn stop_streams(shared: &Shared, threads: &mut Vec<thread::JoinHandle<()>>) {
    for active in &shared.stream_active {
        active.store(false, Ordering::Relaxed);
    }
    for fd in &shared.capture_fd {
        let fd = fd.load(Ordering::Relaxed);
        if fd >= 0 {
            unsafe {
                libc::shutdown(fd, libc::SHUT_RDWR);
            }
        }
    }
    for thread in threads.drain(..) {
        let _ = thread.join();
    }
}

fn run(
    initial: &[MonitorRect],
    opts: &ServeOpts,
    mut pool: Option<&mut MonitorPool>,
    mut sources: Vec<(String, MonitorRect)>,
) -> Result<()> {
    let mut monitors = initial.to_vec();
    let mut pointer = InputDevices::open()?;
    let socket = UdpSocket::bind(opts.listen)?;
    socket.set_nonblocking(true)?;
    let session_id = monotonic_ns();
    let mut hellos: Vec<MonitorHello> = monitors
        .iter()
        .map(|monitor| MonitorHello {
            index: monitor.index,
            width: stream_size(*monitor).0,
            height: stream_size(*monitor).1,
            port: 0,
            ssrc: ssrc_for(monitor.index),
            name: if opts.existing {
                sources
                    .iter()
                    .find(|(_, r)| r.index == monitor.index)
                    .map(|(n, _)| n.clone())
                    .unwrap_or_else(|| "existing-0".into())
            } else {
                monitor.name()
            },
        })
        .collect();
    let shared = Arc::new(Shared {
        stop: AtomicBool::new(false),
        client: RwLock::new(None),
        session_id,
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
        capture_fd: std::array::from_fn(|_| AtomicI32::new(-1)),
        stream_active: std::array::from_fn(|_| AtomicBool::new(true)),
        stream_progress: std::array::from_fn(|_| AtomicU64::new(0)),
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
    let mut replies: std::collections::VecDeque<(SocketAddr, u32, Vec<u8>)> =
        std::collections::VecDeque::new();
    let mut last_hello = Instant::now() - Duration::from_secs(2);
    let mut last_stats = Instant::now();
    let mut last_progress = [0u64; 6];
    let started = Instant::now();
    let mut scratch = [0u8; 2048];
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
                    if let Ok(ControlPacket::MonitorCommand {
                        request_id,
                        action,
                        index,
                        width,
                        height,
                    }) = floatdesk_protocol::decode_packet(&scratch[..size])
                    {
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
                        let mut ok = true;
                        let mut message = String::from("Displays ready");
                        let refreshed = if action == 0 {
                            pool.as_deref().map(|p| p.sources())
                        } else {
                            None
                        };
                        let topology_changed =
                            matches!(&refreshed, Some(Ok(next)) if next != &sources);
                        if let Some(Err(error)) = &refreshed {
                            ok = false;
                            message = error.to_string();
                        }
                        if action != 0 || topology_changed {
                            pointer.release();
                            stop_streams(&shared, &mut threads);
                            let result = if action == 0 {
                                Ok(())
                            } else {
                                pool.as_deref_mut()
                                    .ok_or_else(|| {
                                        Error::Msg("Display management requires --existing".into())
                                    })
                                    .and_then(|p| p.apply(action, index, width, height, &sources))
                            };
                            if let Err(error) = result {
                                ok = false;
                                message = error.to_string();
                            }
                            if let Some(p) = pool.as_deref() {
                                match p.sources() {
                                    Ok(next) => {
                                        sources = next;
                                        monitors = vec![desktop_rect(&sources)?];
                                    }
                                    Err(error) => {
                                        ok = false;
                                        message = error.to_string();
                                    }
                                }
                            }
                            hellos = monitors
                                .iter()
                                .map(|r| MonitorHello {
                                    index: r.index,
                                    width: stream_size(*r).0,
                                    height: stream_size(*r).1,
                                    port: 0,
                                    ssrc: ssrc_for(r.index),
                                    name: sources
                                        .iter()
                                        .find(|(_, s)| s.index == r.index)
                                        .map(|(n, _)| n.clone())
                                        .unwrap_or_else(|| r.name()),
                                })
                                .collect();
                            for r in &monitors {
                                shared.stream_active[r.index as usize]
                                    .store(true, Ordering::Relaxed);
                            }
                            threads = launch_streams(&shared, &monitors, &hellos, opts);
                            send_hello(&shared, &socket, &hellos);
                        }
                        let available = pool.as_deref().map_or(0, |p| p.capacity());
                        let owned = pool.as_deref().map_or(0, |p| p.owned_mask(&sources));
                        let reply = ControlPacket::MonitorStatus {
                            request_id,
                            ok,
                            available,
                            owned,
                            screens: sources
                                .iter()
                                .map(|(_, r)| (r.width as u16, r.height as u16))
                                .collect(),
                            message,
                        }
                        .encode();
                        let _ = socket.send_to(&reply, from);
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
        pointer.expire();
        if last_hello.elapsed() >= Duration::from_secs(1) {
            send_hello(&shared, &socket, &hellos);
            if !opts.listen.ip().is_loopback() {
                send_beacon(opts.listen.port());
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
            tracing::info!(frames, skipped, bytes, "session");
            last_stats = Instant::now();
        }
        thread::sleep(Duration::from_millis(8));
    }
    shared.stop.store(true, Ordering::Relaxed);
    pointer.release();
    stop_streams(&shared, &mut threads);
    let _ = cursor_thread.join();
    Ok(())
}

fn send_ready(
    encoder: &mut crate::media::GstEncoder,
    packetizer: &mut RtpH264Packetizer,
    socket: &UdpSocket,
    dest: SocketAddr,
    shared: &Shared,
    timestamp: u32,
    wait_first: bool,
) -> Result<()> {
    let mut waited = !wait_first;
    loop {
        let unit = if !waited {
            waited = true;
            encoder.pull_wait(8_000_000)?
        } else {
            encoder.pull()?
        };
        let Some(unit) = unit else { break };
        for packet in packetizer.packetize(&unit.bytes, timestamp) {
            match socket.send_to(&packet, dest) {
                Ok(sent) => {
                    shared.bytes.fetch_add(sent as u64, Ordering::Relaxed);
                }
                Err(err) => tracing::debug!("video send: {err}"),
            }
        }
    }
    Ok(())
}

struct ClearCaptureFd<'a>(&'a AtomicI32);

impl Drop for ClearCaptureFd<'_> {
    fn drop(&mut self) {
        self.0.store(-1, Ordering::Relaxed);
    }
}

fn monitor_thread(
    shared: Arc<Shared>,
    rect: MonitorRect,
    packetizer: &mut RtpH264Packetizer,
    encoder_name: String,
    fps: u32,
    bitrate: u32,
) -> Result<()> {
    let mut capture = X11Capture::open(rect)?;
    shared.capture_fd[rect.index as usize].store(capture.connection_fd(), Ordering::Relaxed);
    let _clear_fd = ClearCaptureFd(&shared.capture_fd[rect.index as usize]);
    let (width, height) = stream_size(rect);
    let mut encoder = GstEncoder::open_scaled(
        &encoder_name,
        crate::core::EncoderConfig {
            width: u32::from(width),
            height: u32::from(height),
            fps,
            bitrate_bps: bitrate.max(400_000),
        },
        (rect.width, rect.height),
    )?;
    let socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)))?;
    encoder.set_bitrate(bitrate.max(400_000))?;
    let interval = Duration::from_secs_f64(1.0 / f64::from(fps));
    let mut seen_generation = 0u64;
    let mut seen_cursor = 0u64;
    while !shared.stop.load(Ordering::Relaxed)
        && shared.stream_active[rect.index as usize].load(Ordering::Relaxed)
    {
        shared.stream_progress[rect.index as usize].fetch_add(1, Ordering::Relaxed);
        let target = shared
            .client
            .read()
            .unwrap_or_else(|err| err.into_inner())
            .as_ref()
            .cloned();
        let Some(target) = target else {
            shared.skipped.fetch_add(1, Ordering::Relaxed);
            thread::sleep(Duration::from_millis(20));
            continue;
        };
        let mut force = false;
        if target.generation != seen_generation {
            force = true;
            seen_generation = target.generation;
        }
        if rect.index < 6 && shared.keys[rect.index as usize].swap(false, Ordering::Relaxed) {
            force = true;
        }
        if force {
            encoder.request_keyframe();
        }
        // The pointer is drawn into the frame, and XDamage does not fire when
        // only the cursor sprite moves. A cursor change has to schedule a frame
        // or the pointer freezes until the desktop repaints.
        let cursor_generation = shared.cursor_generation.load(Ordering::Relaxed);
        if cursor_generation != seen_cursor {
            seen_cursor = cursor_generation;
            capture.note_cursor();
        }
        let Some(mut frame) = capture.poll(interval, force)? else {
            shared.skipped.fetch_add(1, Ordering::Relaxed);
            thread::sleep(Duration::from_millis(2));
            continue;
        };
        if let Ok(cursor) = shared.cursor.lock() {
            paint_cursor(&mut frame, rect.x, rect.y, &cursor);
        }
        let timestamp = rtp_timestamp(frame.monotonic_ns, epoch_ns());
        let dest = SocketAddr::new(
            target.control.ip(),
            target.video_port + u16::from(rect.index),
        );
        // Pull before pushing. The encoder stops accepting input once its
        // output queue is full, and skipping the pull on that failure left
        // both sides waiting on each other.
        send_ready(
            &mut encoder,
            packetizer,
            &socket,
            dest,
            &shared,
            timestamp,
            false,
        )?;
        if let Err(err) = encoder.submit(&frame) {
            tracing::debug!("encoder busy, frame dropped: {err}");
            encoder.request_keyframe();
            send_ready(
                &mut encoder,
                packetizer,
                &socket,
                dest,
                &shared,
                timestamp,
                true,
            )?;
            continue;
        }
        shared.captured.fetch_add(1, Ordering::Relaxed);
        tracing::trace!(
            generation = frame.damage_generation,
            monitor = rect.index,
            "captured"
        );
        send_ready(
            &mut encoder,
            packetizer,
            &socket,
            dest,
            &shared,
            timestamp,
            true,
        )?;
    }
    Ok(())
}

fn handle_datagram(
    shared: &Shared,
    socket: &UdpSocket,
    hellos: &[MonitorHello],
    bytes: &[u8],
    from: SocketAddr,
    monitors: &[MonitorRect],
    pointer: &mut InputDevices,
) {
    let packet = match floatdesk_protocol::decode_packet(bytes) {
        Ok(packet) => packet,
        Err(err) => {
            tracing::debug!(%from, "ignored control datagram: {err}");
            return;
        }
    };
    match packet {
        ControlPacket::ClientHello { name, video_port } => {
            let mut client = shared.client.write().unwrap_or_else(|err| err.into_inner());
            let same = client
                .as_ref()
                .is_some_and(|current| current.control == from && current.video_port == video_port);
            if same {
                return;
            }
            tracing::info!(%from, %name, video_port, "client hello");
            let generation = client
                .as_ref()
                .map(|current| current.generation)
                .unwrap_or(0)
                + 1;
            pointer.release();
            *client = Some(ClientTarget {
                control: from,
                video_port,
                generation,
                pointer_sequence: None,
                click_sequence: None,
                touchpad_sequence: None,
            });
            drop(client);
            send_hello(shared, socket, hellos);
            send_layout(shared, socket, hellos);
        }
        ControlPacket::PointerMove {
            monitor_index,
            norm_x,
            norm_y,
            sequence,
            left_down,
        } => {
            let mut client = shared.client.write().unwrap_or_else(|err| err.into_inner());
            let first = client
                .as_ref()
                .is_some_and(|client| client.pointer_sequence.is_none());
            if let Some((x, y)) = pointer_position(
                &mut client,
                monitors,
                from,
                monitor_index,
                norm_x,
                norm_y,
                sequence,
            ) {
                match pointer.move_pointer(x, y, left_down) {
                    Ok(()) if first => tracing::info!("remote pointer control active"),
                    Err(error) => tracing::warn!(%error,"pointer move failed"),
                    _ => {}
                }
            }
        }
        ControlPacket::PointerClick {
            monitor_index,
            norm_x,
            norm_y,
            button,
            sequence,
        } => {
            let mut client = shared.client.write().unwrap_or_else(|err| err.into_inner());
            if let Some((x, y)) = click_position(
                &mut client,
                monitors,
                from,
                monitor_index,
                norm_x,
                norm_y,
                sequence,
            ) {
                let started = Instant::now();
                if let Err(error) = pointer.click(x, y, button) {
                    tracing::warn!(%error,"mouse click failed");
                } else if button == 3 {
                    tracing::info!(
                        sequence,
                        elapsed_us = started.elapsed().as_micros(),
                        "right click processed"
                    );
                }
            }
        }
        ControlPacket::TouchpadState {
            fingers,
            x,
            y,
            sequence,
        } => {
            let mut client = shared.client.write().unwrap_or_else(|err| err.into_inner());
            let Some(client) = client.as_mut().filter(|client| client.control == from) else {
                return;
            };
            if client
                .touchpad_sequence
                .is_some_and(|last| (sequence.wrapping_sub(last) as i32) <= 0)
            {
                return;
            }
            client.touchpad_sequence = Some(sequence);
            if let Some(pad) = &mut pointer.touchpad {
                if let Err(error) = pad.update(fingers, x, y) {
                    tracing::warn!(%error,"touchpad input failed");
                }
            }
        }
        ControlPacket::KeyframeRequest { monitor_index } => {
            if monitor_index == 0xff {
                for key in &shared.keys {
                    key.store(true, Ordering::Relaxed);
                }
            } else if (monitor_index as usize) < shared.keys.len() {
                shared.keys[monitor_index as usize].store(true, Ordering::Relaxed);
            }
        }
        ControlPacket::Goodbye => {
            if !shared
                .client
                .read()
                .unwrap_or_else(|err| err.into_inner())
                .as_ref()
                .is_some_and(|client| client.control == from)
            {
                return;
            }
            pointer.release();
            tracing::info!(%from, "client goodbye");
            *shared.client.write().unwrap_or_else(|err| err.into_inner()) = None;
        }
        _ => {}
    }
}

fn pointer_position(
    client: &mut Option<ClientTarget>,
    monitors: &[MonitorRect],
    from: SocketAddr,
    index: u8,
    x: f32,
    y: f32,
    sequence: u32,
) -> Option<(i32, i32)> {
    if !x.is_finite() || !y.is_finite() || !(0.0..=1.0).contains(&x) || !(0.0..=1.0).contains(&y) {
        return None;
    }
    let client = client.as_mut().filter(|client| client.control == from)?;
    if client
        .pointer_sequence
        .is_some_and(|last| (sequence.wrapping_sub(last) as i32) <= 0)
    {
        return None;
    }
    let rect = monitors
        .iter()
        .find(|rect| rect.index == index && rect.width > 0 && rect.height > 0)?;
    let point = (
        rect.x + (x * (rect.width - 1) as f32).round() as i32,
        rect.y + (y * (rect.height - 1) as f32).round() as i32,
    );
    client.pointer_sequence = Some(sequence);
    Some(point)
}

fn click_position(
    client: &mut Option<ClientTarget>,
    monitors: &[MonitorRect],
    from: SocketAddr,
    index: u8,
    x: f32,
    y: f32,
    sequence: u32,
) -> Option<(i32, i32)> {
    if !x.is_finite() || !y.is_finite() || !(0.0..=1.0).contains(&x) || !(0.0..=1.0).contains(&y) {
        return None;
    }
    let client = client.as_mut().filter(|client| client.control == from)?;
    if client
        .click_sequence
        .is_some_and(|last| (sequence.wrapping_sub(last) as i32) <= 0)
    {
        return None;
    }
    let rect = monitors
        .iter()
        .find(|rect| rect.index == index && rect.width > 0 && rect.height > 0)?;
    client.click_sequence = Some(sequence);
    Some((
        rect.x + (x * (rect.width - 1) as f32).round() as i32,
        rect.y + (y * (rect.height - 1) as f32).round() as i32,
    ))
}

fn send_beacon(listen_port: u16) {
    let Ok(socket) = UdpSocket::bind("0.0.0.0:0") else {
        return;
    };
    let _ = socket.set_broadcast(true);
    let packet = ControlPacket::Beacon {
        listen_port,
        name: host_name(),
    }
    .encode();
    for address in broadcast_targets() {
        let _ = socket.send_to(&packet, address);
    }
}

fn host_name() -> String {
    let mut buffer = [0u8; 64];
    let rc = unsafe { libc::gethostname(buffer.as_mut_ptr() as *mut libc::c_char, buffer.len()) };
    if rc != 0 {
        return "floatdesk".into();
    }
    let end = buffer
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(buffer.len());
    String::from_utf8_lossy(&buffer[..end]).into_owned()
}

fn broadcast_targets() -> Vec<SocketAddr> {
    let mut targets = vec![SocketAddr::from(([255, 255, 255, 255], BEACON_PORT))];
    unsafe {
        let mut interfaces: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut interfaces) != 0 {
            return targets;
        }
        let mut cursor = interfaces;
        while !cursor.is_null() {
            let interface = &*cursor;
            if !interface.ifa_addr.is_null()
                && (*interface.ifa_addr).sa_family == libc::AF_INET as libc::sa_family_t
                && !interface.ifa_netmask.is_null()
            {
                let address = &*(interface.ifa_addr as *const libc::sockaddr_in);
                let mask = &*(interface.ifa_netmask as *const libc::sockaddr_in);
                let ip = u32::from_be(address.sin_addr.s_addr);
                let netmask = u32::from_be(mask.sin_addr.s_addr);
                if ip != 0 && netmask != 0 && ip != u32::from(Ipv4Addr::LOCALHOST) {
                    let broadcast = Ipv4Addr::from(ip | !netmask);
                    targets.push(SocketAddr::from((broadcast, BEACON_PORT)));
                }
            }
            cursor = interface.ifa_next;
        }
        libc::freeifaddrs(interfaces);
    }
    targets.sort_by_key(|address| address.to_string());
    targets.dedup();
    targets
}

fn send_hello(shared: &Shared, socket: &UdpSocket, hellos: &[MonitorHello]) {
    let Some(client) = shared
        .client
        .read()
        .unwrap_or_else(|err| err.into_inner())
        .as_ref()
        .cloned()
    else {
        return;
    };
    let monitors = hellos
        .iter()
        .map(|hello| MonitorHello {
            port: client.video_port + u16::from(hello.index),
            ..hello.clone()
        })
        .collect();
    let packet = ControlPacket::HostHello {
        session_id: shared.session_id,
        monitors,
    };
    let _ = socket.send_to(&packet.encode(), client.control);
}

fn send_layout(shared: &Shared, socket: &UdpSocket, hellos: &[MonitorHello]) {
    let Some(client) = shared
        .client
        .read()
        .unwrap_or_else(|err| err.into_inner())
        .as_ref()
        .cloned()
    else {
        return;
    };
    let packet = ControlPacket::Layout {
        monitors: hellos
            .iter()
            .map(|hello| LayoutEntry {
                index: hello.index,
                x: 0,
                y: 0,
                width: hello.width,
                height: hello.height,
            })
            .collect(),
    };
    let _ = socket.send_to(&packet.encode(), client.control);
}

fn cursor_loop(shared: Arc<Shared>) {
    let mut cursor = match X11Cursor::open() {
        Ok(cursor) => cursor,
        Err(err) => {
            tracing::error!("cursor: {err}");
            return;
        }
    };
    while !shared.stop.load(Ordering::Relaxed) {
        match cursor.poll() {
            Ok(sample) => {
                let changed = if let Ok(mut slot) = shared.cursor.lock() {
                    let moved = slot.root_x != sample.root_x || slot.root_y != sample.root_y;
                    let reshaped = !sample.argb.is_empty();
                    slot.root_x = sample.root_x;
                    slot.root_y = sample.root_y;
                    if reshaped {
                        slot.hotspot_x = sample.hotspot_x;
                        slot.hotspot_y = sample.hotspot_y;
                        slot.width = sample.width;
                        slot.height = sample.height;
                        slot.pixels = argb_to_bgra(&sample.argb);
                    }
                    moved || reshaped
                } else {
                    false
                };
                if changed {
                    shared.cursor_generation.fetch_add(1, Ordering::Relaxed);
                }
            }
            Err(err) => tracing::debug!("cursor poll: {err}"),
        }
        thread::sleep(Duration::from_millis(2));
    }
}

fn paint_cursor(
    frame: &mut crate::core::CapturedFrame,
    origin_x: i32,
    origin_y: i32,
    cursor: &CursorSprite,
) {
    let width = cursor.width as i32;
    let height = cursor.height as i32;
    if width <= 0 || height <= 0 {
        return;
    }
    let expected = cursor.width as usize * cursor.height as usize * 4;
    if cursor.pixels.len() < expected {
        return;
    }
    let left = cursor.root_x - i32::from(cursor.hotspot_x) - origin_x;
    let top = cursor.root_y - i32::from(cursor.hotspot_y) - origin_y;
    let frame_w = frame.width as i32;
    let frame_h = frame.height as i32;
    let stride = frame.stride as usize;
    for row in 0..height {
        let dst_y = top + row;
        if dst_y < 0 || dst_y >= frame_h {
            continue;
        }
        for col in 0..width {
            let dst_x = left + col;
            if dst_x < 0 || dst_x >= frame_w {
                continue;
            }
            let src = ((row as usize * cursor.width as usize) + col as usize) * 4;
            let alpha = cursor.pixels[src + 3] as u32;
            if alpha == 0 {
                continue;
            }
            let dst = dst_y as usize * stride + dst_x as usize * 4;
            if dst + 2 >= frame.bgra.len() {
                continue;
            }
            if alpha == 255 {
                frame.bgra[dst] = cursor.pixels[src];
                frame.bgra[dst + 1] = cursor.pixels[src + 1];
                frame.bgra[dst + 2] = cursor.pixels[src + 2];
            } else {
                for channel in 0..3 {
                    let blended = cursor.pixels[src + channel] as u32 * alpha
                        + frame.bgra[dst + channel] as u32 * (255 - alpha);
                    frame.bgra[dst + channel] = (blended / 255) as u8;
                }
            }
        }
    }
}

fn argb_to_bgra(pixels: &[u32]) -> Vec<u8> {
    let mut out = Vec::with_capacity(pixels.len() * 4);
    for pixel in pixels {
        out.push(*pixel as u8);
        out.push((pixel >> 8) as u8);
        out.push((pixel >> 16) as u8);
        out.push((pixel >> 24) as u8);
    }
    out
}

fn ssrc_for(index: u8) -> u32 {
    let mixed = monotonic_ns() ^ 0x9E37_79B9u64.wrapping_mul(u64::from(index) + 1);
    let value = mixed as u32;
    if value == 0 {
        1
    } else {
        value
    }
}

pub fn receive(host: SocketAddr, output: &std::path::Path, seconds: u64) -> Result<()> {
    let control = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)))?;
    control.set_read_timeout(Some(Duration::from_millis(200)))?;
    let mut videos = Vec::new();
    for port in 48000..48006 {
        match UdpSocket::bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), port)) {
            Ok(socket) => {
                socket.set_nonblocking(true)?;
                videos.push(socket);
            }
            Err(err) => tracing::warn!(port, "video port unavailable: {err}"),
        }
    }
    if videos.is_empty() {
        return Err(Error::Msg(
            "could not bind a video port starting at 48000".into(),
        ));
    }
    let hello = ControlPacket::ClientHello {
        name: "floatdesk-recv".into(),
        video_port: 48000,
    };
    let mut file = std::fs::File::create(output)?;
    let mut depacketizers: Vec<floatdesk_protocol::RtpH264Depacketizer> = (0..6)
        .map(|_| floatdesk_protocol::RtpH264Depacketizer::new())
        .collect();
    let deadline = Instant::now() + Duration::from_secs(seconds);
    let mut access_units = 0u64;
    let mut buf = [0u8; 2048];
    let mut last_hello = Instant::now() - Duration::from_secs(2);
    use std::io::Write;
    while Instant::now() < deadline {
        if last_hello.elapsed() >= Duration::from_secs(1) {
            let _ = control.send_to(&hello.encode(), host);
            last_hello = Instant::now();
        }
        let _ = control.recv_from(&mut buf);
        for (index, socket) in videos.iter().enumerate() {
            loop {
                match socket.recv_from(&mut buf) {
                    Ok((size, _)) => {
                        if let Ok(floatdesk_protocol::Depacketized::AccessUnit(_)) =
                            depacketizers[index].push(&buf[..size])
                        {
                            let unit = depacketizers[index].take_access_unit();
                            file.write_all(&unit)?;
                            access_units += 1;
                        }
                    }
                    Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => break,
                    Err(err) => return Err(err.into()),
                }
            }
        }
        thread::sleep(Duration::from_millis(5));
    }
    tracing::info!(access_units, path = %output.display(), "receiver finished");
    if access_units == 0 {
        return Err(Error::Msg("received no video access units".into()));
    }
    Ok(())
}

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
