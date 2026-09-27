package com.mitchej123.supernova.mixin.early.engine;

import com.mitchej123.supernova.api.ExtendedSection;
import com.mitchej123.supernova.config.SupernovaConfig;
import com.mitchej123.supernova.core.SupernovaCore;
import com.mitchej123.supernova.light.ChunkLightHelper;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.light.WorldLightManager;
import com.mitchej123.supernova.light.engine.SupernovaEngine;
import com.mitchej123.supernova.world.SupernovaWorld;
import net.minecraft.block.Block;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Chunk.class)
public abstract class MixinChunk implements SupernovaChunk {

    @Shadow public World worldObj;
    @Final @Shadow public int xPosition;
    @Final @Shadow public int zPosition;
    @Shadow public int[] heightMap;
    @Shadow public int heightMapMinimum;
    @Shadow public int[] precipitationHeightMap;
    @Shadow public boolean isModified;
    @Shadow public boolean isTerrainPopulated;
    @Shadow public boolean isLightPopulated;
    @Shadow public static boolean isLit;
    @Shadow private ExtendedBlockStorage[] storageArrays;

    @Shadow
    public abstract int getTopFilledSegment();

    @Shadow
    public abstract Block getBlock(int x, int y, int z);

    @Shadow
    public abstract ExtendedBlockStorage[] getBlockStorageArray();

    @Unique private volatile SWMRNibbleArray[] supernova$skyNibbles;
    @Unique private volatile boolean[] supernova$skyEmptinessMap;
    @Unique private volatile SWMRNibbleArray[] supernova$blockNibblesR;
    @Unique private volatile SWMRNibbleArray[] supernova$blockNibblesG;
    @Unique private volatile SWMRNibbleArray[] supernova$blockNibblesB;
    @Unique private volatile boolean[] supernova$blockEmptinessMap;
    @Unique private volatile SWMRNibbleArray[] supernova$skyNibblesG;
    @Unique private volatile SWMRNibbleArray[] supernova$skyNibblesB;
    @Unique private volatile boolean supernova$lightReady;
    @Unique private volatile boolean supernova$lightUsable;
    @Unique private volatile boolean supernova$blockLightDirty;
    @Unique private volatile boolean supernova$skyLightDirty;

    @Inject(method = "<init>(Lnet/minecraft/world/World;II)V", at = @At("RETURN"))
    private void supernova$onInit(World world, int cx, int cz, CallbackInfo ci) {
        supernova$initNibbles();
    }

    @Unique
    private void supernova$initNibbles() {
        this.supernova$skyNibbles = SupernovaEngine.getFilledEmptyLight();
        this.supernova$blockNibblesR = SupernovaEngine.getFilledEmptyLight();
        if (SupernovaConfig.isScalarMode()) {
            // Scalar mode: G/B stay null; engines read only R.
            this.supernova$skyNibblesG = null;
            this.supernova$skyNibblesB = null;
            this.supernova$blockNibblesG = null;
            this.supernova$blockNibblesB = null;
        } else {
            this.supernova$skyNibblesG = SupernovaEngine.getFilledEmptyLight();
            this.supernova$skyNibblesB = SupernovaEngine.getFilledEmptyLight();
            this.supernova$blockNibblesG = SupernovaEngine.getFilledEmptyLight();
            this.supernova$blockNibblesB = SupernovaEngine.getFilledEmptyLight();
        }
    }

    @Inject(method = "onChunkLoad", at = @At("HEAD"))
    private void supernova$onChunkLoad(CallbackInfo ci) {
        if (!SupernovaCore.CHUNKAPI_PRESENT && this.isLightPopulated) {
            ChunkLightHelper.importVanillaBlock(this.supernova$blockNibblesR, null, null, this.storageArrays);
        }

        final boolean hasBlockData = ChunkLightHelper.hasSavedBlockData(this.supernova$blockNibblesR, this.storageArrays);

        if (this.worldObj != null) {
            final WorldLightManager iface = ((SupernovaWorld) this.worldObj).supernova$getLightManager();
            if (iface != null) {
                // Registered in the SnapshotChunkMap the light workers read (both client and server).
                iface.registerChunk((Chunk) (Object) this);

                if (hasBlockData) {
                    ChunkLightHelper.importVanillaSky(this.supernova$skyNibbles, this.supernova$skyNibblesG, this.supernova$skyNibblesB, this.storageArrays, true);
                    this.setLightReady(true);
                } else if (!this.worldObj.isRemote) {
                    // Game logic reads sky before the BFS backlog clears.
                    ChunkLightHelper.importVanillaSky(this.supernova$skyNibbles, this.supernova$skyNibblesG, this.supernova$skyNibblesB, this.storageArrays, false);
                    final Boolean[] emptySections = SupernovaEngine.getEmptySectionsForChunk((Chunk) (Object) this);
                    iface.queueChunkLight(this.xPosition, this.zPosition, (Chunk) (Object) this, emptySections);
                }
            }
        }

        ChunkLightHelper.syncSkyToVanilla(this.supernova$skyNibbles, this.supernova$skyNibblesG, this.supernova$skyNibblesB, this.storageArrays);
    }

