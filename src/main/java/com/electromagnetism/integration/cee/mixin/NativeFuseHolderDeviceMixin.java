package com.electromagnetism.integration.cee.mixin;

import com.electromagnetism.integration.cee.nativebridge.*;
import com.electromagnetism.simulation.SimulationProtocol.Reading;
import com.george_vi.electroenergetics.content.fuse.FuseHolderDevice;
import com.george_vi.electroenergetics.content.fuse.fuse_held.*;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

@Mixin(value=FuseHolderDevice.class,remap=false)
public abstract class NativeFuseHolderDeviceMixin implements NativeFuseHolder {
    @Shadow FuseHoldable firstFuse;
    @Shadow FuseHoldable secondFuse;
    @Shadow CompoundTag firstData;
    @Shadow CompoundTag secondData;
    @Unique private String electromagnetism$invalid="";
    @Inject(method="read",at=@At("HEAD"))
    private void electromagnetism$read(CompoundTag tag,CallbackInfo ci) {
        firstFuse=secondFuse=null;firstData=secondData=null;electromagnetism$invalid="";
        for(String key:List.of("FirstID","SecondID"))if(tag.contains(key)) {
            var id=net.minecraft.resources.ResourceLocation.tryParse(tag.getString(key));
            if(id==null || !FuseHoldable.ALL.containsKey(id))electromagnetism$invalid="Unknown fuse holder attachment: "+tag.getString(key);
        }
    }
    @Override public void electromagnetism$replaceSlotCommands(){electromagnetism$invalid="";}
    @Override public List<Slot> electromagnetism$slots() {
        if(!electromagnetism$invalid.isEmpty())throw new IllegalArgumentException(electromagnetism$invalid);
        var result=new ArrayList<Slot>();
        for(int slot=0;slot<2;slot++) {
            var type=slot==0?firstFuse:secondFuse;var data=slot==0?firstData:secondData;
            if(type==null)continue;
            if(data==null || !data.hasUUID("NativeInstance"))throw new IllegalArgumentException("Fuse holder attachment has missing/invalid identity");
            String kind=type instanceof FuseHeldFuse?"fuse":type instanceof FuseHeldSwitch?"switch":type instanceof FuseHeldIndicatorBulb?"indicator":type instanceof FuseHoldable.CopperConductor?"copper":"unknown";
            if(kind.equals("unknown"))throw new IllegalArgumentException("Missing fuse holder attachment adapter: "+type.getID());
            result.add(new Slot(data.getUUID("NativeInstance"),kind,slot==0?1:0,slot==0?3:2,data.getBoolean("Broken"),data.getBoolean("Closed"),data.getDouble("SetAmperage")));
        }
        if(result.size()==2 && result.getFirst().identity().equals(result.getLast().identity()))throw new IllegalArgumentException("Duplicate fuse holder attachment identity");
        return List.copyOf(result);
    }
    @Unique private void electromagnetism$publish() {
        var device=(FuseHolderDevice)(Object)this;device.deviceSD.setDirty();
        if(device.level.getBlockEntity(device.pos) instanceof NativeFuseHolderDisplay display)display.electromagnetism$slots(firstFuse,firstData,secondFuse,secondData);
    }
    @Override public void electromagnetism$slotReading(UUID identity,Reading reading) {
        for(int slot=0;slot<2;slot++) {
            var type=slot==0?firstFuse:secondFuse;var data=slot==0?firstData:secondData;
            if(!(type instanceof FuseHeldIndicatorBulb) || data==null || !data.hasUUID("NativeInstance") || !data.getUUID("NativeInstance").equals(identity))continue;
            float light=(float)Math.clamp(reading.rmsVoltage()/70,0,1);
            boolean changed=!data.getBoolean("NativeAvailable") || Math.abs(data.getFloat("Light")-light)>0.01;
            data.putFloat("Light",light);data.putBoolean("NativeAvailable",true);
            if(changed)electromagnetism$publish();
        }
    }
    @Override public void electromagnetism$slotsUnavailable() {
        boolean changed=false;
        for(var data:Arrays.asList(firstData,secondData))if(data!=null && (data.getBoolean("NativeAvailable") || data.getFloat("Light")!=0)) {
            data.putBoolean("NativeAvailable",false);data.putFloat("Light",0);changed=true;
        }
        if(changed)electromagnetism$publish();
    }
    @Override public void electromagnetism$slotBurn(UUID identity) {
        for(int slot=0;slot<2;slot++) {
            var type=slot==0?firstFuse:secondFuse;var data=slot==0?firstData:secondData;
            if(type==null || data==null || !data.hasUUID("NativeInstance") || !data.getUUID("NativeInstance").equals(identity))continue;
            if(type instanceof FuseHeldFuse)data.putBoolean("Broken",true);
            else if(slot==0){firstFuse=null;firstData=null;}else{secondFuse=null;secondData=null;}
            electromagnetism$publish();return;
        }
        throw new IllegalStateException("Protection event no longer owns fuse holder attachment "+identity);
    }
}
