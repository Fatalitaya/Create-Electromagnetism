package com.electromagnetism.client;

import com.electromagnetism.registry.ModSounds;
import com.electromagnetism.content.acoustics.bell.IceBellBlock;
import com.electromagnetism.content.acoustics.bell.NailongBellBlock;
import com.electromagnetism.content.acoustics.bell.ElectricBellBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance.Attenuation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Drive loop volume and pitch from server updates. The ordinary bell plays one final sound when
 * power cuts out.
 */
public final class BellSoundHandler {
    private BellSoundHandler() {
    }

    public static void tickAudio(ElectricBellBlockEntity be) {
        SoundEvent loop = loopSoundOf(be);
        if (be.getVolume() > 0 && !be.hasSoundInstance()) {
            Minecraft.getInstance().getSoundManager().play(new BellSoundInstance(be, loop));
            be.markSoundStarted();
        } else if (be.getVolume() == 0 && be.hasSoundInstance()) {
            if (!isEasterEgg(be)) {
                // Special bell variants stop their loop immediately on power loss.
                var center = be.getBlockPos().getCenter();
                Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
                        ModSounds.ALARM_BELL_END.get(), SoundSource.BLOCKS, be.getPrevVolume(), be.getPrevPitch(),
                        be.getLevel().random, center.x, center.y, center.z));
            }
            be.markSoundStopped();
        }
    }

    private static SoundEvent loopSoundOf(ElectricBellBlockEntity be) {
        if (be.getBlockState().getBlock() instanceof NailongBellBlock)
            return ModSounds.NAILONG_BELL.get();
        if (be.getBlockState().getBlock() instanceof IceBellBlock)
            return ModSounds.ICE_BELL.get();
        return ModSounds.ALARM_BELL.get();
    }

    private static boolean isEasterEgg(ElectricBellBlockEntity be) {
        return be.getBlockState().getBlock() instanceof IceBellBlock
                || be.getBlockState().getBlock() instanceof NailongBellBlock;
    }

    /** Follow the block entity and stop when the bell loses power or is removed. */
    static class BellSoundInstance extends AbstractTickableSoundInstance {
        private final ElectricBellBlockEntity be;

        BellSoundInstance(ElectricBellBlockEntity be, SoundEvent sound) {
            super(sound, SoundSource.BLOCKS, be.getLevel().random);
            this.be = be;
            var center = be.getBlockPos().getCenter();
            this.x = center.x;
            this.y = center.y;
            this.z = center.z;
            this.attenuation = Attenuation.LINEAR;
            this.looping = true;
            this.delay = 0;
            this.volume = 0f;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (be.isRemoved()) {
                stop();
            } else {
                if (be.getVolume() == 0) {
                    stop();
                    return;
                }
                volume = be.getVolume();
                pitch = be.getPitch();
            }
        }
    }
}