    @Inject(method = "func_150812_a", at = @At("RETURN"))
    private void supernova$onTileEntityAdded(int x, int y, int z, net.minecraft.tileentity.TileEntity tile, CallbackInfo ci) {
        supernova$tileEntityChanged(x, y, z);
    }

    @Inject(method = "removeTileEntity", at = @At("RETURN"))
    private void supernova$onTileEntityRemoved(int x, int y, int z, CallbackInfo ci) {
        supernova$tileEntityChanged(x, y, z);
    }

    @Unique
    private void supernova$tileEntityChanged(final int x, final int y, final int z) {
        if (this.worldObj == null) return;
        final WorldLightManager iface = ((SupernovaWorld) this.worldObj).supernova$lightManagerIfPresent();
        if (iface != null) iface.tileEntityChanged((this.xPosition << 4) | x, y, (this.zPosition << 4) | z);
    }

    @Override
    public void syncLightToVanilla() {
        ChunkLightHelper.syncSkyToVanilla(this.supernova$skyNibbles, this.supernova$skyNibblesG, this.supernova$skyNibblesB, this.storageArrays);
        ChunkLightHelper.syncBlockToVanilla(
            this.supernova$blockNibblesR, this.supernova$blockNibblesG, this.supernova$blockNibblesB,
            this.storageArrays);
    }

    @Override
    public SWMRNibbleArray[] getSkyNibbles() {return this.supernova$skyNibbles;}

    @Override
    public void setSkyNibbles(SWMRNibbleArray[] nibbles) {this.supernova$skyNibbles = nibbles;}

    @Override
    public boolean[] getSkyEmptinessMap() {return this.supernova$skyEmptinessMap;}

    @Override
    public void setSkyEmptinessMap(boolean[] map) {this.supernova$skyEmptinessMap = map;}

    @Override
    public SWMRNibbleArray[] getBlockNibblesR() {return this.supernova$blockNibblesR;}

    @Override
    public void setBlockNibblesR(SWMRNibbleArray[] nibbles) {this.supernova$blockNibblesR = nibbles;}

    @Override
    public SWMRNibbleArray[] getBlockNibblesG() {return this.supernova$blockNibblesG;}

    @Override
    public void setBlockNibblesG(SWMRNibbleArray[] nibbles) {this.supernova$blockNibblesG = nibbles;}

    @Override
    public SWMRNibbleArray[] getBlockNibblesB() {return this.supernova$blockNibblesB;}

    @Override
    public void setBlockNibblesB(SWMRNibbleArray[] nibbles) {this.supernova$blockNibblesB = nibbles;}

    @Override
    public boolean[] getBlockEmptinessMap() {return this.supernova$blockEmptinessMap;}

    @Override
    public void setBlockEmptinessMap(boolean[] map) {this.supernova$blockEmptinessMap = map;}

    @Override
    public SWMRNibbleArray[] getSkyNibblesR() {return this.supernova$skyNibbles;}

    @Override
    public SWMRNibbleArray[] getSkyNibblesG() {return this.supernova$skyNibblesG;}

    @Override
    public SWMRNibbleArray[] getSkyNibblesB() {return this.supernova$skyNibblesB;}

    @Override
    public void setSkyNibblesG(SWMRNibbleArray[] nibbles) {this.supernova$skyNibblesG = nibbles;}

    @Override
    public void setSkyNibblesB(SWMRNibbleArray[] nibbles) {this.supernova$skyNibblesB = nibbles;}

    @Override
    public boolean isLightReady() {return this.supernova$lightReady;}

    @Override
    public void setLightReady(boolean ready) {this.supernova$lightReady = ready;}

    @Override
    public void setLightUsable(boolean usable) {this.supernova$lightUsable = usable;}

    @Override
    public void markLightDirty(boolean sky) {if (sky) this.supernova$skyLightDirty = true; else this.supernova$blockLightDirty = true;}

    @Override
    public void clearLightDirty(boolean sky) {if (sky) this.supernova$skyLightDirty = false; else this.supernova$blockLightDirty = false;}

