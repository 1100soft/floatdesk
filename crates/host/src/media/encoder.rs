//! H.264 encoder profiles. Hardware elements are preferred when the plugin
//! loads; x264 and OpenH264 remain the portable low-latency fallbacks.
//! B-frames stay off. A keyframe can be requested without restarting the pipeline.

use super::gst::{Element, Pipeline};
use crate::core::{CapturedFrame, EncodeBackend, EncodedAccessUnit, EncoderConfig};
use crate::error::{Error, Result};
use std::borrow::Cow;
use std::process::Command;

#[derive(Clone, Copy)]
struct Profile {
    element: &'static str,
    format: &'static str,
    /// `true` when the element's `bitrate` property is kilobits per second.
    kilobits: bool,
    props: &'static [(&'static str, &'static str)],
}

const NVENC: &[(&str, &str)] = &[
    ("rc-mode", "cbr"),
    ("preset", "low-latency-hq"),
    ("zerolatency", "true"),
    ("bframes", "0"),
];
const VA: &[(&str, &str)] = &[
    ("rate-control", "cbr"),
    ("b-frames", "0"),
    ("cabac", "false"),
    ("target-usage", "6"),
    ("cpb-size", "400"),
    ("ref-frames", "1"),
    ("aud", "true"),
];
const X264: &[(&str, &str)] = &[
    ("tune", "zerolatency"),
    ("speed-preset", "ultrafast"),
    ("pass", "cbr"),
    ("bframes", "0"),
    ("byte-stream", "true"),
    ("aud", "true"),
    ("vbv-buf-capacity", "60"),
    ("rc-lookahead", "0"),
    ("sync-lookahead", "0"),
    ("sliced-threads", "true"),
    ("threads", "4"),
];
const OPENH264: &[(&str, &str)] = &[
    ("complexity", "low"),
    ("usage-type", "screen"),
    ("rate-control", "bitrate"),
    ("enable-frame-skip", "false"),
];

fn profiles() -> &'static [Profile] {
    &[
        Profile {
            element: "nvh264enc",
            format: "NV12",
            kilobits: true,
            props: NVENC,
        },
        Profile {
            element: "vah264enc",
            format: "NV12",
            kilobits: true,
            props: VA,
        },
        Profile {
            element: "vaapih264enc",
            format: "NV12",
            kilobits: true,
            props: VA,
        },
        Profile {
            element: "x264enc",
            format: "I420",
            kilobits: true,
            props: X264,
        },
        Profile {
            element: "openh264enc",
            format: "I420",
            kilobits: false,
            props: OPENH264,
        },
    ]
}

pub fn available_encoders() -> Vec<&'static str> {
    profiles()
        .iter()
        .filter(|profile| inspect(profile.element))
        .map(|profile| profile.element)
        .collect()
}

fn inspect(name: &str) -> bool {
    Command::new("gst-inspect-1.0")
        .arg(name)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|status| status.success())
        .unwrap_or(false)
}

pub struct GstEncoder {
    pipeline: Pipeline,
    profile: Profile,
    config: EncoderConfig,
    keyframe_pending: bool,
}

impl GstEncoder {
    pub fn open(preference: &str, config: EncoderConfig) -> Result<Self> {
        if config.width % 2 != 0 || config.height % 2 != 0 {
            return Err(Error::Encoder(
                "encoder width and height must be even".into(),
            ));
        }
        let chosen: Vec<Profile> = if preference == "auto" {
            profiles()
                .iter()
                .copied()
                .filter(|profile| inspect(profile.element))
                .collect()
        } else {
            profiles()
                .iter()
                .copied()
                .filter(|profile| profile.element == preference)
                .collect()
        };
        if chosen.is_empty() {
            return Err(Error::Encoder(format!(
                "no H.264 encoder matches `{preference}`"
            )));
        }
        let mut errors = Vec::new();
        for profile in chosen {
            match Self::start(profile, config) {
                Ok(encoder) => {
                    tracing::info!(encoder = profile.element, "using H.264 encoder");
                    return Ok(encoder);
                }
                Err(err) => {
                    tracing::warn!(encoder = profile.element, "{err}");
                    errors.push(format!("{}: {err}", profile.element));
                }
            }
        }
        Err(Error::Encoder(errors.join("; ")))
    }

