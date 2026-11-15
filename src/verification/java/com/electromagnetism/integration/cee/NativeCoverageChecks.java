package com.electromagnetism.integration.cee;

import com.george_vi.electroenergetics.CEERegistries;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Enumerates the actual resolved registries, including attachment-only entries.
 * Missing acceptance evidence remains an explicit gap, never a completed adapter.
 */
public final class NativeCoverageChecks {
    private NativeCoverageChecks() {}
    public static String verifyDependency() throws Exception {
        var path=net.neoforged.fml.ModList.get().getModFileById("electroenergetics").getFile().getFilePath();
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(path)) {
            byte[] buffer=new byte[16384];int count;
            while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);
        }
        String hash=java.util.HexFormat.of().formatHex(digest.digest());
        if(!hash.equals("2362decf60ace66037eb2ae79f34fed57b5f152e0435164f5f91912ea837052f"))
            throw new AssertionError("CEE dependency changed; re-audit adapters and injection bytecode: "+path+" SHA256="+hash);
        return hash;
    }
    public static JsonObject write() throws Exception {
        var evidence=new HashMap<String,String>();
        bind(evidence,"electroenergetics","verifyWorkingCircuit","creative_battery","creative_resistor");
        bind(evidence,"electroenergetics","verifyTerminalAndReversingSwitch","double_connector");
        bind(evidence,"electroenergetics","verifyBundledWire","connector","insulator","bundled_wire_termination");
        bind(evidence,"electroenergetics","verifyDiodeAndEarth","diode","ground");
        bind(evidence,"electroenergetics","verifyStorageTransients","capacitor","inductor","high_voltage_capacitor");
        bind(evidence,"electroenergetics","verifyGauges","voltmeter","ammeter");
        bind(evidence,"electroenergetics","verifyFrequencyMeter","frequency_meter");
        bind(evidence,"electroenergetics","verifySynchroscope","synchroscope");
        bind(evidence,"electroenergetics","verifyEnergyMeters","energy_meter","tri_polar_energy_meter");
        bind(evidence,"electroenergetics","verifyPotentiometer","potentiometer","resistor");
        bind(evidence,"electroenergetics","verifyCeeHeater","resistive_heater");
        bind(evidence,"electroenergetics","verifyCeeBulb","bulb");
        bind(evidence,"electroenergetics","verifyIndicatorsAndBuzzer","indicator_bulb","buzzer");
        bind(evidence,"electroenergetics","verifyFuse","fuse");
        bind(evidence,"electroenergetics","verifySf6Breaker","sulfur_hexafluoride_breaker");
        bind(evidence,"electroenergetics","verifyFuseHolder","fuse_holder");
        bind(evidence,"electroenergetics","verifyTransformer","transformer");
        bind(evidence,"electroenergetics","verifyVariac","variac");
        bind(evidence,"electroenergetics","verifyCurrentTransformer","current_transformer");
        bind(evidence,"electroenergetics","verifyTransformerCore","transformer_core");
        bind(evidence,"electroenergetics","verifyConcretePole","concrete_pole");
        bind(evidence,"electroenergetics","verifyCommandedSwitches","cut_off_switch","double_switch","redstone_relay","momentary_switch");
        bind(evidence,"electromagnetism","verifyWorkingCircuit","light_fixture","factory_light","heating_coil");
        bind(evidence,"electromagnetism","verifySemiconductors","npn_transistor","pnp_transistor","n_mosfet","p_mosfet");
        bind(evidence,"electromagnetism","verifyTerminalAndReversingSwitch","reversing_switch");
        bind(evidence,"electromagnetism","verifyGeneratorScope","signal_generator","oscilloscope");
        bind(evidence,"electromagnetism","verifyBell","electric_bell");
        var root=new JsonObject();var entries=new JsonArray();int own=0,upstream=0,verified=0;
        for(var key:CEERegistries.SIMULATED_DEVICE_TYPE.keySet().stream().sorted(Comparator.comparing(Object::toString)).toList()) {
            if(key.getNamespace().equals("electromagnetism"))own++;
            if(key.getNamespace().equals("electroenergetics"))upstream++;
            var entry=new JsonObject();entry.addProperty("registryId",key.toString());
            var type=CEERegistries.SIMULATED_DEVICE_TYPE.get(key);entry.addProperty("declaredTypeId",type.id().toString());
            String test=evidence.remove(key.toString());
            entry.addProperty("status",test==null?"acceptance_gap":"world_regression_passed");
            if(test!=null){verified++;entry.addProperty("acceptance","NativeMixinChecks."+test);entry.addProperty("capture","CeeNativeWorld.captureChecked");entry.addProperty("validation","NativeWireCodec model parameter contract and Rust DeviceModel validation");entry.addProperty("consumer","CeeNativeWorld.apply and accepted Reading/waveform consumers");}
            entries.add(entry);
        }
        if(own!=16)throw new AssertionError("Own device registry changed: "+own);
        if(!evidence.isEmpty())throw new AssertionError("Acceptance references unregistered devices: "+evidence.keySet());
        root.addProperty("ceeJarSha256",verifyDependency());
        root.addProperty("registeredCeeDevices",upstream);root.addProperty("registeredOwnDevices",own);root.addProperty("devicesWithWorldRegression",verified);
        root.addProperty("complete",verified==own+upstream && CEERegistries.PANEL_ATTACHMENT_TYPE.keySet().isEmpty());root.add("devices",entries);
        root.add("panelAttachments",attachments(CEERegistries.PANEL_ATTACHMENT_TYPE.keySet()));
        var wireEntries=attachments(CEERegistries.WIRE_ATTACHMENT_TYPE.keySet());
        var passive=Set.of("electroenergetics:empty","electroenergetics:banner","electroenergetics:bunting","electroenergetics:damper");
        for(var item:wireEntries) {
            var entry=item.getAsJsonObject();
            if(passive.contains(entry.get("registryId").getAsString())) {
                entry.addProperty("status","world_regression_passed");entry.addProperty("acceptance","NativeMixinChecks.verifyWireAttachments");
                entry.addProperty("capture","NativeWireAttachments.validate");entry.addProperty("model","passive decoration: no electrical ports, dynamic state or electrical energy");
                entry.addProperty("consumer","CEE rendering and drops; native conductor identity and thermal state preserved across edits");
            } else root.addProperty("complete",false);
        }
        root.add("wireAttachments",wireEntries);
        var instruments=new JsonArray();
        for(var item:List.of(com.george_vi.electroenergetics.CEEItems.CLAMP_METER.get(),com.george_vi.electroenergetics.CEEItems.LINEMANS_STICK.get())) {
            var entry=new JsonObject();entry.addProperty("registryId",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString());
            entry.addProperty("status","world_regression_passed");entry.addProperty("acceptance","NativeMixinChecks.verifyClampMeter");
            entry.addProperty("consumer","NativeClampMeter.read: native accepted conductor RMS; server action bar, no client voltage/resistance reconstruction");
            entry.addProperty("boundary","single conductor only; cable sheath aggregate requires correlated native observations");instruments.add(entry);
        }
        root.add("instrumentItems",instruments);
        Files.writeString(Path.of("native-coverage.json"),new GsonBuilder().setPrettyPrinting().create().toJson(root));
        System.out.println("NATIVE_COVERAGE_ENUMERATED: CEE="+upstream+" own="+own+" world regressions="+verified+"; remaining entries are gaps");
        return root;
    }
    private static JsonArray attachments(Set<net.minecraft.resources.ResourceLocation> keys) {
        var result=new JsonArray();for(var key:keys.stream().sorted(Comparator.comparing(Object::toString)).toList()) {
            var entry=new JsonObject();entry.addProperty("registryId",key.toString());entry.addProperty("status","acceptance_gap");result.add(entry);
        }return result;
    }
    private static void bind(Map<String,String> entries,String namespace,String test,String... ids) {for(String id:ids)entries.put(namespace+":"+id,test);}
}
