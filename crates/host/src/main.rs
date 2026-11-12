mod core;
mod error;
mod media;
mod platform;
mod session;

use crate::core::{DisplayBackend, EncodeBackend};
use crate::error::Result;
use clap::{Parser, Subcommand};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::time::Duration;

#[derive(Parser)]
#[command(
    name = "floatdesk",
    about = "Stream X11 monitors to an Android XR workspace"
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Open the desktop tray with service controls, output status and virtual-display setup.
    Tray,
    /// Report RandR, capture extensions, and H.264 encoders. Does not change the screen.
    Doctor,
    /// Create logical monitors and stream them until Ctrl-C.
    Serve {
        #[arg(long, default_value = "0.0.0.0:47000")]
        listen: SocketAddr,
        #[arg(long, default_value_t = 1)]
        monitors: u8,
        #[arg(long, default_value_t = 1920)]
        width: u16,
        #[arg(long, default_value_t = 1080)]
        height: u16,
        #[arg(long, default_value_t = 60)]
        fps: u32,
        /// Total video bitrate shared across monitors.
        #[arg(long, default_value_t = 12_000_000)]
        bitrate: u32,
        /// Stream active XRandR outputs and allow management of a pre-provisioned VKMS pool.
        #[arg(long)]
        existing: bool,
        /// `auto`, or one of nvh264enc, vah264enc, vaapih264enc, x264enc, openh264enc.
        #[arg(long, default_value = "auto")]
        encoder: String,
        /// Stop after this many seconds. Useful for a loopback test.
        #[arg(long)]
        duration: Option<u64>,
    },
    /// Delete Floatdesk monitors and return the framebuffer to its saved size.
    Restore,
    /// Create one 1280x720 logical monitor, confirm RandR lists it, then restore.
    Trial,
    /// Encode a synthetic frame through GStreamer and write Annex-B.
    EncodeOnce {
        #[arg(long)]
        output: PathBuf,
    },
    /// Capture a corner of the current screen through MIT-SHM and write a PPM.
    CaptureOnce {
        #[arg(long)]
        output: PathBuf,
        #[arg(long, default_value_t = 320)]
        width: u16,
        #[arg(long, default_value_t = 180)]
        height: u16,
    },
    /// Receive a LAN proof-of-concept stream and write Annex-B.
    Recv {
        #[arg(long, default_value = "127.0.0.1:47000")]
        host: SocketAddr,
        #[arg(long)]
        output: PathBuf,
        #[arg(long, default_value_t = 5)]
        seconds: u64,
    },
}

fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();
    if let Err(err) = run(Cli::parse()) {
        eprintln!("floatdesk: {err}");
        std::process::exit(1);
    }
}

fn run(cli: Cli) -> Result<()> {
    match cli.command {
        Command::Tray => {
            let root = std::env::var_os("FLOATDESK_DATA_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../.."));
            let status = std::process::Command::new("python3")
                .arg(root.join("scripts/floatdesk-tray.py"))
                .status()?;
            if !status.success() {
                return Err(crate::error::Error::Msg(
                    "Tray failed; install PySide6 for the selected Python interpreter".into(),
                ));
            }
        }
        Command::Doctor => {
            let report = platform::x11::doctor_report()?;
            for line in report.lines {
                println!("{line}");
            }
            if !report.ok {
                std::process::exit(1);
            }
        }
        Command::Serve {
            listen,
            monitors,
            width,
            height,
            fps,
            bitrate,
            existing,
            encoder,
            duration,
        } => session::serve(session::ServeOpts {
            listen,
            monitors,
            width,
            height,
            fps,
            bitrate_bps: bitrate,
            existing,
            encoder,
            duration: duration.map(Duration::from_secs),
        })?,
        Command::Restore => {
            let mut display = platform::x11::X11Display::open()?;
            display.restore()?;
        }
        Command::Trial => session::trial()?,
        Command::EncodeOnce { output } => encode_once(&output)?,
        Command::CaptureOnce {
            output,
            width,
            height,
        } => capture_once(&output, width, height)?,
        Command::Recv {
            host,
            output,
            seconds,
        } => session::receive(host, &output, seconds)?,
    }
    Ok(())
}

fn encode_once(path: &std::path::Path) -> Result<()> {
    use crate::core::{monotonic_ns, CapturedFrame, EncoderConfig};
    use crate::media::GstEncoder;
    use std::io::Write;

    let mut encoder = GstEncoder::open(
        "x264enc",
        EncoderConfig {
            width: 320,
            height: 180,
            fps: 30,
            bitrate_bps: 2_000_000,
        },
    )?;
    let mut bgra = vec![0u8; 320 * 180 * 4];
    for y in 0..180u32 {
        for x in 0..320u32 {
            let i = ((y * 320 + x) * 4) as usize;
            bgra[i] = x as u8;
            bgra[i + 1] = y as u8;
            bgra[i + 2] = 220;
            bgra[i + 3] = 255;
        }
    }
    let frame = CapturedFrame {
        width: 320,
        height: 180,
        stride: 320 * 4,
        bgra,
        monotonic_ns: monotonic_ns(),
        damage_generation: 1,
    };
    let mut file = std::fs::File::create(path)?;
    let mut bytes = 0usize;
    for _ in 0..5 {
        encoder.submit(&frame)?;
        let start = std::time::Instant::now();
        while start.elapsed() < Duration::from_millis(200) {
            if let Some(unit) = encoder.pull_wait(20_000_000)? {
                file.write_all(&unit.bytes)?;
                bytes += unit.bytes.len();
            }
        }
    }
    if bytes == 0 {
        return Err(error::Error::Encoder("encoder produced no bytes".into()));
    }
    println!("wrote {bytes} bytes to {}", path.display());
    Ok(())
}

fn capture_once(path: &std::path::Path, width: u16, height: u16) -> Result<()> {
    use crate::core::{CaptureBackend, MonitorRect};
    use crate::platform::x11::X11Capture;
    use std::io::Write;

    let mut capture = X11Capture::open(MonitorRect {
        index: 0,
        x: 0,
        y: 0,
        width: u32::from(width),
        height: u32::from(height),
    })?;
    let frame = capture
        .poll(Duration::ZERO, true)?
        .ok_or_else(|| error::Error::X11("capture returned no frame".into()))?;
    let mut file = std::fs::File::create(path)?;
    write!(file, "P6\n{} {}\n255\n", frame.width, frame.height)?;
    let stride = frame.stride as usize;
    for y in 0..frame.height as usize {
        let start = y * stride;
        for x in 0..frame.width as usize {
            let i = start + x * 4;
            // BGRx in memory. PPM wants RGB.
            file.write_all(&[frame.bgra[i + 2], frame.bgra[i + 1], frame.bgra[i]])?;
        }
    }
    println!(
        "captured {}x{} stride {} to {}",
        frame.width,
        frame.height,
        frame.stride,
        path.display()
    );
    Ok(())
}
