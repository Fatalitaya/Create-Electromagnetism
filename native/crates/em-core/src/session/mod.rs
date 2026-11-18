//! Ordered session lifecycle, revisions, request deduplication, and bounded advance.
//! Failed candidates must not modify the accepted state or commit energy twice.

use em_protocol::contract::*;

pub mod circuit;

/// Backend seam corresponding to the Java SimulationBackend interface.
/// `linear` supplies the circuit sessions used by the JNI world coordinator.
/// This trait does not imply support for every world device or external boundary.
/// Every mutation must validate inputs before changing accepted state.
pub trait SimulationBackend {
    fn availability(&self) -> Diagnostic;
    fn create(&mut self) -> Result<Id, Diagnostic>;
    fn repartition(&mut self, request: Repartition) -> Result<(), Diagnostic>;
    fn apply_topology(&mut self, topology: Topology) -> Result<(), Diagnostic>;
    fn set_interval(&mut self, inputs: IntervalInputs) -> Result<(), Diagnostic>;
    fn set_parameters(&mut self, parameters: Parameters) -> Result<(), Diagnostic>;
    fn advance_to(&mut self, request: Advance) -> Result<Accepted, Diagnostic>;
    fn read_observations(&self, revision: Revision) -> Result<Observations, Diagnostic>;
    fn subscribe_waveforms(&mut self, subscription: WaveformSubscription)
    -> Result<(), Diagnostic>;
    fn snapshot(&self, revision: Revision) -> Result<Snapshot, Diagnostic>;
    fn restore(&mut self, session: Id, snapshot: Snapshot) -> Result<(), Diagnostic>;
    /// Called only after outstanding operations have stopped using the session.
    fn destroy(&mut self, session: Id) -> Result<(), Diagnostic>;
}
