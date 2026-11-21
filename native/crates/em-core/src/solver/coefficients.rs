//! Ordered terminal roles and equation parameters identify immutable numerical plans.
//! UUIDs, source histories, and accepted states are owned by the binding circuit.
use super::dc::DcBranch;
use crate::{model::element::Element, topology::Layout};
use std::{
    collections::HashMap,
    sync::{Arc, LazyLock, Mutex, Weak},
};

#[derive(Hash, Eq, PartialEq)]
pub(super) struct Key(Vec<u64>);

impl Key {
    pub(super) fn new(branches: &[DcBranch], layout: &Layout) -> Self {
        // Identical devices can use full coordinates in a small independent
        // island and compact coordinates in a coupled solve.
        let mut words = vec![layout.series.as_ref().map_or(layout.size, |p| p.kept.len()) as u64];
        let mut ports = HashMap::new();
        for (branch, rows) in branches.iter().zip(&layout.mappings) {
            // The Newton stamp for audio has the same coefficients for every sample block.
            let (kind, parameters) = match &branch.element {
                Element::Windings(model) => (11, model.parameters()),
                Element::Audio(audio) => (24, vec![audio.ohms]),
                Element::RadioTransmitter {
                    hertz,
                    phase,
                    audio,
                } => (26, vec![*hertz, *phase, audio.ohms, audio.volts]),
                element => element.parameters(),
            };
            words.extend([kind as u64, parameters.len() as u64]);
            words.extend(parameters.into_iter().map(f64::to_bits));
            for port in branch.ports() {
                let next = ports.len() as u64;
                words.push(*ports.entry(port).or_insert(next));
            }
            words.extend(rows.iter().map(|row| row.map_or(u64::MAX, |r| r as u64)));
        }
        Self(words)
    }

    pub(super) fn bias(&mut self, value: f64) {
        self.0.push(value.to_bits());
    }
}

pub(super) struct Cache<T>(LazyLock<Mutex<HashMap<Key, Weak<T>>>>);

impl<T> Cache<T> {
    pub(super) const fn new() -> Self {
        Self(LazyLock::new(|| Mutex::new(HashMap::new())))
    }

    pub(super) fn get(&self, key: &Key) -> Option<Arc<T>> {
        self.0.lock().unwrap().get(key).and_then(Weak::upgrade)
    }

    pub(super) fn publish(&self, key: Key, value: T) -> Arc<T> {
        let mut cache = self.0.lock().unwrap();
        if let Some(existing) = cache.get(&key).and_then(Weak::upgrade) {
            return existing;
        }
        let value = Arc::new(value);
        if cache.len() >= 128 {
            cache.retain(|_, item| item.strong_count() != 0);
        }
        if cache.len() < 128 {
            cache.insert(key, Arc::downgrade(&value));
        }
        value
    }
}