    /** Cleared before the light is written, so a worker's mark landing during the write survives and the chunk saves again. */
    @Inject(method = "needsSaving", at = @At("RETURN"), cancellable = true)
    private void supernova$needsSavingForLight(boolean flag, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && (this.supernova$blockLightDirty || this.supernova$skyLightDirty)) cir.setReturnValue(true);
    }

    // Disjunction keeps the pair monotone, so the saved-light load path can set ready alone.
    @Override
    public boolean isLightUsable() {return this.supernova$lightReady || this.supernova$lightUsable;}

    // Heightmap only; lighting waits for onChunkLoad, where neighbors exist for edge propagation.
    // @Inject+cancel instead of @Overwrite so other mods' injectors into this method don't crash.
    @Inject(method = "generateSkylightMap", at = @At("HEAD"), cancellable = true)
    private void supernova$generateSkylightMap(CallbackInfo ci) {
        final int topSegment = this.getTopFilledSegment();
        this.heightMapMinimum = Integer.MAX_VALUE;

        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                this.precipitationHeightMap[x + (z << 4)] = -999;

                for (int y = topSegment + 16 - 1; y > 0; --y) {
                    if (this.getBlock(x, y - 1, z).getLightOpacity() != 0) {
                        this.heightMap[z << 4 | x] = y;
                        if (y < this.heightMapMinimum) {
                            this.heightMapMinimum = y;
                        }
                        break;
                    }
                }
            }
        }

        // Scalar baseline so chunk packets carry sane sky values before the RGB BFS runs.
        if (!this.worldObj.provider.hasNoSky) {
            supernova$fillVanillaSkyColumn(topSegment);
        }

        this.isModified = true;
        ci.cancel();
    }

    /**
     * @author Supernova
     * @reason Supernova handles skylight column updates via updateLightByType
     */
    @Overwrite
    private void relightBlock(int x, int y, int z) {
        final int heightMapIdx = z << 4 | x;
        final int currentHeight = this.heightMap[heightMapIdx];
        int newHeight = Math.max(y + 1, currentHeight);

        while (newHeight > 0 && this.getBlock(x, newHeight - 1, z).getLightOpacity() == 0) {
            --newHeight;
        }

        if (newHeight != currentHeight) {
            this.worldObj.markBlocksDirtyVertical(x + this.xPosition * 16, z + this.zPosition * 16, newHeight, currentHeight);
        }

        this.heightMap[heightMapIdx] = newHeight;

        if (newHeight != currentHeight) {
            if (newHeight < this.heightMapMinimum) {
                this.heightMapMinimum = newHeight;
            } else if (currentHeight == this.heightMapMinimum) {
                this.heightMapMinimum = Integer.MAX_VALUE;
                for (int i = 0; i < 256; ++i) {
                    if (this.heightMap[i] < this.heightMapMinimum) {
                        this.heightMapMinimum = this.heightMap[i];
                    }
                }
            }

            // Sections created during population (tall trees) otherwise ship zero sky.
            if (!this.worldObj.provider.hasNoSky) {
                supernova$fillVanillaSkyForColumn(x, z, this.getTopFilledSegment());
            }
        }

        this.isModified = true;
    }

    @Unique
    private void supernova$fillVanillaSkyColumn(final int topSegment) {
        for (int lx = 0; lx < 16; ++lx) {
            for (int lz = 0; lz < 16; ++lz) {
                supernova$fillVanillaSkyForColumn(lx, lz, topSegment);
            }
        }
    }

    /** Vanilla column walk: sky=15 top-down by opacity; below the first opaque block even transparent blocks attenuate by 1. */
    @Unique
    private void supernova$fillVanillaSkyForColumn(final int x, final int z, final int topSegment) {
        int skyLevel = 15;
        for (int y = topSegment + 15; y >= 0; --y) {
            final ExtendedBlockStorage section = this.storageArrays[y >> 4];
            if (section == null) {
                if (skyLevel != 15) {
                    skyLevel = Math.max(0, skyLevel - 1);
                }
                continue;
            }
            int opacity = section.getBlockByExtId(x, y & 15, z).getLightOpacity();
            if (opacity == 0 && skyLevel != 15) {
                opacity = 1;
            }
            skyLevel = Math.max(0, skyLevel - opacity);
            final NibbleArray skyArr = section.getSkylightArray();
            if (skyArr != null) {
                skyArr.set(x, y & 15, z, skyLevel);
            }
            if (skyLevel <= 0) break;
        }
    }

    /**
     * @author Supernova
     * @reason Supernova handles edge checks separately
     */
    @Overwrite
    private void recheckGaps(boolean onlyOne) {}

    /**
     * @author Supernova
     * @reason isLightPopulated also gates markAndNotifyBlock, so the readiness gate lives at the chunk send site
     */
    @Overwrite
    public void func_150809_p() {
        this.isTerrainPopulated = true;
        this.isLightPopulated = true;
    }

    /**
     * @author Supernova
     * @reason Vanilla column-walking light checker is fully redundant with Supernova.
     */
    @Overwrite
    private boolean func_150811_f(int x, int z) {
        return true;
    }

    /**
     * @author Supernova
     * @reason Vanilla per-tick relight checks are redundant
     */
    @Overwrite
    public void enqueueRelightChecks() {}

    @Inject(method = "onChunkUnload", at = @At("HEAD"))
    private void supernova$onChunkUnload(CallbackInfo ci) {
        if (this.worldObj == null) return;
        final WorldLightManager iface = ((SupernovaWorld) this.worldObj).supernova$getLightManager();
        if (iface == null) return;

        // Await before dropping the queues; removal completes the futures and makes the wait a no-op.
        if (!this.worldObj.isRemote) {
            final boolean settled = iface.awaitPendingWork(this.xPosition, this.zPosition);
            if (!settled || iface.hasUnsettledLightValues(this.xPosition, this.zPosition)) {
                this.supernova$lightReady = false;
                this.supernova$lightUsable = false;
            }
        }

        iface.removeChunkFromQueues(this.xPosition, this.zPosition);
        // Last: workers key "chunk unloaded" off the loaded map, so an earlier unregister lets them bail.
        iface.unregisterChunk(this.xPosition, this.zPosition);
    }

    /**
     * @author Supernova
     * @reason Read sky light from Supernova nibbles, block light from RGB max
     */
    @Overwrite
    public int getSavedLightValue(EnumSkyBlock type, int x, int y, int z) {
        if (type == EnumSkyBlock.Sky) {
            return ChunkLightHelper.getSkyLight(this.supernova$skyNibbles, this.supernova$skyNibblesG, this.supernova$skyNibblesB, x, y, z);
        }
        return ChunkLightHelper.getBlockLight(this.supernova$blockNibblesR, this.supernova$blockNibblesG, this.supernova$blockNibblesB, x, y, z);
    }

    // Engine owns the nibbles; MixinWorld already intercepts updateLightByType.
    // @Inject+cancel instead of @Overwrite for compat with mods injecting into this method.
    @Inject(method = "setLightValue", at = @At("HEAD"), cancellable = true)
    private void supernova$setLightValue(EnumSkyBlock type, int x, int y, int z, int value, CallbackInfo ci) {
        ci.cancel();
    }

    @Unique private static final String SET_BLOCK = "func_150807_a(IIILnet/minecraft/block/Block;I)Z";

    /** Suppresses generateSkylightMap; targeted store sits in the just-allocated-section branch, and a section born under a final nibble ships zeros unless seeded. */
    @ModifyVariable(method = SET_BLOCK, at = @At(value = "STORE", ordinal = 1), name = "flag", index = 11, allow = 1)
    private boolean supernova$onSectionCreated(boolean flag, int x, int y, int z, Block block, int meta) {
        final int sectionY = y >> 4;
        ChunkLightHelper.seedVanillaSection(this.storageArrays[sectionY], this.supernova$skyNibbles, this.supernova$skyNibblesG, this.supernova$skyNibblesB,
            this.supernova$blockNibblesR, this.supernova$blockNibblesG, this.supernova$blockNibblesB, sectionY);
        if (this.worldObj != null) {
            final WorldLightManager iface = ((SupernovaWorld) this.worldObj).supernova$getLightManager();
            // The emptiness map still believes this section is empty; nothing else told the engine otherwise.
            if (iface != null) iface.queueSectionChange(this.xPosition, sectionY, this.zPosition, false);
        }
        return false;
    }

    // It only sets dirty flags for recheckGaps, already overwritten to a no-op.
    @Redirect(method = SET_BLOCK, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;propagateSkylightOcclusion(II)V"))
    private void supernova$noPropagateOcclusion(Chunk chunk, int x, int z) {}

    /**
     * @author Supernova
     * @reason Read sky and block light from Supernova nibbles instead of vanilla EBS
     */
    @Overwrite
    public int getBlockLightValue(int x, int y, int z, int skyLightSubtracted) {
        int skyLight = this.worldObj.provider.hasNoSky ? 0
            : ChunkLightHelper.getSkyLight(
                this.supernova$skyNibbles, this.supernova$skyNibblesG, this.supernova$skyNibblesB, x, y, z);
        if (skyLight > 0) {
            isLit = true;
        }
        skyLight -= skyLightSubtracted;
        int blockLight = ChunkLightHelper.getBlockLight(
            this.supernova$blockNibblesR, this.supernova$blockNibblesG, this.supernova$blockNibblesB, x, y, z);
        if (blockLight > skyLight) {
            skyLight = blockLight;
        }
        return skyLight;
    }

    // Vanilla's renderer and pathfinding ChunkCache use this; only the packet path needs isEmpty()'s light latch.
    @Redirect(method = "getAreLevelsEmpty", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;isEmpty()Z"))
    private boolean supernova$levelsEmptyIgnoresLight(ExtendedBlockStorage section) {
        return ((ExtendedSection) section).supernova$hasNoBlocks();
    }
}
