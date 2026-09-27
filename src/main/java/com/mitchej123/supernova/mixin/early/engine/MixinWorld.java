package com.mitchej123.supernova.mixin.early.engine;

import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.api.ExtendedChunk;
import com.mitchej123.supernova.light.LightRegistries;
import com.mitchej123.supernova.light.WorldLightManager;
import com.mitchej123.supernova.world.SupernovaWorld;
import net.minecraft.block.Block;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class MixinWorld implements SupernovaWorld {

    @Final
    @Shadow
    public WorldProvider provider;

    @Unique
    private WorldLightManager supernova$lightInterface;

    @Unique
    private volatile boolean supernova$lightShutDown;
    @Unique
    private boolean supernova$warnedAfterShutdown;

    /** Set at constructor RETURN; dispatching from the constructor body would touch a half-built world. */
    @Unique
    private boolean supernova$ready;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void supernova$onWorldInit(CallbackInfo ci) {
        this.supernova$ready = true;
    }

    @Override
    public Chunk supernova$getAnyChunkImmediately(int chunkX, int chunkZ) {
        final WorldLightManager iface = this.supernova$lightInterface;
        if (iface != null) {
            return iface.getLoadedChunk(chunkX, chunkZ);
        }
        return null;
    }

    @Override
    public void supernova$shutdown() {
        if (this.supernova$lightInterface != null) {
            this.supernova$lightInterface.shutdown();
            this.supernova$lightInterface = null;
        }
        this.supernova$lightShutDown = true;
    }

    @Override
    public WorldLightManager supernova$getLightManager() {
        if (this.supernova$lightShutDown) {
            if (!this.supernova$warnedAfterShutdown) {
                this.supernova$warnedAfterShutdown = true;
                Supernova.LOG.warn("Light manager requested after shutdown, dim {}", this.provider.dimensionId);
            }
            return null;
        }
        if (this.supernova$lightInterface == null && this.provider != null) {
            this.supernova$lightInterface = new WorldLightManager((World) (Object) this, !this.provider.hasNoSky, true);
        }
        return this.supernova$lightInterface;
    }

    @Override
    public WorldLightManager supernova$lightManagerIfPresent() {
        return this.supernova$lightInterface;
    }

    @Override
    public boolean supernova$hasChunkPendingLight(final int chunkX, final int chunkZ) {
        final WorldLightManager iface = this.supernova$lightInterface;
        return iface != null && iface.hasChunkPendingLight(chunkX, chunkZ);
    }

    @Inject(method = "updateEntities", at = @At("HEAD"))
    private void supernova$drainClientRenderUpdates(CallbackInfo ci) {
        if (((World) (Object) this).isRemote) {
            final WorldLightManager iface = this.supernova$lightInterface;
            if (iface != null) {
                iface.drainClientLight();
            }
        }
    }

    @Inject(method = "updateEntities", at = @At("RETURN"))
    private void supernova$refreshDynamicEmission(CallbackInfo ci) {
        final WorldLightManager iface = this.supernova$lightInterface;
        if (iface != null) iface.refreshDynamicEmission();
    }

    /**
     * @author Supernova
     * @reason Replaced by Supernova engine dispatch; both sides enqueue to LightQueue
     */
    @Overwrite
    public boolean updateLightByType(EnumSkyBlock type, int x, int y, int z) {
        if (!this.supernova$ready) return false;
        final WorldLightManager iface = this.supernova$getLightManager();
        if (iface == null) return false;

        return this.supernova$queueBlockChange(iface, x, y, z);
    }

    /**
     * @author Supernova
     * @reason Dispatch checkLight to the Supernova engines on both sides
     */
    @Overwrite
    public boolean func_147451_t(int x, int y, int z) {
        if (!this.supernova$ready) return false;
        final WorldLightManager iface = this.supernova$getLightManager();
        if (iface == null) return false;
        this.supernova$queueBlockChange(iface, x, y, z);
        return true;
    }

    /** Client work is drained on the main thread before this tick's frame, so a player edit is lit in the same frame. */
    @Unique
    private boolean supernova$queueBlockChange(final WorldLightManager iface, final int x, final int y, final int z) {
        if (!((World) (Object) this).isRemote) {
            iface.queueBlockChange(x, y, z);
            return true;
        }
        final Chunk chunk = this.supernova$getAnyChunkImmediately(x >> 4, z >> 4);
        if (chunk == null || !((ExtendedChunk) chunk).isLightUsable()) {
            return false;
        }
        iface.queueBlockChange(x, y, z);
        return true;
    }

    /** Vanilla makes no light call on a metadata-only write, so a block whose emission or absorption depends on metadata would never reach the engine. */
    @Inject(method = "setBlockMetadataWithNotify", at = @At("RETURN"))
    private void supernova$onMetadataChanged(int x, int y, int z, int meta, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !this.supernova$ready) return;
        final WorldLightManager iface = this.supernova$getLightManager();
        if (iface == null) return;
        final Block block = ((World) (Object) this).getBlock(x, y, z);
        if (LightRegistries.metaAffectsLight(Block.getIdFromBlock(block))) {
            this.supernova$queueBlockChange(iface, x, y, z);
        }
    }

    // Kills vanilla's random per-tick playerCheckLight fixup.
    @Redirect(method = "setActivePlayerChunksAndCheckLight", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;func_147451_t(III)Z"))
    private boolean supernova$skipPlayerCheckLight(World world, int x, int y, int z) {
        return true;
    }
}
