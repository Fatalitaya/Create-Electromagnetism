package com.electromagnetism.integration.cee.nativebridge;

import com.george_vi.electroenergetics.content.cut_off_switch.MomentarySwitchBlock;
import com.george_vi.electroenergetics.content.cut_off_switch.MomentarySwitchDevice;
import com.george_vi.electroenergetics.devices.device.DevicesSavedData;
import com.george_vi.electroenergetics.CEESoundEvents;
import net.minecraft.server.level.ServerLevel;

/**
 * Capture player commands on the game clock. Button hold time is in game ticks, while Rust
 * calculates contact temperature and current.
 */
public final class NativeWorldCommands {
    private long lastTick=Long.MIN_VALUE;
    public void tick(ServerLevel level,DevicesSavedData devices) {
        long now=level.getGameTime();
        if(now==lastTick)return;
        lastTick=now;
        for(var device:java.util.List.copyOf(devices.getDevices())) {
            if(device instanceof com.george_vi.electroenergetics.content.transmission_distribution.hv_switch.HVSwitchDevice hv && level.isLoaded(device.pos)) {
                hv.progress=Math.clamp(hv.progress+(hv.isConnecting?0.01f:-0.01f),0,1);
            }
            if(device instanceof com.george_vi.electroenergetics.content.electrical_panel.ElectricalPanelDevice panel && level.isLoaded(device.pos)) {
                tickPanel(panel);
            }
            if(device instanceof com.george_vi.electroenergetics.foundation.base.TemporaryDevice && level.isLoaded(device.pos)) {
                var block=level.getBlockState(device.pos).getBlock();
                if(block instanceof com.george_vi.electroenergetics.devices.device.DeviceBlock) {
                    if(!level.getBlockTicks().hasScheduledTick(device.pos,block))level.scheduleTick(device.pos,block,1);
                } else devices.removeDevice(device.pos);
                continue;
            }
            if(!(device instanceof MomentarySwitchDevice button) || !level.isLoaded(device.pos))continue;
            if(button.closedTicks < -1 || button.closedTicks > 5)continue;
            var state=level.getBlockState(device.pos);
            if(!(state.getBlock() instanceof MomentarySwitchBlock))continue;
            boolean pressed=button.closedTicks>0;
            if(state.getValue(MomentarySwitchBlock.CLOSED)!=pressed) {
                level.setBlockAndUpdate(device.pos,state.setValue(MomentarySwitchBlock.CLOSED,pressed));
                if(!pressed)CEESoundEvents.playOnServer(level,device.pos,CEESoundEvents.CONTACT_OPEN.get(),1,1);
            }
            if(button.closedTicks>=0) {button.closedTicks--;devices.setDirty();}
        }
    }
    private static void tickPanel(com.george_vi.electroenergetics.content.electrical_panel.ElectricalPanelDevice panel) {
        for(var part:panel.attachments) {
            if(part==null)continue;
            ((NativePanelPart)part).electromagnetism$initializePart();
            if(part instanceof com.george_vi.electroenergetics.content.electrical_panel.attachments.MomentarySwitchPanelAttachment button) {
                if(button.closedTicks>=0){button.closedTicks--;button.sendData();button.updateLinkState();}
            } else if(part instanceof com.george_vi.electroenergetics.content.electrical_panel.special_interaction.AnalogLeverPanelAttachment lever) {
                if(lever.prevRedstoneSignal!=lever.redstoneSignal){lever.prevRedstoneSignal=lever.redstoneSignal;lever.updateLinkState();}
            } else if(part instanceof com.george_vi.electroenergetics.content.electrical_panel.special_interaction.SteeringWheelPanelAttachment wheel) {
                if(wheel.prevRedstoneSignal!=wheel.redstoneSignal){wheel.prevRedstoneSignal=wheel.redstoneSignal;wheel.updateLinkState();}
            } else if(part instanceof com.george_vi.electroenergetics.content.electrical_panel.attachments.AltitudeSensorPanelAttachment altitude) {
                altitude.worldHeight=dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.projectOutOfSubLevel(panel.level,part.pos.getCenter()).y;
                altitude.sendData();
            } else if(part instanceof com.george_vi.electroenergetics.content.electrical_panel.attachments.VelocitySensorPanelAttachment velocity) {
                var sub=dev.ryanhcode.sable.companion.SableCompanion.INSTANCE.getContaining(panel.level,part.pos);
                var position=part.pos.getCenter();
                velocity.value=sub==null?0:sub.logicalPose().transformPosition(position).distanceTo(sub.lastPose().transformPosition(position))*20;
                velocity.sendData();
            }
        }
    }
}
