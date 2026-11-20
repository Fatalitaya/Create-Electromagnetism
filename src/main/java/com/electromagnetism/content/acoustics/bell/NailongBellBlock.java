package com.electromagnetism.content.acoustics.bell;

/** The Nailong bell uses the ordinary coil connections and plays its own powered loop. */
public class NailongBellBlock extends ElectricBellBlock {
    public NailongBellBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isNailongBell() {
        return true;
    }
}
