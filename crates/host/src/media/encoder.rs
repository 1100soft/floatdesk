//! H.264 encoder profiles. Hardware elements are preferred when the plugin
//! loads; x264 and OpenH264 remain the portable low-latency fallbacks.
//! B-frames stay off. A keyframe can be requested without restarting the pipeline.

use super::gst::{Element, Pipeline};
use crate::core::{CapturedFrame, EncodeBackend, EncodedAccessUnit, EncoderConfig};
use crate::error::{Error, Result};
use std::borrow::Cow;
use std::process::Command;
use std::sync::Arc;

#[derive(Clone, Copy)]
struct Profile {
    element: &'static str,
    format: &'static str,
    /// `true` when the element's `bitrate` property is kilobits per second.
    kilobits: bool,
    props: &'static [(&'static str, &'static str)],
}

// Raw frames may be replaced safely before encoding. max-bytes with block=false
// alone is a notification threshold, not a queue bound.
const LIVE_SOURCE: &str = "appsrc name=src is-live=true format=time do-timestamp=true block=false max-buffers=1 leaky-type=downstream";

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

pub struct EncodedOutput {
    pipeline: Arc<Pipeline>,
}
impl EncodedOutput {
    pub fn pull(&mut self) -> Result<Option<EncodedAccessUnit>> {
        self.pull_wait(0)
    }
    pub fn pull_wait(&mut self, timeout: u64) -> Result<Option<EncodedAccessUnit>> {
        Ok(self
            .pipeline
            .pull(timeout)?
            .map(|bytes| EncodedAccessUnit { bytes }))
    }
}
pub struct GstEncoder {
    pipeline: Arc<Pipeline>,
    profile: Profile,
    config: EncoderConfig,
    input_size: (u32, u32),
    keyframe_pending: bool,
}

