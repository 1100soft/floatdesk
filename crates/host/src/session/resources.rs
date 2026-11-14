//! One-Hz process telemetry. GPU values are per-process DRM engine counters, not system-wide guesses.
use super::*;
use std::collections::HashMap;
use std::fs;

fn cpu_ticks(text: &str) -> Option<u64> {
    let fields = text
        .rsplit_once(") ")?
        .1
        .split_whitespace()
        .collect::<Vec<_>>();
    Some(fields.get(11)?.parse::<u64>().ok()? + fields.get(12)?.parse::<u64>().ok()?)
}
fn gpu_counters() -> HashMap<(String, String), (u64, u64)> {
    let mut counters = HashMap::new();
    let Ok(files) = fs::read_dir("/proc/self/fdinfo") else {
        return counters;
    };
    for file in files.take(256).flatten() {
        let Ok(text) = fs::read_to_string(file.path()) else {
            continue;
        };
        let Some(id) = text
            .lines()
            .find_map(|l| l.strip_prefix("drm-client-id:").map(str::trim))
        else {
            continue;
        };
        for line in text.lines() {
            let Some((key, value)) = line.split_once(':') else {
                continue;
            };
            let Some(engine) = key.strip_prefix("drm-engine-") else {
                continue;
            };
            if engine.starts_with("capacity-") {
                continue;
            }
            let Some(ns) = value
                .split_whitespace()
                .next()
                .and_then(|v| v.parse::<u64>().ok())
            else {
                continue;
            };
            let capacity = text
                .lines()
                .find_map(|l| {
                    l.strip_prefix(&format!("drm-engine-capacity-{engine}:"))
                        .and_then(|v| v.trim().parse::<u64>().ok())
                })
                .unwrap_or(1)
                .max(1);
            counters.insert((id.to_owned(), engine.to_owned()), (ns, capacity));
        }
    }
    counters
}
pub(super) struct Sampler {
    at: Instant,
    ticks: u64,
    bytes: u64,
    frames: u64,
    samples: u64,
    capture: u64,
    encode: u64,
    gpu: HashMap<(String, String), (u64, u64)>,
}
impl Sampler {
    pub fn new() -> Self {
        Self {
            at: Instant::now(),
            ticks: fs::read_to_string("/proc/self/stat")
                .ok()
                .and_then(|v| cpu_ticks(&v))
                .unwrap_or(0),
            bytes: 0,
            frames: 0,
            samples: 0,
            capture: 0,
            encode: 0,
            gpu: gpu_counters(),
        }
    }
    pub fn sample(&mut self, shared: &Shared) -> serde_json::Value {
        let elapsed = self.at.elapsed().as_secs_f64().max(0.001);
        let ticks = fs::read_to_string("/proc/self/stat")
            .ok()
            .and_then(|v| cpu_ticks(&v))
            .unwrap_or(self.ticks);
        let hz = unsafe { libc::sysconf(libc::_SC_CLK_TCK) }.max(1) as f64;
        let page = unsafe { libc::sysconf(libc::_SC_PAGESIZE) }.max(1) as f64;
        let rss = fs::read_to_string("/proc/self/statm")
            .ok()
            .and_then(|t| t.split_whitespace().nth(1)?.parse::<u64>().ok())
            .unwrap_or(0) as f64
            * page
            / 1048576.0;
        let available = fs::read_to_string("/proc/meminfo")
            .ok()
            .and_then(|t| {
                t.lines().find_map(|l| {
                    l.strip_prefix("MemAvailable:")
                        .and_then(|v| v.split_whitespace().next()?.parse::<u64>().ok())
                })
            })
            .map(|kb| kb as f64 / 1024.0);
        let gpu = gpu_counters();
        let mut engines: HashMap<String, (u64, u64)> = HashMap::new();
        for (key, (ns, capacity)) in &gpu {
            if let Some((before, _)) = self.gpu.get(key) {
                let e = engines.entry(key.1.clone()).or_insert((0, *capacity));
                e.0 = e.0.saturating_add(ns.saturating_sub(*before));
                e.1 = e.1.max(*capacity);
            }
        }
        let gpu_pct = engines
            .values()
            .map(|(ns, cap)| (*ns as f64 / (elapsed * 1e9 * *cap as f64) * 100.0).clamp(0.0, 100.0))
            .reduce(f64::max);
        let bytes = shared.bytes.load(Ordering::Relaxed);
        let frames = shared.encoded_frames.load(Ordering::Relaxed);
        let samples = shared.view_samples.load(Ordering::Relaxed);
        let capture = shared.view_capture_ns.load(Ordering::Relaxed);
        let encode = shared.view_encode_ns.load(Ordering::Relaxed);
        let count = samples.saturating_sub(self.samples);
        let cap_ms = if count > 0 {
            Some(capture.saturating_sub(self.capture) as f64 / count as f64 / 1e6)
        } else {
            None
        };
        let enc_ms = if count > 0 {
            Some(encode.saturating_sub(self.encode) as f64 / count as f64 / 1e6)
        } else {
            None
        };
        let value = serde_json::json!({"type":"resourceStats","cpuPct":ticks.saturating_sub(self.ticks) as f64/hz/elapsed*100.0,"rssMiB":rss,"availableMiB":available,"gpuPct":gpu_pct,"fps":frames.saturating_sub(self.frames) as f64/elapsed,"mbps":bytes.saturating_sub(self.bytes) as f64*8.0/elapsed/1e6,"captureMs":cap_ms,"encodeMs":enc_ms,"quality":shared.encoder_quality.load(Ordering::Relaxed),"bitrate":shared.encoder_bitrate.load(Ordering::Relaxed)});
        self.at = Instant::now();
        self.ticks = ticks;
        self.bytes = bytes;
        self.frames = frames;
        self.samples = samples;
        self.capture = capture;
        self.encode = encode;
        self.gpu = gpu;
        value
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn proc_name_can_contain_spaces_and_parentheses() {
        let text = "12 (encoder (worker)) S 1 2 3 4 5 6 7 8 9 10 21 34 0";
        assert_eq!(cpu_ticks(text), Some(55));
    }
}
