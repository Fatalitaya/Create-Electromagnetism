package com.electromagnetism.integration.cee.nativebridge;
import java.util.List;
import net.minecraft.core.BlockPos;
import com.george_vi.electroenergetics.content.rotor.AlternatorRotorBlockEntity;
public interface NativeBrushAssembly {
    List<AlternatorRotorBlockEntity> electromagnetism$rotors();
    BlockPos electromagnetism$otherBrush();
    void electromagnetism$refreshAssembly();
    String electromagnetism$assemblyProblem();
}
