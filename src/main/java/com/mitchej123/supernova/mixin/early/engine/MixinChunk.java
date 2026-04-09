package com.mitchej123.supernova.mixin.early.engine;

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

    @Unique private SWMRNibbleArray[] supernova$skyNibbles;
    @Unique private boolean[] supernova$skyEmptinessMap;
    @Unique private SWMRNibbleArray[] supernova$blockNibblesR;
    @Unique private SWMRNibbleArray[] supernova$blockNibblesG;
    @Unique private SWMRNibbleArray[] supernova$blockNibblesB;
    @Unique private boolean[] supernova$blockEmptinessMap;
    @Unique private SWMRNibbleArray[] supernova$skyNibblesG;
    @Unique private SWMRNibbleArray[] supernova$skyNibblesB;
    @Unique private volatile boolean supernova$lightReady;

    @Inject(method = "<init>(Lnet/minecraft/world/World;II)V", at = @At("RETURN"))
    private void supernova$onInit(World world, int cx, int cz, CallbackInfo ci) {
        supernova$initNibbles();
    }

    @Unique
    private void supernova$initNibbles() {
        this.supernova$skyNibbles = SupernovaEngine.getFilledEmptyLight();
        this.supernova$blockNibblesR = SupernovaEngine.getFilledEmptyLight();
        if (SupernovaConfig.isScalarMode()) {
            // Scalar mode: G/B arrays stay null -- engines only use R
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

    // Import vanilla light data and trigger block engine for chunks without saved RGB data.
    @Inject(method = "onChunkLoad", at = @At("HEAD"))
    private void supernova$onChunkLoad(CallbackInfo ci) {
        if (!SupernovaCore.CHUNKAPI_PRESENT && this.isLightPopulated) {
            ChunkLightHelper.importVanillaBlock(this.supernova$blockNibblesR, null, null, this.storageArrays);
        }

        final boolean hasBlockData = ChunkLightHelper.hasSavedBlockData(this.supernova$blockNibblesR, this.storageArrays);

        if (this.worldObj != null) {
            final WorldLightManager iface = ((SupernovaWorld) this.worldObj).supernova$getLightManager();
            if (iface != null) {
                // Register in ConcurrentHashMap for worker thread access (both client and server)
                iface.registerChunk((Chunk) (Object) this);

                if (hasBlockData) {
                    // Chunk has saved Supernova data -- import vanilla sky light where missing and mark ready.
                    ChunkLightHelper.importVanillaSky(this.supernova$skyNibbles, this.supernova$skyNibblesG, this.supernova$skyNibblesB, this.storageArrays, true);
                    ((SupernovaChunk) this).setLightReady(true);
                } else if (!this.worldObj.isRemote) {
                    // Import vanilla sky so game logic has correct values during BFS backlog
                    ChunkLightHelper.importVanillaSky(this.supernova$skyNibbles, this.supernova$skyNibblesG, this.supernova$skyNibblesB, this.storageArrays, false);
                    final Boolean[] emptySections = SupernovaEngine.getEmptySectionsForChunk((Chunk) (Object) this);
                    iface.queueChunkLight(this.xPosition, this.zPosition, (Chunk) (Object) this, emptySections);
                    iface.scheduleUpdate();
                }
            }
        }

        ChunkLightHelper.syncSkyToVanilla(this.supernova$skyNibbles, this.storageArrays);
    }

    @Override
    public void syncLightToVanilla() {
        ChunkLightHelper.syncSkyToVanilla(this.supernova$skyNibbles, this.storageArrays);
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

    // Compute heightmap only; actual lighting deferred to onChunkLoad where neighbor chunks are available for proper edge propagation.
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

        // Vanilla-style column walk: propagate sky light top-down through block opacity. BFS will compute proper RGB sky light later; this provides a correct
        // scalar baseline so chunk packets carry reasonable initial values before BFS runs.
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
    public void relightBlock(int x, int y, int z) {
        // Keep heightmap updated
        final int heightMapIdx = z << 4 | x;
        final int currentHeight = this.heightMap[heightMapIdx];
        int newHeight = Math.max(y + 1, currentHeight);

        while (newHeight > 0 && this.getBlock(x, newHeight - 1, z).getLightOpacity() == 0) {
            --newHeight;
        }

        this.heightMap[heightMapIdx] = newHeight;

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

        // Update vanilla sky nibbles for this column so newly created sections (e.g. tall trees placed during population) have correct initial sky values.
        if (!this.worldObj.provider.hasNoSky) {
            supernova$fillVanillaSkyForColumn(x, z, this.getTopFilledSegment());
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

    /**
     * Vanilla-style column walk for a single (x, z): propagate sky=15 top-down, attenuating by block opacity. Below the first opaque block, even transparent
     * blocks attenuate by 1.
     */
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
    public void recheckGaps(boolean onlyOne) {
        // no-op: Supernova handles skylight gap checks
    }

    /**
     * @author Supernova
     * @reason Gate isLightPopulated on this chunk + 3×3 neighborhood being light-ready.
     */
    @Overwrite
    public void func_150809_p() {
        this.isTerrainPopulated = true;
        supernova$trySetLightPopulated();
    }

    @Unique
    private void supernova$trySetLightPopulated() {
        if (this.isLightPopulated) return;
        if (!this.supernova$lightReady) return;
        if (this.worldObj == null) return;

        final WorldLightManager iface = ((SupernovaWorld) this.worldObj).supernova$getLightManager();
        if (iface == null) return;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                final Chunk neighbor = iface.getLoadedChunk(this.xPosition + dx, this.zPosition + dz);
                if (neighbor == null || !((SupernovaChunk) neighbor).isLightReady()) {
                    return;
                }
            }
        }
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
    public void enqueueRelightChecks() {
        // no-op: Supernova handles all light propagation
    }

    @Inject(method = "onChunkUnload", at = @At("HEAD"))
    private void supernova$onChunkUnload(CallbackInfo ci) {
        if (this.worldObj == null) return;
        final WorldLightManager iface = ((SupernovaWorld) this.worldObj).supernova$getLightManager();
        if (iface == null) return;

        iface.removeChunkFromQueues(this.xPosition, this.zPosition);

        if (!this.worldObj.isRemote) {
            iface.awaitPendingWork(this.xPosition, this.zPosition);
        }

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

    // Our engine manages all light nibbles directly -- ignore external writes.
    // @Inject+cancel instead of @Overwrite for compat with mods injecting into this method.
    // Effectively dead code -- MixinWorld intercepts updateLightByType before it reaches here, but better to be safe.
    @Inject(method = "setLightValue", at = @At("HEAD"), cancellable = true)
    private void supernova$setLightValue(EnumSkyBlock type, int x, int y, int z, int value, CallbackInfo ci) {
        ci.cancel();
    }

    @Unique private static final String SET_BLOCK = "func_150807_a(IIILnet/minecraft/block/Block;I)Z";

    // Prevent generateSkylightMap from being called when a new section is created.
    @ModifyVariable(method = SET_BLOCK, at = @At(value = "STORE", ordinal = 1), name = "flag", index = 11, allow = 1)
    private boolean supernova$preventSkylightRegen(boolean flag) {
        return false;
    }

    // No-op propagateSkylightOcclusion -- it sets dirty flags for recheckGaps which is already overwritten to no-op by Supernova.
    @Redirect(method = SET_BLOCK, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;propagateSkylightOcclusion(II)V"))
    private void supernova$noPropagateOcclusion(Chunk chunk, int x, int z) {
        // no-op
    }

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
}
