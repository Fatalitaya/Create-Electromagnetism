package com.electromagnetism.content.acoustics.bell;

/** The ice bell uses the ordinary coil connections and stops its custom loop on power loss. */
public class IceBellBlock extends ElectricBellBlock {
    public IceBellBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isIceBell() {
        return true;
    }
}
