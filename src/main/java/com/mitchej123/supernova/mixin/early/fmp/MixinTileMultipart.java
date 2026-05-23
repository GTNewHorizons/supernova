package com.mitchej123.supernova.mixin.early.fmp;

import codechicken.multipart.TMultiPart;
import codechicken.multipart.TileMultipart;
import com.mitchej123.supernova.api.PackedColorLight;
import com.mitchej123.supernova.api.TileLightStore;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes the maximum part light value of this {@code TileMultipart} into {@link TileLightStore}
 * whenever the part composition changes, so the worker-thread block-light engine can read the
 * emission without performing an off-thread {@code World.getTileEntity} lookup.
 */
@Mixin(value = TileMultipart.class, remap = false)
public abstract class MixinTileMultipart {

    @Shadow public abstract int getLightValue();

    // HEAD: publish before notifyPartChange queues the relight via func_147451_t,
    // so the worker thread never observes a stale TileLightStore value for the new partList.
    @Inject(method = "notifyPartChange", at = @At("HEAD"))
    private void supernova$onNotifyPartChange(final TMultiPart part, final CallbackInfo ci) {
        supernova$publish();
    }

    @Inject(method = "onChunkLoad", at = @At("RETURN"))
    private void supernova$onChunkLoad(final CallbackInfo ci) {
        supernova$publish();
    }

    @Inject(method = "validate", at = @At("RETURN"))
    private void supernova$onValidate(final CallbackInfo ci) {
        supernova$publish();
    }

    @Inject(method = "invalidate", at = @At("RETURN"))
    private void supernova$onInvalidate(final CallbackInfo ci) {
        final TileEntity self = (TileEntity) (Object) this;
        if (self.getWorldObj() == null) return;
        TileLightStore.remove(self.getWorldObj().provider.dimensionId, self.xCoord, self.yCoord, self.zCoord);
    }

    private void supernova$publish() {
        final TileEntity self = (TileEntity) (Object) this;
        if (self.getWorldObj() == null) return;
        final int level = getLightValue() & 0xF;
        final int packed = level > 0 ? PackedColorLight.pack(level, level, level) : 0;
        TileLightStore.put(self.getWorldObj().provider.dimensionId, self.xCoord, self.yCoord, self.zCoord, packed);
    }
}
