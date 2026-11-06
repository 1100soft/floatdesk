//! Session loop. Each monitor has its own capture thread, encoder, and RTP
//! stream. Cursor updates travel on the control socket and do not wait for a
//! video frame. Nothing is encoded until a client hello arrives.

use crate::core::{
    epoch_ns, monotonic_ns, CaptureBackend, CursorBackend, DisplayBackend, EncodeBackend,
    MonitorRect,
};
use crate::error::{Error, Result};
use crate::media::GstEncoder;
use crate::platform::x11::{X11Capture, X11Cursor, X11Display};
use floatdesk_protocol::{
    allocate_bitrate, cursor_packets, rtp_timestamp, ControlPacket, CursorState, LayoutEntry,
    MonitorHello, MonitorLoad, RtpH264Packetizer,
};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, RwLock};
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
}

struct Shared {
    stop: AtomicBool,
    client: RwLock<Option<ClientTarget>>,
    session_id: u64,
    keys: [AtomicBool; 6],
    captured: AtomicU64,
    skipped: AtomicU64,
    bytes: AtomicU64,
}

pub fn serve(opts: ServeOpts) -> Result<()> {
    let _epoch = epoch_ns();
    let mut display = X11Display::open()?;
    let monitors = if opts.existing {
        vec![display.primary()?]
    } else {
        display.create_virtual(opts.monitors, opts.width, opts.height)?
    };
    if monitors.is_empty() {
        return Err(Error::Msg("no monitors to stream".into()));
    }
    let result = run(&monitors, &opts);
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

fn run(monitors: &[MonitorRect], opts: &ServeOpts) -> Result<()> {
    let socket = UdpSocket::bind(opts.listen)?;
    socket.set_nonblocking(true)?;
    let session_id = monotonic_ns();
    let hellos: Vec<MonitorHello> = monitors
        .iter()
        .map(|monitor| MonitorHello {
            index: monitor.index,
            width: monitor.width as u16,
            height: monitor.height as u16,
            port: 0,
            ssrc: ssrc_for(monitor.index),
            name: if opts.existing {
                "existing-0".into()
            } else {
                monitor.name()
            },
        })
        .collect();
    let loads: Vec<MonitorLoad> = monitors
        .iter()
        .enumerate()
        .map(|(i, monitor)| MonitorLoad {
            index: monitor.index,
            focused: i == 0,
            damaged: false,
            visible: true,
        })
        .collect();
    let bitrates = allocate_bitrate(opts.bitrate_bps, &loads);
    let shared = Arc::new(Shared {
        stop: AtomicBool::new(false),
        client: RwLock::new(None),
        session_id,
        keys: std::array::from_fn(|_| AtomicBool::new(false)),
        captured: AtomicU64::new(0),
        skipped: AtomicU64::new(0),
        bytes: AtomicU64::new(0),
    });
    let stop_flag = Arc::clone(&shared);
    let _ = ctrlc::set_handler(move || {
        stop_flag.stop.store(true, Ordering::Relaxed);
    });

    let mut threads = Vec::new();
    for (monitor, (hello, bitrate)) in monitors.iter().zip(hellos.iter().zip(bitrates.iter())) {
        let shared = Arc::clone(&shared);
        let rect = *monitor;
        let ssrc = hello.ssrc;
        let encoder_name = opts.encoder.clone();
        let fps = opts.fps.max(1);
        let bitrate = *bitrate;
        threads.push(thread::spawn(move || {
            if let Err(err) = monitor_thread(shared, rect, ssrc, encoder_name, fps, bitrate) {
                tracing::error!(monitor = rect.index, "{err}");
            }
        }));
    }

    tracing::info!(listen = %opts.listen, "waiting for a client hello");
    let mut cursor = X11Cursor::open()?;
    let mut last_cursor = (i32::MIN, i32::MIN, u64::MAX);
    let mut last_hello = Instant::now() - Duration::from_secs(2);
    let mut last_stats = Instant::now();
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
                    handle_datagram(&shared, &socket, &hellos, &scratch[..size], from)
                }
                Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => break,
                Err(err) => return Err(err.into()),
            }
        }
        if last_hello.elapsed() >= Duration::from_secs(1) {
            send_hello(&shared, &socket, &hellos);
            last_hello = Instant::now();
        }
        if let Ok(sample) = cursor.poll() {
            let moved = (sample.root_x, sample.root_y, sample.serial) != last_cursor;
            if moved {
                last_cursor = (sample.root_x, sample.root_y, sample.serial);
                send_cursor(&shared, &socket, monitors, &sample);
            }
        }
        if last_stats.elapsed() >= Duration::from_secs(2) {
            tracing::info!(
                frames = shared.captured.load(Ordering::Relaxed),
                skipped = shared.skipped.load(Ordering::Relaxed),
                bytes = shared.bytes.load(Ordering::Relaxed),
                "session"
            );
            last_stats = Instant::now();
        }
        thread::sleep(Duration::from_millis(8));
    }
    shared.stop.store(true, Ordering::Relaxed);
    for thread in threads {
        let _ = thread.join();
    }
    Ok(())
}

