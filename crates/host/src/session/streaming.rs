//! Stream workers, RTP pacing and encoder lifecycle.
use super::*;

pub(super) fn launch_streams(
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
        let shared = Arc::clone(shared);
        let rect = *monitor;
        let ssrc = hello.ssrc;
        shared.stream_ssrc[rect.index as usize].store(ssrc, Ordering::Relaxed);
        let encoder_name = opts.encoder.clone();
        let fps = opts.fps.max(1);
        let bitrate = *bitrate;
        let total_bitrate = opts.bitrate_bps.max(1);
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
                    total_bitrate,
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
pub(super) fn stop_streams(shared: &Shared, threads: &mut Vec<thread::JoinHandle<()>>) {
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

pub(super) fn update_streams(
    shared: &Arc<Shared>,
    socket: &UdpSocket,
    opts: &ServeOpts,
    sources: &[(String, MonitorRect)],
    monitors: &mut Vec<MonitorRect>,
    hellos: &mut Vec<MonitorHello>,
    threads: &mut Vec<thread::JoinHandle<()>>,
) -> Result<()> {
    if let Err(error) = crate::platform::x11::notify_layout_clients() {
        tracing::warn!(%error,"Could not notify desktop layout consumers")
    }
    *shared.screens.write().unwrap_or_else(|e| e.into_inner()) =
        sources.iter().map(|(_, r)| *r).collect();
    let next = vec![desktop_rect(sources)?];
    if next != *monitors {
        stop_streams(shared, threads);
        *monitors = next;
        *hellos = source_hellos(monitors, sources);
        for r in monitors.iter() {
            shared.stream_active[r.index as usize].store(true, Ordering::Relaxed);
        }
        *threads = launch_streams(shared, monitors, hellos, opts);
        send_hello(shared, socket, hellos);
    } else {
        send_reload(shared, socket, false);
    }
    Ok(())
}

pub(super) struct VideoPacer {
    pub(super) next: Instant,
    bytes_per_second: f64,
}
impl VideoPacer {
    pub(super) fn new(bitrate: u32) -> Self {
        Self {
            next: Instant::now(),
            bytes_per_second: f64::from(bitrate.saturating_mul(4).clamp(8_000_000, 80_000_000))
                / 8.0,
        }
    }
    pub(super) fn reserve(&mut self, now: Instant, bytes: usize) -> Duration {
        let burst = Duration::from_secs_f64(9600.0 / self.bytes_per_second);
        let delay = self.next.saturating_duration_since(now + burst);
        self.next =
            self.next.max(now) + Duration::from_secs_f64(bytes as f64 / self.bytes_per_second);
        delay
    }
    pub(super) fn wait(&mut self, bytes: usize) {
        let delay = self.reserve(Instant::now(), bytes);
        if !delay.is_zero() {
            thread::sleep(delay);
        }
    }
}

struct VideoDestination {
    dest: SocketAddr,
    peer: SocketAddr,
    monitor_index: usize,
}

fn send_ready(
    encoder: &mut crate::media::EncodedOutput,
    packetizer: &mut RtpH264Packetizer,
    socket: &UdpSocket,
    shared: &Shared,
    timestamp: u32,
    pacer: &mut VideoPacer,
    target: VideoDestination,
) -> Result<()> {
    let VideoDestination {
        dest,
        peer,
        monitor_index,
    } = target;
    let mut waited = false;
    let mut delivered = 0;
    loop {
        let unit = if !waited {
            waited = true;
            encoder.pull_wait(8_000_000)?
        } else {
            encoder.pull()?
        };
        let Some(unit) = unit else { break };
        delivered += 1;
        shared.encoded_frames.fetch_add(1, Ordering::Relaxed);
        for packet in packetizer.packetize(&unit.bytes, timestamp) {
            if shared.stop.load(Ordering::Relaxed)
                || !shared.stream_active[monitor_index].load(Ordering::Relaxed)
            {
                return Ok(());
            }
            pacer.wait(packet.len());
            match socket.send_to(&packet, dest) {
                Ok(sent) => {
                    shared.bytes.fetch_add(sent as u64, Ordering::Relaxed);
                    if let Ok(mut cache) = shared.repairs.lock() {
                        cache.push_back(CachedRtp {
                            monitor: monitor_index as u8,
                            ssrc: u32::from_be_bytes(packet[8..12].try_into().unwrap()),
                            sequence: u16::from_be_bytes(packet[2..4].try_into().unwrap()),
                            peer,
                            dest,
                            bytes: packet,
                        });
                        if cache.len() > 2048 {
                            cache.pop_front();
                        }
                    }
                }
                Err(err) => tracing::debug!("video send: {err}"),
            }
        }
        if delivered >= 4 {
            break;
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

pub(super) fn monitor_thread(
    shared: Arc<Shared>,
    rect: MonitorRect,
    packetizer: &mut RtpH264Packetizer,
    encoder_name: String,
    fps: u32,
    bitrate: u32,
    total_bitrate: u32,
) -> Result<()> {
    let (width, height) = stream_size(rect);
    let mut capture = X11Capture::open_scaled(rect, width, height)?;
    shared.capture_fd[rect.index as usize].store(capture.connection_fd(), Ordering::Relaxed);
    let _clear_fd = ClearCaptureFd(&shared.capture_fd[rect.index as usize]);
    let (width, height) = stream_size(rect);
    let generation = shared.encoder_generation.load(Ordering::Relaxed);
    let quality = shared.encoder_quality.load(Ordering::Relaxed);
    let bitrate = ((shared.encoder_bitrate.load(Ordering::Relaxed) as u64 * bitrate as u64)
        / total_bitrate as u64)
        .min(u32::MAX as u64) as u32;
    let mut encoder = GstEncoder::open_scaled_quality(
        &encoder_name,
        crate::core::EncoderConfig {
            width: u32::from(width),
            height: u32::from(height),
            fps,
            bitrate_bps: bitrate.max(400_000),
        },
        capture.pixel_size(),
        quality,
    )?;
    let socket = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)))?;
    encoder.set_bitrate(bitrate.max(400_000))?;
    let interval = Duration::from_secs_f64(1.0 / f64::from(fps));
    let mut output = encoder.output();
    let done = AtomicBool::new(false);
    thread::scope(|scope| {
        let sender = scope.spawn(|| -> Result<()> {
            let mut pacer = VideoPacer::new(bitrate);
            let result = (|| {
                while !done.load(Ordering::Relaxed)
                    && !shared.stop.load(Ordering::Relaxed)
                    && shared.stream_active[rect.index as usize].load(Ordering::Relaxed)
                {
                    let target = shared
                        .client
                        .read()
                        .unwrap_or_else(|e| e.into_inner())
                        .clone();
                    if let Some(target) = target {
                        let dest = SocketAddr::new(
                            target.control.ip(),
                            target.video_port + u16::from(rect.index),
                        );
                        send_ready(
                            &mut output,
                            packetizer,
                            &socket,
                            &shared,
                            rtp_timestamp(monotonic_ns(), epoch_ns()),
                            &mut pacer,
                            VideoDestination {
                                monitor_index: rect.index as usize,
                                peer: target.control,
                                dest,
                            },
                        )?;
                    } else {
                        thread::sleep(Duration::from_millis(10));
                    }
                }
                Ok(())
            })();
            done.store(true, Ordering::Relaxed);
            result
        });
        let result = (|| {
            let mut view_was_active = false;
            let mut seen_generation = 0;
            let mut seen_cursor = 0;
            let mut clip: Option<(Vec<MonitorRect>, crate::media::desktop_mask::DesktopMask)> =
                None;
            while !done.load(Ordering::Relaxed)
                && !shared.stop.load(Ordering::Relaxed)
                && shared.stream_active[rect.index as usize].load(Ordering::Relaxed)
            {
                if shared.encoder_generation.load(Ordering::Relaxed) != generation {
                    break;
                }
                shared.stream_progress[rect.index as usize].fetch_add(1, Ordering::Relaxed);
                let target = shared
                    .client
                    .read()
                    .unwrap_or_else(|e| e.into_inner())
                    .clone();
                let Some(target) = target else {
                    thread::sleep(Duration::from_millis(20));
                    continue;
                };
                let paused = {
                    let state = shared.rendering.lock().unwrap_or_else(|e| e.into_inner());
                    state.0 == Some(target.control) && !state.1
                };
                if paused {
                    thread::sleep(Duration::from_millis(100));
                    continue;
                }
                let view_active = rect.index == 0
                    && monotonic_ns() < shared.view_until_ns.load(Ordering::Relaxed);
                if view_active {
                    view_was_active = true;
                    thread::sleep(Duration::from_millis(5));
                    continue;
                }
                if view_was_active {
                    view_was_active = false;
                    shared.keys[rect.index as usize].store(true, Ordering::Relaxed);
                    capture.note_cursor();
                }
                let screens = shared
                    .screens
                    .read()
                    .unwrap_or_else(|e| e.into_inner())
                    .clone();
                if clip
                    .as_ref()
                    .is_none_or(|(previous, _)| previous != &screens)
                {
                    let (w, h) = capture.pixel_size();
                    clip = Some((
                        screens.clone(),
                        crate::media::desktop_mask::DesktopMask::new(rect, w, h, &screens),
                    ));
                    capture.note_cursor(); // Refresh the mask even on an otherwise static desktop.
                }
                let requested = shared.keys[rect.index as usize].swap(false, Ordering::Relaxed);
                let force = target.generation != seen_generation || requested;
                seen_generation = target.generation;
                if force {
                    encoder.request_keyframe();
                }
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
                    paint_cursor_scaled(&mut frame, rect, &cursor);
                }
                tracing::trace!(
                    generation = frame.damage_generation,
                    capture_age_ns = monotonic_ns().saturating_sub(frame.monotonic_ns),
                    "submitting capture"
                );
                if let Some((_, mask)) = &clip {
                    mask.apply(&mut frame);
                }
                encoder.submit(&frame)?;
                shared.captured.fetch_add(1, Ordering::Relaxed);
            }
            Ok(())
        })();
        done.store(true, Ordering::Relaxed);
        let sent = sender
            .join()
            .map_err(|_| Error::Msg("Video sender panicked".into()))?;
        result.and(sent)
    })
}

pub(super) fn ssrc_for(index: u8) -> u32 {
    let mixed = monotonic_ns() ^ 0x9E37_79B9u64.wrapping_mul(u64::from(index) + 1);
    let value = mixed as u32;
    if value == 0 {
        1
    } else {
        value
    }
}
