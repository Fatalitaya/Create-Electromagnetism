package com.electromagnetism.client;

import com.electromagnetism.platform.ClientHooks;
import com.electromagnetism.client.model.ModPartialModels;

/** Load partial models and install client audio handlers for hooks called by common block entities. */
public class ElectromagnetismClient {
    public static void init() {
        com.electromagnetism.content.instruments.AudioNetworking.clientLease=GameAudioCapture::lease;
        com.electromagnetism.content.instruments.AudioNetworking.clientOutput=RadioPlayback::receive;
        ModPartialModels.register();
        ClientHooks.openSignalGenerator = be -> net.minecraft.client.Minecraft.getInstance().setScreen(new SignalGeneratorScreen(be));
        ClientHooks.openOscilloscope = be -> net.minecraft.client.Minecraft.getInstance().setScreen(new OscilloscopeScreen(be));
        ClientHooks.bellAudio = BellSoundHandler::tickAudio;
    }
}
