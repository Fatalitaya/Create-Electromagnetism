package com.electromagnetism.platform;

import com.electromagnetism.content.acoustics.bell.ElectricBellBlockEntity;

import java.util.function.Consumer;

/** Common block entities call these hooks. Client initialization binds the audio implementation. */
public class ClientHooks {
    public static Consumer<com.electromagnetism.content.instruments.SignalGeneratorBlockEntity> openSignalGenerator = be -> {};
    public static Consumer<com.electromagnetism.content.instruments.InstrumentBlockEntity> openOscilloscope = be -> {};

    public static Consumer<ElectricBellBlockEntity> bellAudio = be -> {
    };
}
