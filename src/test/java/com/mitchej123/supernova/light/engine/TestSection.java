package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.ExtendedSection;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Stands in for the mixin, which is not applied under src/test. */
public class TestSection extends ExtendedBlockStorage implements ExtendedSection {

    private boolean skyNonTrivial;
    private boolean blockNonTrivial;

    public TestSection(final int y, final boolean hasSky) {
        super(y, hasSky);
    }

    @Override
    public boolean supernova$hasNoBlocks() {
        return super.isEmpty();
    }

    @Override
    public boolean isEmpty() {
        return super.isEmpty() && !skyNonTrivial && !blockNonTrivial;
    }

    @Override
    public void supernova$setLightNonTrivial(boolean sky, boolean nonTrivial) {
        if (sky) skyNonTrivial = nonTrivial;
        else blockNonTrivial = nonTrivial;
    }

    @Override
    public void supernova$updateLightNonTrivial(boolean sky, boolean rangeNonTrivial, byte[] data, int trivialByte) {
        if (rangeNonTrivial) {
            supernova$setLightNonTrivial(sky, true);
            return;
        }
        if (!(sky ? skyNonTrivial : blockNonTrivial)) return;
        for (byte value : data) if ((value & 0xFF) != trivialByte) return;
        supernova$setLightNonTrivial(sky, false);
    }
}