fn monitor_thread(
    shared: Arc<Shared>,
    rect: MonitorRect,
    ssrc: u32,
    encoder_name: String,
    fps: u32,
    bitrate: u32,
) -> Result<()> {
    let mut capture = X11Capture::open(rect)?;
    let mut encoder = GstEncoder::open(
        &encoder_name,
        crate::core::EncoderConfig {
            width: rect.width,
            height: rect.height,
            fps,
            bitrate_bps: bitrate.max(400_000),
        },
    )?;
    let socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)))?;
    encoder.set_bitrate(bitrate.max(400_000))?;
    let mut packetizer = RtpH264Packetizer::new(ssrc);
    let interval = Duration::from_secs_f64(1.0 / f64::from(fps));
    let mut seen_generation = 0u64;
    while !shared.stop.load(Ordering::Relaxed) {
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
        let Some(frame) = capture.poll(interval, force)? else {
            shared.skipped.fetch_add(1, Ordering::Relaxed);
            thread::sleep(Duration::from_millis(2));
            continue;
        };
        encoder.submit(&frame)?;
        shared.captured.fetch_add(1, Ordering::Relaxed);
        tracing::trace!(
            generation = frame.damage_generation,
            monitor = rect.index,
            "captured"
        );
        let timestamp = rtp_timestamp(frame.monotonic_ns, epoch_ns());
        let mut waited = false;
        loop {
            let unit = if !waited {
                waited = true;
                encoder.pull_wait(8_000_000)?
            } else {
                encoder.pull()?
            };
            let Some(unit) = unit else { break };
            let dest = SocketAddr::new(
                target.control.ip(),
                target.video_port + u16::from(rect.index),
            );
            for packet in packetizer.packetize(&unit.bytes, timestamp) {
                match socket.send_to(&packet, dest) {
                    Ok(sent) => {
                        shared.bytes.fetch_add(sent as u64, Ordering::Relaxed);
                    }
                    Err(err) => tracing::debug!("video send: {err}"),
                }
            }
        }
    }
    Ok(())
}

fn handle_datagram(
    shared: &Shared,
    socket: &UdpSocket,
    hellos: &[MonitorHello],
    bytes: &[u8],
    from: SocketAddr,
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
            *client = Some(ClientTarget {
                control: from,
                video_port,
                generation,
            });
            drop(client);
            send_hello(shared, socket, hellos);
            send_layout(shared, socket, hellos);
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
            tracing::info!(%from, "client goodbye");
            *shared.client.write().unwrap_or_else(|err| err.into_inner()) = None;
        }
        _ => {}
    }
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

fn send_cursor(
    shared: &Shared,
    socket: &UdpSocket,
    monitors: &[MonitorRect],
    sample: &crate::core::CursorSample,
) {
    let Some(client) = shared
        .client
        .read()
        .unwrap_or_else(|err| err.into_inner())
        .as_ref()
        .cloned()
    else {
        return;
    };
    let hit = monitors
        .iter()
        .find(|monitor| monitor.contains(sample.root_x, sample.root_y));
    let (index, visible, norm_x, norm_y) = match hit {
        Some(monitor) => (
            monitor.index,
            true,
            (sample.root_x - monitor.x) as f32 / monitor.width as f32,
            (sample.root_y - monitor.y) as f32 / monitor.height as f32,
        ),
        None => (0, false, 0.0, 0.0),
    };
    let pixels = if sample.width > 0
        && sample.height > 0
        && sample.width <= 256
        && sample.height <= 256
        && sample.argb.len() == sample.width as usize * sample.height as usize
    {
        argb_to_bgra(&sample.argb)
    } else {
        Vec::new()
    };
    let state = CursorState {
        monitor_index: index,
        visible,
        hotspot_x: sample.hotspot_x,
        hotspot_y: sample.hotspot_y,
        norm_x,
        norm_y,
        shape_id: sample.serial,
        timestamp_ns: monotonic_ns(),
        bitmap_w: sample.width,
        bitmap_h: sample.height,
        frag_index: 0,
        frag_count: 0,
        pixels,
    };
    let packets = match cursor_packets(&state) {
        Ok(packets) => packets,
        Err(err) => {
            tracing::debug!("cursor packet: {err}");
            return;
        }
    };
    for packet in packets {
        let _ = socket.send_to(&packet.encode(), client.control);
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
