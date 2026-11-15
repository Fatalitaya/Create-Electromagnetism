use em_protocol::wire::{self, Writer};

#[test]
fn bounded_arbitrary_bytes_never_panic_and_truncated_headers_reject() {
    let mut w = Writer::default();
    w.u32(wire::MAGIC);
    w.string(em_protocol::MOD_VERSION);
    w.u32(0);
    w.u64(0);
    assert!(wire::decode(&w.0).is_ok());
    for length in 0..w.0.len() {
        assert!(wire::decode(&w.0[..length]).is_err());
    }
    w.0.push(0);
    assert!(wire::decode(&w.0).is_err());
    let mut seed = 1234567_u64;
    for length in 0..512 {
        let bytes: Vec<_> = (0..length)
            .map(|_| {
                seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
                (seed >> 32) as u8
            })
            .collect();
        assert!(wire::decode(&bytes).is_err());
    }
    assert!(wire::decode(&vec![0; wire::MAX_BYTES + 1]).is_err());
}
