package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.FaceLightOcclusion;
import com.mitchej123.supernova.api.ColoredLightSource;
import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.light.engine.MCBootstrap.TestIds;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.util.ForgeDirection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.mitchej123.supernova.api.PackedColorLight.blue;
import static com.mitchej123.supernova.api.PackedColorLight.green;
import static com.mitchej123.supernova.api.PackedColorLight.pack;
import static com.mitchej123.supernova.api.PackedColorLight.red;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEngineBFSTest {

    public static class GreenLamp extends Block implements ColoredLightSource {
        public GreenLamp() {super(Material.rock);}

        @Override
        public int getColoredLightEmission(final int meta) {return pack(0, 15, 0);}
    }

    enum EngineKind {
        BLOCK {
            @Override
            RGBEngineAccess create() {
                return new TestableBlockEngine(MCBootstrap.getServerWorld());
            }
        },
        SKY {
            @Override
            RGBEngineAccess create() {
                return new TestableSkyEngine(MCBootstrap.getServerWorld());
            }
        };

        abstract RGBEngineAccess create();
    }

    private static RGBEngineAccess centered(EngineKind kind) {
        return BFSTestHelper.centeredWithAir(kind.create());
    }

    private static TestableBlockEngine neighborhoodEngine() {
        return BFSTestHelper.centeredNeighborhood(new TestableBlockEngine(MCBootstrap.getServerWorld()));
    }

    @Test
    void sectionPackingKeepsGreenAndBlueWhenRedHasNoStorage() {
        final TestableBlockEngine engine = neighborhoodEngine();
        final int section = BFSTestHelper.sectionIndex(engine, 0, 4, 0);
        final int local = BFSTestHelper.localIndex(15, 68, 8);
        engine.getNibbleCacheG()[section].set(local, 12);
        engine.getNibbleCacheB()[section].set(local, 8);

        assertEquals(pack(0, 12, 8), engine.packSection(section)[local]);
    }

    @Test
    void edgeCheckPropagatesGreenLightWithUninitialisedRed() {
        final TestableBlockEngine engine = neighborhoodEngine();
        final Block lamp = MCBootstrap.registerTestBlock(TestIds.GREEN_EDGE_LAMP, "green_edge_lamp", GreenLamp.class, 0, false, 0);
        BFSTestHelper.setBlock(engine, 15, 68, 8, lamp);
        final int section = BFSTestHelper.sectionIndex(engine, 0, 4, 0);
        engine.getNibbleCacheG()[section].set(BFSTestHelper.localIndex(15, 68, 8), 15);

        engine.callCheckChunkEdge(0, 4, 0);
        engine.callPerformLightDecrease();

        assertEquals(14, green(engine.getLightAt(16, 68, 8)));
        assertEquals(0, red(engine.getLightAt(16, 68, 8)));
    }

    @ParameterizedTest
    @EnumSource(EngineKind.class)
    void singleSourceFalloffToRangeEnd(EngineKind kind) {
        final RGBEngineAccess e = BFSTestHelper.centeredNeighborhood(kind.create());
        BFSTestHelper.enqueueIncrease(e, 8, 68, 8, 15, 0, 0);
        e.callPerformLightIncrease();

        for (int d = 0; d <= 15; d++) {
            assertLight(e, 8 + d, 68, 8, 15 - d, 0, 0);
        }
        assertLight(e, 8, 69, 8, 14, 0, 0);
        assertLight(e, 8, 67, 8, 14, 0, 0);
    }

    @ParameterizedTest
    @EnumSource(EngineKind.class)
    void opaqueBlockBlocksAndLightRoutesAround(EngineKind kind) {
        final RGBEngineAccess e = centered(kind);
        BFSTestHelper.setBlock(e, 10, 68, 8, Blocks.stone);
        BFSTestHelper.enqueueIncrease(e, 8, 68, 8, 15, 15, 15);
        e.callPerformLightIncrease();

        assertLight(e, 9, 68, 8, 14, 14, 14);
        assertLight(e, 10, 68, 8, 0, 0, 0);
        // Shortest detour around the stone is 5 steps.
        assertLight(e, 11, 68, 8, 10, 10, 10);
        assertTrue(red(BFSTestHelper.getLight(e, 8, 68, 10)) > 0, "light should go +Z");
        assertTrue(red(BFSTestHelper.getLight(e, 8, 69, 8)) > 0, "light should go +Y");
    }

    @ParameterizedTest
    @EnumSource(EngineKind.class)
    void nullNibbleIsSkipped(EngineKind kind) {
        final RGBEngineAccess e = centered(kind);
        // Section 5 is unpopulated.
        BFSTestHelper.enqueueIncrease(e, 8, 78, 8, 15, 15, 15);
        e.callPerformLightIncrease();

        assertLight(e, 8, 78, 8, 15, 15, 15);
        assertLight(e, 8, 77, 8, 14, 14, 14);
    }

    @ParameterizedTest
    @EnumSource(EngineKind.class)
    void lightCrossesSectionBoundary(EngineKind kind) {
        final RGBEngineAccess e = centered(kind);
        BFSTestHelper.populateAirSection(e, 0, 5, 0);

        BFSTestHelper.enqueueIncrease(e, 8, 79, 8, 15, 15, 15);
        e.callPerformLightIncrease();

        assertLight(e, 8, 79, 8, 15, 15, 15);
        assertLight(e, 8, 78, 8, 14, 14, 14);
        assertLight(e, 8, 80, 8, 14, 14, 14);
        assertLight(e, 8, 81, 8, 13, 13, 13);
    }

    @Test
    void nullSectionTreatedAsAir() {
        final TestableBlockEngine engine = BFSTestHelper.centeredWithAir(new TestableBlockEngine(MCBootstrap.getServerWorld()));
        engine.getSectionCache()[BFSTestHelper.sectionIndex(engine, 0, 4, 0)] = null;

        BFSTestHelper.enqueueIncrease(engine, 8, 68, 8, 15, 15, 15);
        engine.callPerformLightIncrease();

        assertLight(engine, 8, 68, 8, 15, 15, 15);
        assertLight(engine, 9, 68, 8, 14, 14, 14);
    }

    @Test
    void decreaseMultiColorDoesNotExplode() {
        final TestableBlockEngine engine = neighborhoodEngine();

        BFSTestHelper.enqueueIncrease(engine, 4, 68, 8, 15, 0, 0);
        BFSTestHelper.enqueueIncrease(engine, 8, 68, 4, 0, 15, 0);
        BFSTestHelper.enqueueIncrease(engine, 12, 68, 8, 0, 0, 15);
        engine.callPerformLightIncrease();

        BFSTestHelper.setLight(engine, 4, 68, 8, 0, 0, 0);
        BFSTestHelper.enqueueDecrease(engine, 4, 68, 8, 15, 0, 0);
        engine.lastBfsDecreaseTotal = 0;
        engine.lastBfsIncreaseTotal = 0;
        engine.callPerformLightDecrease();

        for (int d = 0; d <= 14; d++) {
            assertEquals(0, red(BFSTestHelper.getLight(engine, 4 + d, 68, 8)), "red should be cleared at distance " + d);
        }

        assertEquals(0, red(BFSTestHelper.getLight(engine, 8, 68, 4)), "red at green source");
        assertEquals(15, green(BFSTestHelper.getLight(engine, 8, 68, 4)), "green at green source");
        assertEquals(0, red(BFSTestHelper.getLight(engine, 12, 68, 8)), "red at blue source");
        assertEquals(15, blue(BFSTestHelper.getLight(engine, 12, 68, 8)), "blue at blue source");

        // Radius-15 Manhattan ball is ~15k cells.
        assertTrue(engine.lastBfsDecreaseTotal < 30_000, "decrease BFS should be bounded, was: " + engine.lastBfsDecreaseTotal);
    }

    /** Opaque only on +Y. */
    private static final class UpOccluder extends Block implements FaceLightOcclusion {

        private UpOccluder() {
            super(Material.rock);
        }

        @Override
        public int getDirectionalLightOpacity(int meta, ForgeDirection direction) {
            return direction == ForgeDirection.UP ? 255 : 0;
        }
    }

    @Test
    void faceOcclusionImplementorGatesSourceFaces() {
        final Block occluder = MCBootstrap.registerTestBlock(TestIds.FACE_OCCLUDER, "test_face_occluder", UpOccluder.class, 255, false, 0);
        FaceOcclusion.registerDefaults();

        final int id = Block.getIdFromBlock(occluder);
        assertTrue(FaceOcclusion.hasSidedTransparency(id), "implementor must be marked sided");
        assertNull(FaceOcclusion.sidedFaceBits(id), "implementor must get no faceSolidity row");

        final RGBEngineAccess e = centered(EngineKind.BLOCK);
        BFSTestHelper.setBlock(e, 8, 68, 8, occluder);
        // Boxes in the cell above so its only unblocked neighbor is the occluder's +Y face.
        BFSTestHelper.setBlock(e, 7, 69, 8, Blocks.stone);
        BFSTestHelper.setBlock(e, 9, 69, 8, Blocks.stone);
        BFSTestHelper.setBlock(e, 8, 69, 7, Blocks.stone);
        BFSTestHelper.setBlock(e, 8, 69, 9, Blocks.stone);
        BFSTestHelper.setBlock(e, 8, 70, 8, Blocks.stone);

        BFSTestHelper.enqueueIncrease(e, 8, 68, 8, 15, 15, 15);
        e.callPerformLightIncrease();

        assertLight(e, 8, 69, 8, 0, 0, 0);
        assertLight(e, 9, 68, 8, 14, 14, 14);
        assertLight(e, 8, 67, 8, 14, 14, 14);
    }

    static void assertLight(RGBEngineAccess engine, int x, int y, int z, int expectedR, int expectedG, int expectedB) {
        final int light = BFSTestHelper.getLight(engine, x, y, z);
        assertEquals(expectedR, red(light), "R at (" + x + "," + y + "," + z + ")");
        assertEquals(expectedG, green(light), "G at (" + x + "," + y + "," + z + ")");
        assertEquals(expectedB, blue(light), "B at (" + x + "," + y + "," + z + ")");
    }

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    class EmitterScan {

        private Block lamp;
        private Block lampRemap;
        private Block lampNeg;

        @BeforeAll
        void registerProbes() {
            lamp = MCBootstrap.registerTestBlock(TestIds.LAMP, "test_lamp", Block.class, 0, false, 0);
            LightColorRegistry.register(lamp, 13, 15, 0, 0);

            lampRemap = MCBootstrap.registerTestBlock(TestIds.LAMP_REMAP, "test_lamp_remap", Block.class, 0, false, 0);
            LightColorRegistry.register(lampRemap, 13, 15, 0, 15);
            MCBootstrap.rebindBlock(TestIds.LAMP_REMAP_TARGET, "test_lamp_remap", lampRemap);
            LightColorRegistry.rebuildIdMappings();

            lampNeg = MCBootstrap.registerTestBlock(TestIds.LAMP_NEG, "test_lamp_neg", Block.class, 0, false, 0);
            LightColorRegistry.register(lampNeg, 13, 0, 15, 0);
        }

        private TestableBlockEngine engine;

        @BeforeEach
        void setup() {
            engine = neighborhoodEngine();
        }

        @Test
        void lightChunkScanFindsZeroBaseLightRegisteredEmitter() {
            assertEquals(0, lamp.getLightValue(), "test block must have zero base light value");
            assertTrue(LightColorRegistry.hasExplicitEntry(lamp));

            final Chunk chunk = chunkWithLampAt(engine, 8, 56, 8, lamp, 13);

            engine.callLightChunk(chunk, false);

            assertEquals(pack(15, 0, 0), BFSTestHelper.getLight(engine, 8, 56, 8) & 0x0F0F0F, "emitter cell");
            assertEquals(pack(14, 0, 0), BFSTestHelper.getLight(engine, 9, 56, 8) & 0x0F0F0F, "adjacent cell");
        }

        @Test
        void lightChunkScanFindsEmitterAfterIdRemap() {
            assertTrue(LightColorRegistry.hasExplicitEntry(lampRemap), "entry must survive the remap");

            final Chunk chunk = chunkWithLampAt(engine, 8, 56, 8, lampRemap, 13);

            engine.callLightChunk(chunk, false);

            assertEquals(pack(15, 0, 15), BFSTestHelper.getLight(engine, 8, 56, 8) & 0x0F0F0F, "emitter cell after remap");
            assertEquals(pack(14, 0, 14), BFSTestHelper.getLight(engine, 9, 56, 8) & 0x0F0F0F, "adjacent cell after remap");
        }

        @Test
        void lightChunkScanFindsEmitterInNegativeCoordChunk() {
            assertTrue(LightColorRegistry.hasExplicitEntry(lampNeg));

            final TestableBlockEngine negEngine = new TestableBlockEngine(MCBootstrap.getServerWorld());
            negEngine.callSetupEncodeOffset(-21 * 16 + 7, 64, -35 * 16 + 7);
            BFSTestHelper.populateNeighborhood(negEngine, -22, -20, -36, -34, 2, 4);

            final Chunk chunk = chunkWithLampAt(negEngine, -326, 56, -554, lampNeg, 13);

            negEngine.callLightChunk(chunk, false);

            assertEquals(pack(0, 15, 0), BFSTestHelper.getLight(negEngine, -326, 56, -554) & 0x0F0F0F, "emitter cell");
            assertEquals(pack(0, 14, 0), BFSTestHelper.getLight(negEngine, -325, 56, -554) & 0x0F0F0F, "adjacent cell");
        }

        /** Shares the engine cache's section object, which lightChunk scans. */
        private Chunk chunkWithLampAt(TestableBlockEngine engine, int worldX, int worldY, int worldZ, Block lamp, int meta) {
            final Chunk chunk = new Chunk(MCBootstrap.getServerWorld(), worldX >> 4, worldZ >> 4);
            final ExtendedBlockStorage section = engine.getSectionCache()[BFSTestHelper.sectionIndex(engine, worldX >> 4, worldY >> 4, worldZ >> 4)];
            section.func_150818_a(worldX & 15, worldY & 15, worldZ & 15, lamp);
            section.setExtBlockMetadata(worldX & 15, worldY & 15, worldZ & 15, meta);
            chunk.getBlockStorageArray()[worldY >> 4] = section;
            return chunk;
        }
    }

    @Nested
    class InitialLight {

        private static final int ORANGE_R = 15, ORANGE_G = 12, ORANGE_B = 10;

        private TestableBlockEngine engine;
        private Chunk centerChunk;

        @BeforeEach
        void setup() {
            engine = neighborhoodEngine();
            centerChunk = new Chunk(MCBootstrap.getServerWorld(), 0, 0);
        }

        private void runCenterInitialLightFlow() {
            engine.callPropagateNeighbourLevels(centerChunk, 3, 5);
            engine.callPerformLightIncrease();
        }

        private int countHueViolations(int minX, int maxX, int minZ, int maxZ) {
            int count = 0;
            for (int x = minX; x <= maxX; x++) {
                for (int y = 48; y <= 95; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        final int light = BFSTestHelper.getLight(engine, x, y, z);
                        if (green(light) > red(light) || blue(light) > green(light)) count++;
                    }
                }
            }
            return count;
        }

        /** Seeds are not hue-ordered and sit 22 apart, out of each other's reach. */
        @Test
        void neighborBorderSeedsEachChannelIndependently() {
            BFSTestHelper.setLight(engine, -1, 68, 8, 2, 3, 0);
            BFSTestHelper.setLight(engine, -1, 52, 2, 8, 10, 0);

            runCenterInitialLightFlow();

            assertLight(engine, 0, 68, 8, 1, 2, 0);
            assertLight(engine, 1, 68, 8, 0, 1, 0);
            assertLight(engine, 0, 67, 8, 0, 1, 0);
            assertLight(engine, 0, 69, 8, 0, 1, 0);
            assertLight(engine, 0, 68, 7, 0, 1, 0);
            assertLight(engine, 0, 68, 9, 0, 1, 0);
            assertLight(engine, 2, 68, 8, 0, 0, 0);

            assertLight(engine, 0, 52, 2, 7, 9, 0);
            assertLight(engine, 5, 52, 2, 2, 4, 0);
            assertLight(engine, 8, 52, 2, 0, 1, 0);
            assertLight(engine, 9, 52, 2, 0, 0, 0);
        }

        @Test
        void poolPlusLegalNeighborSeedingKeepsHueOrderingAndExactField() {
            final int srcX = -8, srcY = 68, srcZ = 8;
            for (int x = -16; x <= -1; x++) {
                for (int y = 54; y <= 82; y++) {
                    for (int z = 0; z <= 15; z++) {
                        final int d = Math.abs(x - srcX) + Math.abs(y - srcY) + Math.abs(z - srcZ);
                        final int r = Math.max(ORANGE_R - d, 0);
                        final int g = Math.max(ORANGE_G - d, 0);
                        final int b = Math.max(ORANGE_B - d, 0);
                        if ((r | g | b) != 0) BFSTestHelper.setLight(engine, x, y, z, r, g, b);
                    }
                }
            }

            for (int px = 6; px <= 11; px++) {
                for (int pz = 6; pz <= 11; pz++) {
                    BFSTestHelper.enqueueIncrease(engine, px, 68, pz, ORANGE_R, ORANGE_G, ORANGE_B);
                }
            }

            runCenterInitialLightFlow();

            for (int x = 0; x <= 15; x++) {
                for (int y = 48; y <= 95; y++) {
                    for (int z = 0; z <= 15; z++) {
                        final int dWest = Math.abs(x - srcX) + Math.abs(y - srcY) + Math.abs(z - srcZ);
                        final int dx = Math.max(Math.max(6 - x, x - 11), 0);
                        final int dz = Math.max(Math.max(6 - z, z - 11), 0);
                        final int dPool = dx + Math.abs(y - 68) + dz;
                        final int expR = Math.max(Math.max(ORANGE_R - dWest, ORANGE_R - dPool), 0);
                        final int expG = Math.max(Math.max(ORANGE_G - dWest, ORANGE_G - dPool), 0);
                        final int expB = Math.max(Math.max(ORANGE_B - dWest, ORANGE_B - dPool), 0);
                        assertLight(engine, x, y, z, expR, expG, expB);
                    }
                }
            }
        }

        @Test
        void lowLevelMergeWritesWithoutReenqueueStaysExact() {
            BFSTestHelper.enqueueIncrease(engine, 8, 68, 8, 2, 0, 0);
            BFSTestHelper.enqueueIncrease(engine, 9, 68, 8, 0, 1, 0);
            engine.callPerformLightIncrease();

            assertLight(engine, 9, 68, 8, 1, 1, 0);
            assertLight(engine, 7, 68, 8, 1, 0, 0);
            assertLight(engine, 8, 68, 9, 1, 0, 0);
            assertLight(engine, 8, 68, 7, 1, 0, 0);
            assertLight(engine, 8, 67, 8, 1, 0, 0);
            assertLight(engine, 8, 69, 8, 1, 0, 0);
            assertLight(engine, 10, 68, 8, 0, 0, 0);
            assertEquals(pack(2, 0, 0), BFSTestHelper.getLight(engine, 8, 68, 8) & 0x0F0F0F);
        }

        @Test
        void thresholdBorderValuesSeedExactlyOrNotAtAll() {
            BFSTestHelper.setLight(engine, -1, 68, 4, 2, 2, 1);
            BFSTestHelper.setLight(engine, -1, 68, 12, 1, 1, 1);

            runCenterInitialLightFlow();

            assertLight(engine, 0, 68, 4, 1, 1, 0);
            assertLight(engine, 1, 68, 4, 0, 0, 0);
            assertLight(engine, 0, 68, 12, 0, 0, 0);
            assertEquals(0, countHueViolations(-16, 31, -16, 31));
        }
    }
}
