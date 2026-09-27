package com.mitchej123.supernova.mixin.early.engine;

import com.mitchej123.supernova.core.SupernovaCore;
import com.mitchej123.supernova.light.ChunkLightHelper;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.light.WorldLightManager;
import com.mitchej123.supernova.world.SupernovaWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Chunk.class)
public abstract class MixinChunk_FillChunkClient implements SupernovaChunk {

    @Shadow public World worldObj;
    @Final @Shadow public int xPosition;
    @Final @Shadow public int zPosition;
    @Shadow private ExtendedBlockStorage[] storageArrays;

    @Inject(method = "fillChunk", at = @At("RETURN"))
    private void supernova$onFillChunk(byte[] data, int extractFlags, int chunkY, boolean forceUpdate, CallbackInfo ci) {
        if (this.worldObj == null || !this.worldObj.isRemote) return;
        final WorldLightManager iface = ((SupernovaWorld) this.worldObj).supernova$getLightManager();
        if (iface == null) return;

        // With ChunkAPI the packet already installed authoritative R/G/B; importing vanilla's scalar nibbles here would overwrite it.
        if (!SupernovaCore.CHUNKAPI_PRESENT) {
            ChunkLightHelper.importVanillaSky(this.getSkyNibbles(), this.getSkyNibblesG(), this.getSkyNibblesB(), this.storageArrays, false);
            ChunkLightHelper.importVanillaBlock(this.getBlockNibblesR(), this.getBlockNibblesG(), this.getBlockNibblesB(), this.storageArrays);
        }

        iface.registerChunk((Chunk) (Object) this);
        this.setLightReady(true);

        // The server only sends final light; edge checks just fix a seam with a neighbor that arrived out of order.
        iface.queueEdgeChecks(this.xPosition, this.zPosition);
    }
}
