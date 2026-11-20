//! Process requests in revision order and detect duplicate submissions. Failed trials preserve
//! accepted state and its energy ledger.

use em_protocol::contract::*;

pub mod circuit;

/// Native session interface corresponding to Java SimulationBackend. Validate each mutation before
/// changing accepted state.
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
    /// Release the session after all operations using it have stopped.
    fn destroy(&mut self, session: Id) -> Result<(), Diagnostic>;
}