    fn start(profile: Profile, config: EncoderConfig) -> Result<Self> {
        let max_bytes = (config.width as u64 * config.height as u64 * 4).saturating_mul(2);
        let description = format!(
            "appsrc name=src is-live=true format=time do-timestamp=true block=false max-bytes={max_bytes} \
             ! videoconvert ! video/x-raw,format={} \
             ! {} name=enc \
             ! h264parse config-interval=-1 \
             ! video/x-h264,stream-format=byte-stream,alignment=au \
             ! appsink name=sink emit-signals=false sync=false max-buffers=1 drop=false",
            profile.format, profile.element
        );
        let pipeline = Pipeline::launch(&description)?;
        let caps = format!(
            "video/x-raw,format=BGRx,width={},height={},framerate={}/1",
            config.width,
            config.height,
            config.fps.max(1)
        );
        pipeline.set_arg(Element::Source, "caps", &caps)?;
        for (name, value) in profile.props {
            pipeline.set_arg(Element::Encoder, name, value)?;
        }
        // Bound recovery time even if the client's IDR request is lost.
        let gop = config.fps.max(1).to_string();
        let _ = pipeline.set_arg(Element::Encoder, "key-int-max", &gop);
        let _ = pipeline.set_arg(Element::Encoder, "gop-size", &gop);
        apply_bitrate(&pipeline, profile, config.bitrate_bps)?;
        pipeline.play()?;
        Ok(Self {
            pipeline,
            profile,
            config,
            keyframe_pending: true,
        })
    }
}

impl EncodeBackend for GstEncoder {
    fn submit(&mut self, frame: &CapturedFrame) -> Result<()> {
        if frame.width != self.config.width || frame.height != self.config.height {
            return Err(Error::Encoder(format!(
                "frame {}x{} does not match encoder {}x{}",
                frame.width, frame.height, self.config.width, self.config.height
            )));
        }
        if self.keyframe_pending {
            self.pipeline.force_keyframe();
            self.keyframe_pending = false;
        }
        let packed = pack_bgrx(frame);
        self.pipeline.push(&packed)
    }

    fn request_keyframe(&mut self) {
        self.keyframe_pending = true;
    }

    fn set_bitrate(&mut self, bps: u32) -> Result<()> {
        self.config.bitrate_bps = bps;
        apply_bitrate(&self.pipeline, self.profile, bps)
    }

    fn pull(&mut self) -> Result<Option<EncodedAccessUnit>> {
        Ok(self
            .pipeline
            .pull(0)?
            .map(|bytes| EncodedAccessUnit { bytes }))
    }
}

impl GstEncoder {
    pub fn pull_wait(&mut self, timeout_ns: u64) -> Result<Option<EncodedAccessUnit>> {
        Ok(self
            .pipeline
            .pull(timeout_ns)?
            .map(|bytes| EncodedAccessUnit { bytes }))
    }
}

fn apply_bitrate(pipeline: &Pipeline, profile: Profile, bps: u32) -> Result<()> {
    let value = if profile.kilobits {
        (bps / 1000).max(100).to_string()
    } else {
        bps.max(100_000).to_string()
    };
    pipeline.set_arg(Element::Encoder, "bitrate", &value)
}

fn pack_bgrx(frame: &CapturedFrame) -> Cow<'_, [u8]> {
    let row = frame.width as usize * 4;
    let stride = frame.stride as usize;
    if stride == row {
        return Cow::Borrowed(&frame.bgra);
    }
    let mut packed = vec![0u8; row * frame.height as usize];
    for y in 0..frame.height as usize {
        let src = y * stride;
        let dst = y * row;
        let end = src + row;
        if end <= frame.bgra.len() {
            packed[dst..dst + row].copy_from_slice(&frame.bgra[src..end]);
        }
    }
    Cow::Owned(packed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::{monotonic_ns, EncodeBackend};

    #[test]
    fn x264_emits_an_annex_b_access_unit() {
        if !inspect("x264enc") {
            return;
        }
        let config = EncoderConfig {
            width: 320,
            height: 180,
            fps: 30,
            bitrate_bps: 2_000_000,
        };
        let mut encoder = GstEncoder::open("x264enc", config).expect("encoder");
        let frame = synthetic(320, 180);
        let mut output = Vec::new();
        for _ in 0..8 {
            encoder.submit(&frame).expect("submit");
            let start = std::time::Instant::now();
            while start.elapsed() < std::time::Duration::from_millis(300) {
                if let Some(au) = encoder.pull_wait(20_000_000).expect("pull") {
                    output.extend_from_slice(&au.bytes);
                }
                if output.windows(4).any(|w| w == [0, 0, 0, 1]) {
                    return;
                }
            }
        }
        panic!(
            "encoder produced {} bytes without an Annex-B start code",
            output.len()
        );
    }

    fn synthetic(width: u32, height: u32) -> CapturedFrame {
        let mut bgra = vec![0u8; (width * height * 4) as usize];
        for y in 0..height {
            for x in 0..width {
                let i = ((y * width + x) * 4) as usize;
                bgra[i] = x as u8;
                bgra[i + 1] = y as u8;
                bgra[i + 2] = 200;
                bgra[i + 3] = 255;
            }
        }
        CapturedFrame {
            width,
            height,
            stride: width * 4,
            bgra,
            monotonic_ns: monotonic_ns(),
            damage_generation: 1,
        }
    }
}
