package com.electromagnetism.integration.cee.mixin;
import com.george_vi.electroenergetics.content.railway_electrification.pantograph.PantographBlockEntity;
import com.george_vi.electroenergetics.content.railway_electrification.third_rail.RailContactShoeBlockEntity;
import com.george_vi.electroenergetics.foundation.nodes.AttachedNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value={PantographBlockEntity.class,RailContactShoeBlockEntity.class},remap=false)
public interface NativeContactAccess {@Accessor("attachedNode") AttachedNode electromagnetism$attachedNode();}
