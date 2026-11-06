use std::sync::OnceLock;

static EPOCH_NS: OnceLock<u64> = OnceLock::new();

pub fn monotonic_ns() -> u64 {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    unsafe {
        libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts);
    }
    ts.tv_sec as u64 * 1_000_000_000 + ts.tv_nsec as u64
}

/// Process-wide monotonic epoch so every monitor's RTP clock starts together.
pub fn epoch_ns() -> u64 {
    *EPOCH_NS.get_or_init(monotonic_ns)
}
