package com.electromagnetism.simulation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

/** Immutable domain contracts. JNI encoding is implemented by NativeWireCodec. All physical values use SI. */
public final class SimulationProtocol {
    public static final int MAX_WAVEFORM_SAMPLES = 32 * 1024;
    private SimulationProtocol() {}

    public enum Status { UNAVAILABLE, UNSUPPORTED, READY, PARTIAL, REJECTED, CLOSED }
    public record Diagnostic(Status status, String reason) {
        public Diagnostic { Objects.requireNonNull(status); Objects.requireNonNull(reason); }
    }
    public record DeviceId(UUID instance, long generation) {
        public DeviceId { Objects.requireNonNull(instance); nonnegative(generation); }
    }
    public record PortId(DeviceId device, int terminal) {
        public PortId { Objects.requireNonNull(device); nonnegative(terminal); }
    }
    public record Revision(UUID session, long topology, long parameters, long sequence) {
        public Revision { Objects.requireNonNull(session); nonnegative(topology); nonnegative(parameters); nonnegative(sequence); }
    }
    public record DeviceInput(DeviceId id, String model, List<PortId> ports, Map<String, Double> parameters) {
        public DeviceInput {
            Objects.requireNonNull(id); Objects.requireNonNull(model);
            if (model.isBlank()) throw new IllegalArgumentException("Empty model identifier");
            ports = List.copyOf(ports); parameters = Map.copyOf(parameters);
            if (ports.stream().distinct().count() != ports.size() || ports.stream().anyMatch(p -> !p.device().equals(id)))
                throw new IllegalArgumentException("Duplicate or foreign device port");
            parameters.values().forEach(SimulationProtocol::finite);
        }
    }
    /** Each conductive branch has its own identity, including parallel branches. */
    public record Branch(UUID id, PortId positive, PortId negative, String model, Map<String, Double> parameters, List<PortId> additionalPorts) {
        public Branch {
            Objects.requireNonNull(id); Objects.requireNonNull(positive); Objects.requireNonNull(negative);
            Objects.requireNonNull(model); parameters = Map.copyOf(parameters);
            additionalPorts = List.copyOf(additionalPorts);
            parameters.values().forEach(SimulationProtocol::finite);
        }
        public Branch(UUID id, PortId positive, PortId negative, String model, Map<String, Double> parameters) {
            this(id, positive, negative, model, parameters, List.of());
        }
    }
    public enum RemovalDisposition { REJECT_ENERGIZED, EXPORT_STORED_ENERGY }
    public record Topology(Revision revision, List<DeviceInput> devices, List<Branch> branches, RemovalDisposition removalDisposition) {
        public Topology {
            Objects.requireNonNull(removalDisposition);
            Objects.requireNonNull(revision); devices = List.copyOf(devices); branches = List.copyOf(branches);
            if (devices.stream().map(DeviceInput::id).distinct().count() != devices.size()
                    || branches.stream().map(Branch::id).distinct().count() != branches.size())
                throw new IllegalArgumentException("Duplicate device/branch identity");
            var ports = devices.stream().flatMap(d -> d.ports().stream()).collect(java.util.stream.Collectors.toSet());
            if (branches.stream().anyMatch(b -> !ports.contains(b.positive()) || !ports.contains(b.negative())
                    || !ports.containsAll(b.additionalPorts())))
                throw new IllegalArgumentException("Dangling branch port");
        }
        public Topology(Revision revision, List<DeviceInput> devices, List<Branch> branches) {
            this(revision, devices, branches, RemovalDisposition.REJECT_ENERGIZED);
        }
    }
    public record BranchParameters(UUID id, String model, Map<String, Double> parameters) {
        public BranchParameters {
            Objects.requireNonNull(id); Objects.requireNonNull(model); parameters = Map.copyOf(parameters);
            parameters.values().forEach(SimulationProtocol::finite);
        }
    }
    public record Parameters(Revision revision, List<DeviceInput> devices, List<BranchParameters> branches) {
        public Parameters { Objects.requireNonNull(revision); devices = List.copyOf(devices); branches = List.copyOf(branches); }
        public Parameters(Revision revision, List<DeviceInput> devices) { this(revision, devices, List.of()); }
    }
    public record Repartition(List<Revision> sources,List<Partition> targets) {
        public Repartition {sources=List.copyOf(sources);targets=List.copyOf(targets);
            if(sources.isEmpty() || sources.size()>64 || targets.isEmpty() || targets.size()>64)throw new IllegalArgumentException("Invalid repartition size");}
    }
    public record Partition(Revision revision,List<UUID> branches) {
        public Partition {Objects.requireNonNull(revision);branches=List.copyOf(branches);}
    }
    public record Advance(Revision revision, double acceptedStart, double requestedEnd, int maxSteps, long budgetNanos) {
        public Advance {
            Objects.requireNonNull(revision); interval(acceptedStart, requestedEnd);
            if (maxSteps <= 0 || budgetNanos <= 0) throw new IllegalArgumentException("Advance must be bounded");
        }
    }
    public record AdvanceWork(int attemptedSteps,int acceptedSteps,int rejectedSteps,int discardedSteps,int localizationTrials) {
        public AdvanceWork {
            if(attemptedSteps<0 || acceptedSteps<0 || rejectedSteps<0 || discardedSteps<0 || localizationTrials<0
                    || (long)acceptedSteps+rejectedSteps+discardedSteps!=attemptedSteps)throw new IllegalArgumentException("Invalid native work counts");
        }
    }
    public record Accepted(Revision revision, double start, double end, double requestedEnd, Diagnostic diagnostic,AdvanceWork work) {
        public Accepted(Revision revision,double start,double end,double requestedEnd,Diagnostic diagnostic) {
            this(revision,start,end,requestedEnd,diagnostic,new AdvanceWork(0,0,0,0,0));
        }
        public Accepted {
            Objects.requireNonNull(revision); Objects.requireNonNull(diagnostic);Objects.requireNonNull(work);
            interval(start, end); interval(end, requestedEnd);
            if (diagnostic.status() == Status.READY && end != requestedEnd)
                throw new IllegalArgumentException("Incomplete prefix cannot be READY");
            // A later candidate can fail after earlier steps in the request were accepted.
            if (diagnostic.status() != Status.READY && diagnostic.status() != Status.PARTIAL
                    && diagnostic.status() != Status.REJECTED && end != start)
                throw new IllegalArgumentException("Unavailable result cannot advance time");
        }
    }
    /** Empty frequency/phase means not applicable or unavailable, never a fabricated zero.
     * Source phase is in radians at windowStart. Peaks are accepted integration-sample maxima.
     */
    public record Reading(UUID branch, double windowStart, double windowEnd,
                          double endVoltage, double endCurrent,
                          double meanVoltage, double rmsVoltage, double peakVoltage,
                          double meanCurrent, double rmsCurrent, double peakCurrent,
                          double activePower, double absorbedEnergy, double returnedEnergy, double dissipatedEnergy,
                          OptionalDouble frequency, OptionalDouble phase, OptionalDouble temperatureKelvin, OptionalDouble torqueNewtonMetres,
                          double totalAbsorbedEnergy,double totalReturnedEnergy,double totalDissipatedEnergy) {
        public Reading {
            Objects.requireNonNull(branch); interval(windowStart, windowEnd);
            finite(endVoltage); finite(endCurrent);
            finite(meanVoltage); positive(rmsVoltage); positive(peakVoltage);
            finite(meanCurrent); positive(rmsCurrent); positive(peakCurrent);
            finite(activePower); positive(absorbedEnergy); positive(returnedEnergy); positive(dissipatedEnergy);
            Objects.requireNonNull(frequency).ifPresent(SimulationProtocol::positive);
            Objects.requireNonNull(phase).ifPresent(SimulationProtocol::finite);
            Objects.requireNonNull(temperatureKelvin).ifPresent(SimulationProtocol::positive);
            Objects.requireNonNull(torqueNewtonMetres).ifPresent(SimulationProtocol::finite);
            positive(totalAbsorbedEnergy);positive(totalReturnedEnergy);positive(totalDissipatedEnergy);
            if(absorbedEnergy>totalAbsorbedEnergy || returnedEnergy>totalReturnedEnergy || dissipatedEnergy>totalDissipatedEnergy)
                throw new IllegalArgumentException("Window energy exceeds lifetime observation");
        }
    }
    public enum SampleQuality { VALID, RECONSTRUCTED, MISSING, UNCONVERGED, BANDWIDTH_LIMIT }
    public record WaveformSample(double time, OptionalDouble value, SampleQuality quality) {
        public WaveformSample {
            positive(time); Objects.requireNonNull(value); Objects.requireNonNull(quality);
            value.ifPresent(SimulationProtocol::finite);
            if (value.isPresent() != (quality == SampleQuality.VALID || quality == SampleQuality.RECONSTRUCTED))
                throw new IllegalArgumentException("Invalid sample cannot carry a numeric value");
        }
    }
    public record Waveform(UUID channel, PortId positive, PortId negative, List<WaveformSample> samples, long droppedSamples) {
        public Waveform {
            Objects.requireNonNull(channel); Objects.requireNonNull(positive); Objects.requireNonNull(negative);
            samples = List.copyOf(samples);
            nonnegative(droppedSamples);
            double previous = -1;
            for (var sample : samples) {
                if (sample.time() <= previous) throw new IllegalArgumentException("Waveform timestamps must increase");
                previous = sample.time();
            }
        }
        public Waveform(UUID channel, PortId positive, PortId negative, List<WaveformSample> samples) {
            this(channel, positive, negative, samples, 0);
        }
    }
    public enum EventAction { DAMAGE, RELEASE, ENERGIZE }
    public record ProtectionEvent(UUID id, UUID branch, double time, double temperatureKelvin, double storedJoules,EventAction action) {
        public ProtectionEvent(UUID id,UUID branch,double time,double temperatureKelvin,double storedJoules){this(id,branch,time,temperatureKelvin,storedJoules,EventAction.DAMAGE);}
        public ProtectionEvent { Objects.requireNonNull(id);Objects.requireNonNull(branch);Objects.requireNonNull(action);positive(time);positive(temperatureKelvin);positive(storedJoules);if(action!=EventAction.DAMAGE && temperatureKelvin!=0)throw new IllegalArgumentException("Switch event must not publish a temperature"); }
    }
    public record Observations(Accepted accepted, List<Reading> readings, List<Waveform> waveforms, List<ProtectionEvent> events) {
        public Observations(Accepted accepted, List<Reading> readings, List<Waveform> waveforms) {this(accepted,readings,waveforms,List.of());}
        public Observations {
            Objects.requireNonNull(accepted); readings = List.copyOf(readings); waveforms = List.copyOf(waveforms); events=List.copyOf(events);
            if (accepted.diagnostic().status() != Status.READY && accepted.diagnostic().status() != Status.PARTIAL
                    && (!readings.isEmpty() || !waveforms.isEmpty() || !events.isEmpty()))
                throw new IllegalArgumentException("Unavailable/rejected result cannot publish readings");
        }
    }
    public record WaveformChannel(UUID channel, PortId positive, PortId negative) {
        public WaveformChannel { Objects.requireNonNull(channel); Objects.requireNonNull(positive); Objects.requireNonNull(negative); }
    }
    /** Bandwidth is the -3 dB corner of four cascaded low-pass poles; samples include filter delay. */
    public record WaveformSubscription(Revision revision, List<WaveformChannel> channels,
                                       double samplesPerSecond, double bandwidthHertz, int maxSamples) {
        public WaveformSubscription {
            Objects.requireNonNull(revision); channels = List.copyOf(channels); positive(samplesPerSecond); positive(bandwidthHertz);
            if (samplesPerSecond < 0.001 || samplesPerSecond > 192000 || bandwidthHertz <= 0
                    || bandwidthHertz > samplesPerSecond / 8 || maxSamples <= 0
                    || channels.size() > 32 || (long) channels.size() * maxSamples > MAX_WAVEFORM_SAMPLES
                    || channels.stream().map(WaveformChannel::channel).distinct().count() != channels.size())
                throw new IllegalArgumentException("Invalid waveform bandwidth, identity or resource limits");
        }
    }
    public record Snapshot(Revision revision, String modVersion, double acceptedTime, byte[] payload) {
        public Snapshot {
            Objects.requireNonNull(revision); Objects.requireNonNull(modVersion); positive(acceptedTime);
            if (modVersion.isBlank()) throw new IllegalArgumentException("Missing mod metadata");
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }
    private static void interval(double start, double end) {
        positive(start); positive(end);
        if (end < start) throw new IllegalArgumentException("Reversed time interval");
    }
    private static void nonnegative(long value) { if (value < 0) throw new IllegalArgumentException("Negative identity/version"); }
    private static void positive(double value) { finite(value); if (value < 0) throw new IllegalArgumentException("Negative magnitude/time"); }
    private static void finite(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite value"); }
    public record IntervalValue(UUID branch, DeviceId owner, double[] samples, double audioStart, Double radiansPerSecond) {
        public IntervalValue {
            Objects.requireNonNull(branch); Objects.requireNonNull(owner);
            if ((samples == null) == (radiansPerSecond == null)) throw new IllegalArgumentException("Exactly one interval value required");
            if (samples != null) {
                samples=samples.clone();
                if(samples.length!=800 || !Double.isFinite(audioStart) || audioStart<0)throw new IllegalArgumentException("Invalid audio interval");
                for(double v:samples)if(!Double.isFinite(v)||Math.abs(v)>1)throw new IllegalArgumentException("Invalid audio sample");
            } else if(!Double.isFinite(radiansPerSecond))throw new IllegalArgumentException("Invalid shaft speed");
        }
        @Override public double[] samples(){return samples==null?null:samples.clone();}
    }
    public record IntervalInputs(Revision revision,double start,double end,List<IntervalValue> inputs) {
        public IntervalInputs {
            Objects.requireNonNull(revision);inputs=List.copyOf(inputs);
            if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||inputs.size()>4096)throw new IllegalArgumentException("Invalid input interval");
        }
    }
}
