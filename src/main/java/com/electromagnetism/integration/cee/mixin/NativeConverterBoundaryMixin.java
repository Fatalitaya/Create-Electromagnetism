package com.electromagnetism.integration.cee.mixin;
import com.electromagnetism.integration.cee.nativebridge.*;
import com.george_vi.electroenergetics.content.converter.ConverterBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ConverterBlockEntity.class,remap=false)
public abstract class NativeConverterBoundaryMixin implements NativeConverterBoundary {
    @Shadow double power;
    @Unique private final NativeConverterEscrow electromagnetism$boundary=new NativeConverterEscrow();
    @Override public NativeConverterEscrow electromagnetism$converter(){return electromagnetism$boundary;}
    @Override public void electromagnetism$displayPower(double signedWatts) {
        if (power == signedWatts) return;
        power = signedWatts;
        ((ConverterBlockEntity)(Object)this).sendData();
    }
    @ModifyArg(method="addToGoggleTooltip", at=@At(value="INVOKE",
            target="Lcom/george_vi/electroenergetics/foundation/CEELang;formatFEPerTick(D)Lnet/createmod/catnip/lang/LangBuilder;"))
    private double electromagnetism$fePerTick(double ignored) {
        return Math.abs(power) / (20 * com.electromagnetism.config.ModConfigs.server().nativeJoulesPerFe.getF());
    }
    @Inject(method="write",at=@At("TAIL"))
    private void electromagnetism$write(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){if(!packet)electromagnetism$boundary.save(tag);}
    @Inject(method="read",at=@At("TAIL"))
    private void electromagnetism$read(CompoundTag tag,HolderLookup.Provider registry,boolean packet,CallbackInfo ci){if(!packet)electromagnetism$boundary.read(tag);}
}
