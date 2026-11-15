package com.electromagnetism.integration.cee.mixin;
import com.george_vi.electroenergetics.content.railway_electrification.catenary.CatenaryConnection;
import com.george_vi.electroenergetics.simulation.infrastructure.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.Map;
@Mixin(value=InfrastructureSavedData.class,remap=false)
public interface NativeCatenaryDataAccess {
    @Accessor("CATENARY_DATA") Map<CatenaryConnection,CatenaryConnectionData> electromagnetism$catenaryData();
}
