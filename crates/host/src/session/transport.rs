//! Peer-scoped control routing, discovery and pointer mapping.
use super::*;

pub(super) fn handle_datagram(
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
            shared
                .cursor_map
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .0
                .clear();
            shared
                .cursor_claim
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .take();
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
        ControlPacket::VideoNack {
            monitor_index,
            ssrc,
            sequence,
        } => {
            let allowed = shared
                .client
                .read()
                .unwrap_or_else(|e| e.into_inner())
                .as_ref()
                .is_some_and(|c| c.control == from);
            if !allowed
                || !shared.stream_active[monitor_index as usize].load(Ordering::Relaxed)
                || shared.stream_ssrc[monitor_index as usize].load(Ordering::Relaxed) != ssrc
            {
                return;
            }
            let mut window = shared
                .repair_window
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            if window.0.elapsed() >= Duration::from_millis(100) {
                *window = (Instant::now(), 0);
            }
            if window.1 >= 64 {
                return;
            }
            window.1 += 1;
            drop(window);
            let cached = shared
                .repairs
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .iter()
                .rev()
                .find(|p| {
                    p.monitor == monitor_index
                        && p.ssrc == ssrc
                        && p.sequence == sequence
                        && p.peer == from
                })
                .cloned();
            if let Some(packet) = cached {
                if socket.send_to(&packet.bytes, packet.dest).is_ok() {
                    shared.repaired.fetch_add(1, Ordering::Relaxed);
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

pub(super) fn pointer_position(
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

pub(super) fn click_position(
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

pub(super) fn send_beacon(listen_port: u16) {
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

pub(super) fn host_name() -> String {
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

pub(super) fn broadcast_targets() -> Vec<SocketAddr> {
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

pub(super) fn send_hello(shared: &Shared, socket: &UdpSocket, hellos: &[MonitorHello]) {
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

pub(super) fn send_layout(shared: &Shared, socket: &UdpSocket, hellos: &[MonitorHello]) {
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
