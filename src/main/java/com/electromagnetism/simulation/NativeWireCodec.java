package com.electromagnetism.simulation;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.electromagnetism.simulation.SimulationProtocol.*;

/** Bounded little-endian encoding. Compatibility comes only from mod metadata. */
final class NativeWireCodec {
    static final int MAGIC = 0x424e4d45;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_BRANCHES = 4096;
    private NativeWireCodec() {}

    static SimulationFailure rejected(String reason) { return new SimulationFailure(Status.REJECTED, reason); }

    static Writer request(String metadata, int operation, long handle) {
        return new Writer().integer(MAGIC).string(metadata).integer(operation).longInteger(handle);
    }

    static Reader exchange(Writer request) {
        final byte[] response;
        try { response = NativeLinearBridge.exchange(request.bytes()); }
        catch (LinkageError | RuntimeException failure) {
            throw new SimulationFailure(Status.UNAVAILABLE, "Native exchange failed: " + failure);
        }
        Reader r = new Reader(response);
        if (r.integer() != MAGIC) throw rejected("Native response marker mismatch");
        int status = r.integer();
        String reason = r.string();
        if (status != 0) {
            r.finish();
            throw new SimulationFailure(status == 1 ? Status.REJECTED : Status.UNAVAILABLE, reason);
        }
        return r;
    }

