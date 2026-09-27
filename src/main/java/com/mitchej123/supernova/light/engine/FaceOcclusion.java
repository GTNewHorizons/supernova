package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.api.FaceLightOcclusion;
import com.mitchej123.supernova.api.PackedColorLight;
import com.mitchej123.supernova.api.PositionalColoredTranslucency;
import com.mitchej123.supernova.api.TranslucencyRegistry;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.BitSet;

/** Face gating uses {@link FaceLightOcclusion} or the scanned {@code isSideSolid} table; ordinary absorption comes from {@link TranslucencyRegistry}. */
public final class FaceOcclusion {

    /** Block-id-indexed face tables, swapped as one field so a rebuild publishes atomically. */
    private static final class Tables {

        static final Tables EMPTY = new Tables(new long[0][], new BitSet());

        /** [blockId] -> 96 bits, index meta * 6 + axisDir, 1 = solid. Absent for FaceLightOcclusion implementors, which resolve dynamically. */
        final long[][] faceSolidity;
        /** Includes FaceLightOcclusion implementors, which have no faceSolidity row. */
        final BitSet sidedTransparency;

        Tables(final long[][] faceSolidity, final BitSet sidedTransparency) {
            this.faceSolidity = faceSolidity;
            this.sidedTransparency = sidedTransparency;
        }
    }

    private static volatile Tables TABLES = Tables.EMPTY;

    private static final ForgeDirection[] AXIS_TO_FORGE = {
            ForgeDirection.EAST,   // 0: POSITIVE_X
            ForgeDirection.WEST,   // 1: NEGATIVE_X
            ForgeDirection.SOUTH,  // 2: POSITIVE_Z
            ForgeDirection.NORTH,  // 3: NEGATIVE_Z
            ForgeDirection.UP,     // 4: POSITIVE_Y
            ForgeDirection.DOWN,   // 5: NEGATIVE_Y
    };

    private FaceOcclusion() {}

    public static boolean hasSidedTransparency(final Block block) {
        return hasSidedTransparency(Block.getIdFromBlock(block));
    }

    public static boolean hasSidedTransparency(final int blockId) {
        return blockId >= 0 && TABLES.sidedTransparency.get(blockId);
    }

    /** Only meaningful when hasSidedTransparency holds. */
    public static boolean isFaceSolid(final Block block, final int meta, final int axisDir) {
        if (block instanceof FaceLightOcclusion) return isImplementorFaceOpaque((FaceLightOcclusion) block, meta, axisDir);
        return isFaceSolid(faceBits(Block.getIdFromBlock(block)), meta, axisDir);
    }

    static boolean isImplementorFaceOpaque(final FaceLightOcclusion block, final int meta, final int axisDir) {
        return block.getDirectionalLightOpacity(meta, AXIS_TO_FORGE[axisDir]) > 1;
    }

    /** Hoist out of direction loops: the Block-keyed overloads redo the id lookup and volatile read per face. */
    private static long[] faceBits(final int blockId) {
        final long[][] solidity = TABLES.faceSolidity;
        return (blockId >= 0 && blockId < solidity.length) ? solidity[blockId] : null;
    }

    /** Distinguishes "no per-face gating" from the null returned for a FaceLightOcclusion implementor. */
    public static final long[] NOT_SIDED = new long[0];

    public static long[] sidedFaceBits(final int blockId) {
        final Tables t = TABLES;
        if (blockId < 0 || !t.sidedTransparency.get(blockId)) return NOT_SIDED;
        return blockId < t.faceSolidity.length ? t.faceSolidity[blockId] : null;
    }

    public static boolean isFaceSolid(final long[] faceBits, final int meta, final int axisDir) {
        // Table covers meta 0-15; past that only FaceLightOcclusion gives per-face control.
        if (faceBits == null || meta > 15) return true;
        final int bitIndex = meta * 6 + axisDir;
        return (faceBits[bitIndex >> 6] & (1L << (bitIndex & 63))) != 0;
    }

    public static boolean isFaceSolid(final long[] bits, final Block block, final int meta, final int axisDir) {
        return bits == NOT_SIDED || (bits == null ? isImplementorFaceOpaque((FaceLightOcclusion) block, meta, axisDir) : isFaceSolid(bits, meta, axisDir));
    }

    /** Packed per-face absorption (0x0R0G0B): interface -> table -> registry/vanilla. */
    public static int getDirectionalAbsorption(final Block block, final int meta, final int rawOpacity, final int axisDir) {
        return getDirectionalAbsorption(Block.getIdFromBlock(block), block, meta, rawOpacity, axisDir);
    }

    public static int getDirectionalAbsorption(final int blockId, final Block block, final int meta, final int rawOpacity, final int axisDir) {
        if (block instanceof FaceLightOcclusion) {
            return ((FaceLightOcclusion) block).getDirectionalLightAbsorption(meta, AXIS_TO_FORGE[axisDir]);
        }
        if (isFaceSolid(faceBits(blockId), meta, axisDir)) {
            return TranslucencyRegistry.getPackedAbsorptionNoInterface(blockId, block, meta);
        }
        // A non-solid face still decays 1 per channel.
        return PackedColorLight.pack(1, 1, 1);
    }