impl GstEncoder {
    pub fn output(&self) -> EncodedOutput {
        EncodedOutput {
            pipeline: Arc::clone(&self.pipeline),
        }
    }
    pub fn open(preference: &str, config: EncoderConfig) -> Result<Self> {
        Self::open_scaled(preference, config, (config.width, config.height))
    }
    pub fn open_scaled(
        preference: &str,
        config: EncoderConfig,
        input_size: (u32, u32),
    ) -> Result<Self> {
        Self::open_scaled_quality(preference, config, input_size, 0)
    }
    pub fn open_hevc(config: EncoderConfig, quality: u32) -> Result<Self> {
        if !inspect("vah265enc") {
            return Err(Error::Encoder("Hardware HEVC unavailable".into()));
        }
        Self::start(
            Profile {
                element: "vah265enc",
                format: "NV12",
                kilobits: true,
                props: VA,
            },
            config,
            (config.width, config.height),
            quality,
        )
    }
    pub fn open_quality(preference: &str, config: EncoderConfig, quality: u32) -> Result<Self> {
        Self::open_scaled_quality(preference, config, (config.width, config.height), quality)
    }
    pub fn open_scaled_quality(
        preference: &str,
        config: EncoderConfig,
        input_size: (u32, u32),
        quality: u32,
    ) -> Result<Self> {
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
            match Self::start(profile, config, input_size, quality) {
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

    fn start(
        profile: Profile,
        config: EncoderConfig,
        input_size: (u32, u32),
        quality: u32,
    ) -> Result<Self> {
        let max_bytes = (input_size.0 as u64 * input_size.1 as u64 * 4).saturating_mul(2);
        // A fused, parallel conversion avoids the serial full-size conversion
        // followed by a second scaling pass over a large combined desktop.
        let conversion = if inspect("videoconvertscale") {
            format!(
                "videoconvertscale n-threads=4 ! video/x-raw,format={},width={},height={}",
                profile.format, config.width, config.height
            )
        } else {
            format!(
                "videoconvert ! videoscale ! video/x-raw,format={},width={},height={}",
                profile.format, config.width, config.height
            )
        };
        let hevc = profile.element == "vah265enc";
        let parser = if hevc { "h265parse" } else { "h264parse" };
        let media = if hevc { "video/x-h265" } else { "video/x-h264" };
        let description = format!(
            "{LIVE_SOURCE} max-bytes={max_bytes} \
             ! {conversion} \
             ! {} name=enc \
             ! {parser} config-interval=-1 \
             ! {media},stream-format=byte-stream,alignment=au \
             ! appsink name=sink emit-signals=false sync=false max-buffers=2 leaky-type=none",
            profile.element
        );
        let pipeline = Pipeline::launch(&description)?;
        let caps = format!(
            "video/x-raw,format=BGRx,width={},height={},framerate={}/1",
            input_size.0,
            input_size.1,
            config.fps.max(1)
        );
        pipeline.set_arg(Element::Source, "caps", &caps)?;
        for (name, value) in profile.props {
            pipeline.set_arg(Element::Encoder, name, value)?;
        }
        if quality > 0 {
            match profile.element {
                "vah264enc" | "vaapih264enc" | "vah265enc" => pipeline.set_arg(
                    Element::Encoder,
                    "target-usage",
                    if quality == 1 { "4" } else { "1" },
                )?,
                "x264enc" => {
                    pipeline.set_arg(
                        Element::Encoder,
                        "speed-preset",
                        if quality == 1 {
                            "superfast"
                        } else {
                            "veryfast"
                        },
                    )?;
                    pipeline.set_arg(Element::Encoder, "cabac", "false")?;
                    pipeline.set_arg(Element::Encoder, "dct8x8", "false")?;
                    pipeline.set_arg(Element::Encoder, "ref", "1")?;
                }
                "nvh264enc" => pipeline.set_arg(
                    Element::Encoder,
                    "preset",
                    if quality == 1 { "p4" } else { "p6" },
                )?,
                "openh264enc" => pipeline.set_arg(
                    Element::Encoder,
                    "complexity",
                    if quality == 1 { "medium" } else { "high" },
                )?,
                _ => {}
            }
        }
        // Bound recovery time even if the client's IDR request is lost.
        let gop = config.fps.max(1).to_string();
        let _ = pipeline.set_arg(Element::Encoder, "key-int-max", &gop);
        let _ = pipeline.set_arg(Element::Encoder, "gop-size", &gop);
        apply_bitrate(&pipeline, profile, config.bitrate_bps)?;
        pipeline.play()?;
        Ok(Self {
            pipeline: Arc::new(pipeline),
            profile,
            config,
            input_size,
            keyframe_pending: true,
        })
    }
}

impl EncodeBackend for GstEncoder {
    fn submit(&mut self, frame: &CapturedFrame) -> Result<()> {
        if (frame.width, frame.height) != self.input_size {
            return Err(Error::Encoder(format!(
                "frame {}x{} does not match encoder {}x{}",
                frame.width, frame.height, self.input_size.0, self.input_size.1
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

    #[test]
    fn scaled_input_emits_an_access_unit() {
        if !inspect("x264enc") {
            return;
        }
        let config = EncoderConfig {
            width: 320,
            height: 180,
            fps: 30,
            bitrate_bps: 2_000_000,
        };
        let mut encoder = GstEncoder::open_scaled("x264enc", config, (640, 360)).unwrap();
        encoder.submit(&synthetic(640, 360)).unwrap();
        assert!(encoder.pull_wait(2_000_000_000).unwrap().is_some());
        assert!(encoder.submit(&synthetic(320, 180)).is_err());
    }
    #[test]
    fn split_output_keeps_pipeline_alive_after_producer_drops() {
        if !inspect("x264enc") {
            return;
        }
        let mut encoder = GstEncoder::open(
            "x264enc",
            EncoderConfig {
                width: 320,
                height: 180,
                fps: 30,
                bitrate_bps: 1_000_000,
            },
        )
        .unwrap();
        let mut output = encoder.output();
        encoder.submit(&synthetic(320, 180)).unwrap();
        drop(encoder);
        let unit = output
            .pull_wait(2_000_000_000)
            .unwrap()
            .expect("separate sender retained pipeline");
        assert!(unit.bytes.windows(4).any(|v| v == [0, 0, 0, 1]));
    }
    #[test]
    fn raw_input_keeps_latest_frame_when_downstream_is_slow() {
        let pipeline = Pipeline::launch(&format!(
            "{LIVE_SOURCE} max-bytes=1024 ! identity name=enc sleep-time=100000 ! appsink name=sink sync=false max-buffers=8"
        )).expect("slow pipeline");
        pipeline.play().expect("play");
        pipeline.push(&[0]).expect("initial frame");
        assert_eq!(pipeline.pull(1_000_000_000).unwrap(), Some(vec![0]));
        for value in 1..=80 {
            pipeline.push(&[value]).expect("push burst");
        }
        let mut observed = Vec::new();
        while observed.len() < 4 {
            let Some(value) = pipeline.pull(500_000_000).unwrap() else {
                break;
            };
            observed.push(value[0]);
            if value[0] == 80 {
                break;
            }
        }
        assert_eq!(observed.last(), Some(&80), "latest raw frame must survive");
        assert!(
            observed.len() <= 2,
            "obsolete raw frames accumulated: {observed:?}"
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