    static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int limit;
        Writer() { this(MAX_BYTES); }
        Writer(int limit) { this.limit=limit; }
        Writer raw(byte[] bytes) {
            if (bytes.length > limit - output.size()) throw rejected("Batch exceeds byte limit");
            output.writeBytes(bytes); return this;
        }
        Writer integer(int value) { return raw(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()); }
        Writer longInteger(long value) { return raw(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()); }
        Writer number(double value) {
            if (!Double.isFinite(value)) throw rejected("Non-finite numeric field");
            return longInteger(Double.doubleToRawLongBits(value));
        }
        Writer uuid(UUID id) { return longInteger(id.getMostSignificantBits()).longInteger(id.getLeastSignificantBits()); }
        Writer revision(Revision r) { return uuid(r.session()).longInteger(r.topology()).longInteger(r.parameters()).longInteger(r.sequence()); }
        Writer port(PortId p) { return uuid(p.device().instance()).longInteger(p.device().generation()).integer(p.terminal()); }
        Writer string(String value) { return blob(value.getBytes(StandardCharsets.UTF_8)); }
        Writer optional(OptionalDouble value) { integer(value.isPresent()?1:0);if(value.isPresent())number(value.getAsDouble());return this; }
        Writer blob(byte[] bytes) { return integer(bytes.length).raw(bytes); }
        byte[] bytes() { return output.toByteArray(); }
    }

    static final class Reader {
        private final ByteBuffer input;
        Reader(byte[] bytes) {
            this(bytes,MAX_BYTES);
        }
        Reader(byte[] bytes,int limit) {
            if (bytes == null || bytes.length > limit) throw rejected("Invalid native response length");
            input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        private void require(int count) {
            if (count < 0 || count > input.remaining()) throw rejected("Truncated native response");
        }
        int integer() { require(4); return input.getInt(); }
        long longInteger() { require(8); return input.getLong(); }
        double number() {
            double value = Double.longBitsToDouble(longInteger());
            if (!Double.isFinite(value)) throw rejected("Non-finite native response");
            return value;
        }
        int count(int max) {
            int count = integer();
            if (count < 0 || count > max) throw rejected("Native count exceeds resource limit");
            return count;
        }
        UUID uuid() { return new UUID(longInteger(), longInteger()); }
        PortId port() { return new PortId(new DeviceId(uuid(),longInteger()),integer()); }
        Revision revision() { return new Revision(uuid(), longInteger(), longInteger(), longInteger()); }
        byte[] blob() {
            int length = count(MAX_BYTES); require(length);
            byte[] bytes = new byte[length]; input.get(bytes); return bytes;
        }
        String string() { return new String(blob(), StandardCharsets.UTF_8); }
        OptionalDouble optional() {
            return switch (integer()) { case 0 -> OptionalDouble.empty(); case 1 -> OptionalDouble.of(number());
                default -> throw rejected("Invalid optional numeric field"); };
        }
        void finish() { if (input.hasRemaining()) throw rejected("Trailing native response bytes"); }
    }

    static Accepted accepted(Reader r) {
        Revision revision = r.revision();
        double start = r.number(), end = r.number(), requestedEnd = r.number();
        Status status = switch (r.integer()) { case 0 -> Status.READY; case 1 -> Status.PARTIAL;
            default -> throw rejected("Invalid native acceptance status"); };
        var diagnostic=new Diagnostic(status,r.string());
        var work=new AdvanceWork(r.integer(),r.integer(),r.integer(),r.integer(),r.integer());
        return new Accepted(revision,start,end,requestedEnd,diagnostic,work);
    }

    static void state(Reader r, double expectedTime) {
        if (r.number() != expectedTime) throw rejected("Native accepted time mismatch");
        for (int i=0;i<7;i++) r.number();
        int count = r.count(MAX_BRANCHES);
        var ids = new HashSet<UUID>();
        for (int i=0;i<count;i++) {
            if (!ids.add(r.uuid())) throw rejected("Duplicate native branch identity");
            for (int j=0;j<5;j++) r.number();
        }
        r.number(); // Signed parameter-boundary work.
        if (r.number()<0) throw rejected("Negative removed boundary energy");
        r.finish();
    }

    static Observations observations(Reader r) {
        Accepted accepted = accepted(r);
        int count = r.count(MAX_BRANCHES);
        var readings = new ArrayList<Reading>(count);
        var ids = new HashSet<UUID>();
        for (int i=0;i<count;i++) {
            UUID id = r.uuid();
            if (!ids.add(id)) throw rejected("Duplicate observation branch identity");
            Reading reading = new Reading(id, r.number(), r.number(), r.number(), r.number(), r.number(),
                    r.number(), r.number(), r.number(), r.number(), r.number(), r.number(), r.number(), r.optional(), r.optional(), r.optional(),r.optional(),r.number(),r.number(),r.number());
            if (reading.windowStart() != accepted.start() || reading.windowEnd() != accepted.end())
                throw rejected("Reading lies outside accepted window");
            readings.add(reading);
        }
        int channels=r.count(32), remaining=MAX_WAVEFORM_SAMPLES;
        var waveforms=new ArrayList<Waveform>(channels);
        var channelIds=new HashSet<UUID>();
        for (int i=0;i<channels;i++) {
            UUID id=r.uuid();
            if (!channelIds.add(id)) throw rejected("Duplicate waveform channel identity");
            PortId positive=r.port(), negative=r.port();
            long dropped=r.longInteger();
            int samples=r.count(remaining); remaining-=samples;
            var values=new ArrayList<WaveformSample>(samples);
            for (int j=0;j<samples;j++) {
                double time=r.number();
                int quality=r.count(SampleQuality.values().length-1);
                if (time<=accepted.start() || time>accepted.end()) throw rejected("Waveform sample outside accepted interval");
                values.add(new WaveformSample(time,r.optional(),SampleQuality.values()[quality]));
            }
            waveforms.add(new Waveform(id,positive,negative,values,dropped));
        }
        int eventCount=r.count(MAX_BRANCHES);var events=new ArrayList<ProtectionEvent>(eventCount);var eventIds=new HashSet<UUID>();
        for (int i=0;i<eventCount;i++) {
            var eventId=r.uuid();var branchId=r.uuid();double time=r.number(),temperature=r.number(),stored=r.number();int action=r.integer();
            if(action<0 || action>=EventAction.values().length)throw rejected("Unknown native event action");
            var event=new ProtectionEvent(eventId,branchId,time,temperature,stored,EventAction.values()[action]);
            if (!eventIds.add(event.id()) || event.time()!=accepted.end()) throw rejected("Invalid protection event identity/boundary");
            events.add(event);
        }
        r.finish();
        return new Observations(accepted, readings, waveforms, events);
    }

    static byte[] encodeObservations(Observations observations) {
        var a=observations.accepted();var work=a.work();var w=new Writer();
        w.revision(a.revision()).number(a.start()).number(a.end()).number(a.requestedEnd())
                .integer(a.diagnostic().status()==Status.READY?0:1).string(a.diagnostic().reason())
                .integer(work.attemptedSteps()).integer(work.acceptedSteps()).integer(work.rejectedSteps())
                .integer(work.discardedSteps()).integer(work.localizationTrials());
        w.integer(observations.readings().size());for(var r:observations.readings()) {
            w.uuid(r.branch()).number(r.windowStart()).number(r.windowEnd()).number(r.meanVoltage()).number(r.rmsVoltage()).number(r.peakVoltage())
                    .number(r.meanCurrent()).number(r.rmsCurrent()).number(r.peakCurrent()).number(r.activePower())
                    .number(r.absorbedEnergy()).number(r.returnedEnergy()).number(r.dissipatedEnergy())
                    .optional(r.frequency()).optional(r.phase()).optional(r.temperatureKelvin()).optional(r.torqueNewtonMetres())
                    .number(r.totalAbsorbedEnergy()).number(r.totalReturnedEnergy()).number(r.totalDissipatedEnergy());
        }
        w.integer(observations.waveforms().size());for(var wave:observations.waveforms()) {
            w.uuid(wave.channel()).port(wave.positive()).port(wave.negative()).longInteger(wave.droppedSamples()).integer(wave.samples().size());
            for(var sample:wave.samples())w.number(sample.time()).integer(sample.quality().ordinal()).optional(sample.value());
        }
        w.integer(observations.events().size());for(var event:observations.events())
            w.uuid(event.id()).uuid(event.branch()).number(event.time()).number(event.temperatureKelvin()).number(event.storedJoules()).integer(event.action().ordinal());
        return w.bytes();
    }

    record Model(int tag, String... parameters) {}
    static Model model(String name) {
        return switch (name) {
            case "resistor" -> new Model(1, "resistanceOhms");
            case "field_ac_shaft" -> new Model(28,"newtonMetresPerAmpereSquared","radiansPerSecond","polePairs","phaseRadians");
            case "field_shaft" -> new Model(27,"newtonMetresPerAmpereSquared","radiansPerSecond");
            case "radio_transmitter" -> radioModel();
            case "carrier_mixer" -> new Model(25,"frequencyHz","phaseRadians","ratio");
            case "audio_source" -> audioModel();
            case "lightning_impulse" -> new Model(23,"startSeconds","peakAmperes","riseSeconds","fallSeconds","channelOhms","continuingAmperes","durationSeconds");
            case "voltage_regulator" -> new Model(22,"targetVolts","responseSeconds","minimumRatio","maximumRatio");
            case "air_gap" -> new Model(21,"closed","contactOhms","leakageSiemens","hotSiemens","breakdownVolts","ignitionRiseKelvin","heatCapacityJoulesPerKelvin","conductanceWattsPerKelvin","ambientKelvin");
            case "tapped_wire" -> tappedModel();
            case "relay" -> new Model(19,"coilOhms","closedOhms","openOhms","pickupVolts","dropoutVolts","inverted","initialActive");
            case "ac_shaft" -> new Model(18,"newtonMetresPerAmpere","radiansPerSecond","polePairs","phaseRadians");
            case "regulated_converter" -> new Model(17,"outputVolts","kneeVolts","efficiency");
            case "battery_contact" -> new Model(16,"resistanceOhms","fullVolts","taperVolts");
            case "shaft_boundary" -> new Model(15,"newtonMetresPerAmpere","radiansPerSecond");
            case "wattmeter_contact" -> new Model(14,"resistanceOhms");
            case "dc_voltage_source" -> new Model(2, "voltageVolts");
            case "capacitor" -> new Model(3, "capacitanceFarads");
            case "inductor" -> new Model(4, "inductanceHenries");
            case "diode" -> new Model(5, "saturationAmperes", "thermalVolts", "junctionFarads");
            case "sine_voltage_source" -> new Model(6, "offsetVolts", "peakVolts", "frequencyHz", "phaseRadians");
            case "thermal_fuse" -> new Model(13,"ambientOhms","coefficientPerKelvin","heatCapacityJoulesPerKelvin","conductanceWattsPerKelvin","ambientKelvin","tripKelvin");
            case "thermal_resistor" -> new Model(12,"ambientOhms","coefficientPerKelvin","heatCapacityJoulesPerKelvin","conductanceWattsPerKelvin","ambientKelvin");
            case "coupled_windings" -> new Model(11,"primaryHenries","secondaryHenries","mutualHenries","primaryOhms","secondaryOhms");
            case "n_mosfet", "p_mosfet" -> new Model(name.equals("n_mosfet") ? 9 : 10,
                    "betaAmperesPerVoltSquared", "thresholdVolts", "saturationAmperes", "thermalVolts",
                    "gateSourceFarads", "gateDrainFarads");
            case "npn_bipolar", "pnp_bipolar" -> new Model(name.equals("npn_bipolar") ? 7 : 8,
                    "saturationAmperes", "thermalVolts", "forwardBeta", "reverseBeta", "baseEmitterFarads", "baseCollectorFarads");
            default -> throw new SimulationFailure(Status.UNSUPPORTED, "Unknown native constitutive model: " + name);
        };
    }

    private static Model radioModel(){
        var names=new ArrayList<String>(List.of("frequencyHz","phaseRadians"));names.addAll(Arrays.asList(audioModel().parameters()));return new Model(26,names.toArray(String[]::new));
    }
    private static Model audioModel() {
        var names=new ArrayList<String>(List.of("startSeconds","sampleSeconds","peakVolts","resistanceOhms"));
        for(int i=0;i<800;i++)names.add("sample"+i);return new Model(24,names.toArray(String[]::new));
    }
    private static Model tappedModel() {
        var names=new ArrayList<String>(List.of("ambientOhms","coefficientPerKelvin","heatCapacityJoulesPerKelvin","conductanceWattsPerKelvin","ambientKelvin","tripKelvin","portCount"));
        for(int i=0;i<32;i++)names.add("position"+i);
        return new Model(20,names.toArray(String[]::new));
    }
    private static int portCount(String name,Map<String,Double> values) {
        if(name.equals("tapped_wire")){double count=values.getOrDefault("portCount",Double.NaN);if(!Double.isFinite(count)||count!=Math.rint(count)||count<2||count>32)throw rejected("Invalid tapped conductor ports");return (int)count;}
        return portCount(name);
    }
    private static int portCount(String name) { int tag=model(name).tag(); return (tag==11 || tag==17 || tag==19 || tag==25 || tag==26 || tag==27 || tag==28)?4:(tag>=7 && tag<=10 || tag==14 || tag==16 || tag==22)?3:2; }
    static double[] parameters(String name, Map<String, Double> values) {
        Model model = model(name);
        if (!values.keySet().equals(Set.of(model.parameters()))) throw rejected("Unexpected/missing SI parameters for " + name);
        return Arrays.stream(model.parameters()).mapToDouble(values::get).toArray();
    }

    static UUID deviceBranchId(DeviceId id) {
        return UUID.nameUUIDFromBytes(("electromagnetism/device/" + id.instance() + "/" + id.generation()).getBytes(StandardCharsets.UTF_8));
    }

    static Branch deviceBranch(DeviceInput device) {
        int portCount=portCount(device.model(),device.parameters());
        if (device.ports().size() != portCount) throw rejected("Constitutive model requires " + portCount + " ordered ports");
        return new Branch(deviceBranchId(device.id()), device.ports().get(0), device.ports().get(1), device.model(), device.parameters(),
                device.ports().subList(2,device.ports().size()));
    }

    static List<Branch> compile(Topology topology) {
        var branches = new ArrayList<>(topology.branches());
        for (DeviceInput device : topology.devices()) {
            if (device.model().equals("ports")) {
                if (!device.parameters().isEmpty()) throw rejected("Port declarations cannot contain constitutive parameters");
            } else branches.add(deviceBranch(device));
        }
        if (branches.size() > MAX_BRANCHES) throw rejected("Circuit branch resource limit exceeded");
        var ids = new HashSet<UUID>();
        for (Branch branch : branches) {
            if (!ids.add(branch.id())) throw rejected("Duplicate compiled branch identity");
            parameters(branch.model(), branch.parameters());
            int portCount=portCount(branch.model(),branch.parameters());
            if (branch.additionalPorts().size()+2!=portCount) throw rejected("Branch constitutive port count mismatch");
        }
        return branches;
    }

    static void branch(Writer w, Branch branch) {
        w.uuid(branch.id()).port(branch.positive()).port(branch.negative());
        parameters(w, branch.id(), branch.model(), branch.parameters(), false);
        branch.additionalPorts().forEach(w::port);
    }

    static void parameters(Writer w, UUID id, String name, Map<String, Double> values, boolean includeId) {
        if (includeId) w.uuid(id);
        w.integer(model(name).tag());
        for (double value : parameters(name, values)) w.number(value);
    }
}