    /** Full absorption resolution: cached non-directional > positional interface > sided transparency > registry/vanilla. */
    public static int resolveAbsorption(final IBlockAccess world, final int blockId, final Block block, final int meta, final int dirOrdinal, final int x,
        final int y, final int z) {
        final int cached = TranslucencyRegistry.getPackedAbsorptionCached(blockId, meta);
        if (cached >= 0) return cached;

        if (block instanceof PositionalColoredTranslucency) {
            return PackedColorLight.transmittanceToAbsorption(((PositionalColoredTranslucency) block).getColoredTransmittance(world, meta, x, y, z));
        }

        final int rawOpacity = block.getLightOpacity();
        if (rawOpacity > 1 && hasSidedTransparency(blockId)) {
            return getDirectionalAbsorption(blockId, block, meta, rawOpacity, dirOrdinal);
        }

        return TranslucencyRegistry.getPackedAbsorptionNoInterface(blockId, block, meta);
    }

    /** Scalar counterpart of resolveAbsorption: vanilla opacity and face checks only, no TranslucencyRegistry. */
    public static int resolveScalarAbsorption(final int blockId, final Block block, final int meta, final int dirOrdinal, final int x, final int y,
        final int z) {
        if (block instanceof FaceLightOcclusion) {
            return Math.max(1, PackedColorLight.maxComponent(((FaceLightOcclusion) block).getDirectionalLightAbsorption(meta, AXIS_TO_FORGE[dirOrdinal])));
        }
        final int opacity = block.getLightOpacity();
        if (opacity > 1 && hasSidedTransparency(blockId)) {
            return isFaceSolid(faceBits(blockId), meta, dirOrdinal) ? opacity : 1;
        }
        return Math.max(1, opacity);
    }

    /** Rebuilds the tables from a full block-registry scan; safe to re-run, readers see the old tables until the swap. */
    @SuppressWarnings("unchecked")
    public static void registerDefaults() {
        int count = 0;
        TranslucencyRegistry.clearUncacheable();
        final FakeBlockAccess fake = new FakeBlockAccess();
        final BitSet sidedTransparency = new BitSet();
        long[][] faceSolidity = new long[0][];

        for (final Block block : (Iterable<Block>) Block.blockRegistry) {
            final int id = Block.getIdFromBlock(block);
            if (id < 0) continue;

            // Interface implementors resolve dynamically; the BitSet alone routes them there.
            if (block instanceof FaceLightOcclusion) {
                sidedTransparency.set(id);
                count++;
                continue;
            }

            if (block.isOpaqueCube() || block.getLightOpacity() <= 0) {
                continue;
            }

            fake.setBlock(block);
            boolean anySidedDifference = false;
            long bits0 = 0, bits1 = 0;

            for (int meta = 0; meta < 16; meta++) {
                fake.setMeta(meta);
                for (int dir = 0; dir < 6; dir++) {
                    final boolean solid = block.isSideSolid(fake, 0, 0, 0, AXIS_TO_FORGE[dir]);
                    if (solid) {
                        final int bitIndex = meta * 6 + dir;
                        if (bitIndex < 64) {
                            bits0 |= 1L << bitIndex;
                        } else {
                            bits1 |= 1L << (bitIndex - 64);
                        }
                    } else {
                        anySidedDifference = true;
                    }
                }
            }

            if (anySidedDifference) {
                if (id >= faceSolidity.length) {
                    final long[][] old = faceSolidity;
                    faceSolidity = new long[Math.max(id + 1, old.length * 2)][];
                    System.arraycopy(old, 0, faceSolidity, 0, old.length);
                }
                faceSolidity[id] = new long[] { bits0, bits1 };
                sidedTransparency.set(id);
                count++;
            }
        }

        TABLES = new Tables(faceSolidity, sidedTransparency);

        Supernova.LOG.info("FaceOcclusion: registered {} blocks with per-face transparency", count);

        // Directional absorption cannot be flattened into the per-meta absorption cache.
        for (int id = sidedTransparency.nextSetBit(0); id >= 0; id = sidedTransparency.nextSetBit(id + 1)) {
            TranslucencyRegistry.markUncacheable(id);
        }
    }

    /** isSideSolid probe world: the configured block/meta at (0,0,0), air everywhere else. */
    private static final class FakeBlockAccess implements IBlockAccess {

        private Block block = Blocks.air;
        private int meta;

        void setBlock(final Block block) {this.block = block;}

        void setMeta(final int meta) {this.meta = meta;}

        @Override
        public Block getBlock(int x, int y, int z) {
            return (x == 0 && y == 0 && z == 0) ? block : Blocks.air;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {return null;}

        @Override
        public int getLightBrightnessForSkyBlocks(int x, int y, int z, int lb) {return 0;}

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return (x == 0 && y == 0 && z == 0) ? meta : 0;
        }

        @Override
        public int isBlockProvidingPowerTo(int x, int y, int z, int side) {return 0;}

        @Override
        public boolean isAirBlock(int x, int y, int z) {
            return !(x == 0 && y == 0 && z == 0);
        }

        @Override
        public BiomeGenBase getBiomeGenForCoords(int x, int z) {return BiomeGenBase.plains;}

        @Override
        public int getHeight() {return 256;}

        @Override
        public boolean extendedLevelsInChunkCache() {return false;}

        @Override
        public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean def) {
            if (x == 0 && y == 0 && z == 0) {
                return block.isSideSolid(this, x, y, z, side);
            }
            return def;
        }
    }
}
