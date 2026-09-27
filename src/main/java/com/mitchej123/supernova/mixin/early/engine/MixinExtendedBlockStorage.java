package com.mitchej123.supernova.mixin.early.engine;

import com.mitchej123.supernova.api.ExtendedSection;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ExtendedBlockStorage.class)
public abstract class MixinExtendedBlockStorage implements ExtendedSection {

    @Shadow private int blockRefCount;

    @Unique private boolean supernova$skyLightNonTrivial;
    @Unique private boolean supernova$blockLightNonTrivial;

    /**
     * @author Supernova
     * @reason MC-80966: packets drop empty sections and their light; overwritten rather than redirected because ChunkAPI replaces the extraction method
     */
    @Overwrite
    public synchronized boolean isEmpty() {
        return this.blockRefCount == 0 && !this.supernova$skyLightNonTrivial && !this.supernova$blockLightNonTrivial;
    }

    @Override
    public boolean supernova$hasNoBlocks() {
        return this.blockRefCount == 0;
    }

    @Override
    public synchronized void supernova$setLightNonTrivial(final boolean sky, final boolean nonTrivial) {
        if (sky) this.supernova$skyLightNonTrivial = nonTrivial;
        else this.supernova$blockLightNonTrivial = nonTrivial;
    }

    @Override
    public synchronized void supernova$updateLightNonTrivial(final boolean sky, final boolean rangeNonTrivial, final byte[] data, final int trivialByte) {
        if (rangeNonTrivial) {
            this.supernova$setLightNonTrivial(sky, true);
            return;
        }
        if (!(sky ? this.supernova$skyLightNonTrivial : this.supernova$blockLightNonTrivial)) return;
        for (final byte value : data) {
            if ((value & 0xFF) != trivialByte) return;
        }
        this.supernova$setLightNonTrivial(sky, false);
    }
}
