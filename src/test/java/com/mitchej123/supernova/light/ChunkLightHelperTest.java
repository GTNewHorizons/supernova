package com.mitchej123.supernova.light;

import com.mitchej123.supernova.light.engine.TestSection;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLightHelperTest {

    // Bounds (0,15) give minLightSection=-1, so section Y=4 is nibble index 5.
    private static final int TOTAL_LIGHT_SECTIONS = 18;
    private static final int TEST_SECTION_Y = 4;
    private static final int TEST_NIBBLE_IDX = 5;

    private static ExtendedBlockStorage[] makeStorageArrays() {
        ExtendedBlockStorage[] arr = new ExtendedBlockStorage[16];
        arr[TEST_SECTION_Y] = new TestSection(TEST_SECTION_Y << 4, true);
        return arr;
    }

    private static SWMRNibbleArray[] makeNullNibbles() {
        SWMRNibbleArray[] arr = new SWMRNibbleArray[TOTAL_LIGHT_SECTIONS];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = NibbleStates.nullNibble();
        }
        return arr;
    }

    private static SWMRNibbleArray[] makeEmptyNibbles() {
        SWMRNibbleArray[] arr = new SWMRNibbleArray[TOTAL_LIGHT_SECTIONS];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = NibbleStates.uninit();
        }
        return arr;
    }

    @Nested
    class HasSavedBlockData {

        @Test
        void returnsFalseWhenAllNull() {
            SWMRNibbleArray[] blockR = makeNullNibbles();
            assertFalse(ChunkLightHelper.hasSavedBlockData(blockR, makeStorageArrays()));
        }

        @Test
        void returnsTrueWhenSectionHasData() {
            SWMRNibbleArray[] blockR = makeNullNibbles();
            blockR[TEST_NIBBLE_IDX] = NibbleStates.lit(0, 0, 0, 5);
            assertTrue(ChunkLightHelper.hasSavedBlockData(blockR, makeStorageArrays()));
        }

        @Test
        void ignoresSectionsWithoutStorage() {
            SWMRNibbleArray[] blockR = makeEmptyNibbles();
            ExtendedBlockStorage[] storage = new ExtendedBlockStorage[16];
            assertFalse(ChunkLightHelper.hasSavedBlockData(blockR, storage));
        }
    }

    @Nested
    class ImportVanillaSky {

        @Test
        void importsUnconditionally() {
            SWMRNibbleArray[] skyR = makeNullNibbles();
            SWMRNibbleArray[] skyG = makeNullNibbles();
            SWMRNibbleArray[] skyB = makeNullNibbles();
            ExtendedBlockStorage[] storage = makeStorageArrays();

            storage[TEST_SECTION_Y].getSkylightArray().set(3, 5, 7, 12);

            ChunkLightHelper.importVanillaSky(skyR, skyG, skyB, storage, false);

            assertEquals(12, skyR[TEST_NIBBLE_IDX].getVisible(3, 5, 7));
            assertEquals(12, skyG[TEST_NIBBLE_IDX].getVisible(3, 5, 7));
            assertEquals(12, skyB[TEST_NIBBLE_IDX].getVisible(3, 5, 7));
        }

        @Test
        void onlyWhereNullSkipsExistingData() {
            SWMRNibbleArray[] skyR = makeNullNibbles();
            SWMRNibbleArray[] skyG = makeNullNibbles();
            SWMRNibbleArray[] skyB = makeNullNibbles();

            skyR[TEST_NIBBLE_IDX] = NibbleStates.lit(3, 5, 7, 9);

            ExtendedBlockStorage[] storage = makeStorageArrays();
            storage[TEST_SECTION_Y].getSkylightArray().set(3, 5, 7, 12);

            ChunkLightHelper.importVanillaSky(skyR, skyG, skyB, storage, true);

            assertEquals(9, skyR[TEST_NIBBLE_IDX].getVisible(3, 5, 7));
        }

        @Test
        void onlyWhereNullImportsNullSections() {
            SWMRNibbleArray[] skyR = makeNullNibbles();
            SWMRNibbleArray[] skyG = makeNullNibbles();
            SWMRNibbleArray[] skyB = makeNullNibbles();
            ExtendedBlockStorage[] storage = makeStorageArrays();
            storage[TEST_SECTION_Y].getSkylightArray().set(0, 0, 0, 10);

            ChunkLightHelper.importVanillaSky(skyR, skyG, skyB, storage, true);

            assertEquals(10, skyR[TEST_NIBBLE_IDX].getVisible(0, 0, 0));
        }

        @Test
        void handlesNullGBArrays() {
            SWMRNibbleArray[] skyR = makeNullNibbles();
            ExtendedBlockStorage[] storage = makeStorageArrays();
            storage[TEST_SECTION_Y].getSkylightArray().set(1, 2, 3, 8);

            ChunkLightHelper.importVanillaSky(skyR, null, null, storage, false);

            assertEquals(8, skyR[TEST_NIBBLE_IDX].getVisible(1, 2, 3));
        }
    }

    @Nested
    class ImportVanillaBlock {

        @Test
        void importsBlockLight() {
            SWMRNibbleArray[] blockR = makeNullNibbles();
            SWMRNibbleArray[] blockG = makeNullNibbles();
            SWMRNibbleArray[] blockB = makeNullNibbles();
            ExtendedBlockStorage[] storage = makeStorageArrays();
            storage[TEST_SECTION_Y].getBlocklightArray().set(2, 3, 4, 14);

            ChunkLightHelper.importVanillaBlock(blockR, blockG, blockB, storage);

            assertEquals(14, blockR[TEST_NIBBLE_IDX].getVisible(2, 3, 4));
            assertEquals(14, blockG[TEST_NIBBLE_IDX].getVisible(2, 3, 4));
            assertEquals(14, blockB[TEST_NIBBLE_IDX].getVisible(2, 3, 4));
        }
    }

    @Nested
    class SyncSkyToVanilla {

        @Test
        void copiesVisibleDataToVanilla() {
            SWMRNibbleArray[] skyNibbles = makeEmptyNibbles();
            skyNibbles[TEST_NIBBLE_IDX] = NibbleStates.lit(5, 10, 3, 11);

            ExtendedBlockStorage[] storage = makeStorageArrays();
            ChunkLightHelper.syncSkyToVanilla(skyNibbles, null, null, storage);

            assertEquals(11, storage[TEST_SECTION_Y].getSkylightArray().get(5, 10, 3));
        }

        @Test
        void publishesMaxOfChannels() {
            SWMRNibbleArray[] r = makeEmptyNibbles();
            SWMRNibbleArray[] g = makeEmptyNibbles();
            SWMRNibbleArray[] b = makeEmptyNibbles();
            r[TEST_NIBBLE_IDX] = NibbleStates.lit(1, 2, 3, 4);
            g[TEST_NIBBLE_IDX] = NibbleStates.lit(1, 2, 3, 9);
            b[TEST_NIBBLE_IDX] = NibbleStates.lit(1, 2, 3, 6);

            ExtendedBlockStorage[] storage = makeStorageArrays();
            ChunkLightHelper.syncSkyToVanilla(r, g, b, storage);

            assertEquals(9, storage[TEST_SECTION_Y].getSkylightArray().get(1, 2, 3));
        }

        @Test
        void uninitialisedRedDoesNotDiscardGreenSky() {
            final SWMRNibbleArray[] r = makeEmptyNibbles();
            final SWMRNibbleArray[] g = makeEmptyNibbles();
            g[TEST_NIBBLE_IDX] = NibbleStates.lit(1, 2, 3, 9);
            final ExtendedBlockStorage[] storage = makeStorageArrays();

            ChunkLightHelper.syncSkyToVanilla(r, g, null, storage);

            assertEquals(9, storage[TEST_SECTION_Y].getSkylightArray().get(1, 2, 3));
        }

        @Test
        void leavesVanillaUntouchedForNullNibbles() {
            SWMRNibbleArray[] skyNibbles = makeNullNibbles();
            ExtendedBlockStorage[] storage = makeStorageArrays();

            NibbleArray vanilla = storage[TEST_SECTION_Y].getSkylightArray();
            java.util.Arrays.fill(vanilla.data, (byte) 0x34);

            ChunkLightHelper.syncSkyToVanilla(skyNibbles, null, null, storage);

            for (byte b : vanilla.data) {
                assertEquals((byte) 0x34, b, "NULL nibble must not overwrite vanilla sky data");
            }
            assertFalse(storage[TEST_SECTION_Y].isEmpty(), "provider-owned nontrivial sky still needs a packet section");
        }

        @Test
        void fillsZeroForUninitNibbles() {
            SWMRNibbleArray[] skyNibbles = makeEmptyNibbles();
            ExtendedBlockStorage[] storage = makeStorageArrays();

            NibbleArray vanilla = storage[TEST_SECTION_Y].getSkylightArray();
            java.util.Arrays.fill(vanilla.data, (byte) 0xFF);

            ChunkLightHelper.syncSkyToVanilla(skyNibbles, null, null, storage);

            for (byte b : vanilla.data) {
                assertEquals((byte) 0, b, "UNINIT nibble means all-dark and must zero vanilla sky data");
            }
        }
    }

    @Nested
    class SyncBlockToVanilla {

        @Test
        void computesMaxRGB() {
            SWMRNibbleArray[] blockR = makeEmptyNibbles();
            SWMRNibbleArray[] blockG = makeEmptyNibbles();
            SWMRNibbleArray[] blockB = makeEmptyNibbles();

            blockR[TEST_NIBBLE_IDX] = NibbleStates.lit(1, 2, 3, 5);
            blockG[TEST_NIBBLE_IDX] = NibbleStates.lit(1, 2, 3, 12);
            blockB[TEST_NIBBLE_IDX] = NibbleStates.lit(1, 2, 3, 8);

            ExtendedBlockStorage[] storage = makeStorageArrays();
            ChunkLightHelper.syncBlockToVanilla(blockR, blockG, blockB, storage);

            assertEquals(12, storage[TEST_SECTION_Y].getBlocklightArray().get(1, 2, 3));
        }

        @Test
        void handlesNullGBArrays() {
            SWMRNibbleArray[] blockR = makeEmptyNibbles();
            blockR[TEST_NIBBLE_IDX] = NibbleStates.lit(4, 5, 6, 7);

            ExtendedBlockStorage[] storage = makeStorageArrays();
            ChunkLightHelper.syncBlockToVanilla(blockR, null, null, storage);

            assertEquals(7, storage[TEST_SECTION_Y].getBlocklightArray().get(4, 5, 6));
        }

        @Test
        void greenOnlyLightKeepsEmptySectionUntilRemoved() {
            final SWMRNibbleArray[] blockR = makeEmptyNibbles();
            final SWMRNibbleArray[] blockG = makeEmptyNibbles();
            final SWMRNibbleArray[] blockB = makeEmptyNibbles();
            final ExtendedBlockStorage[] storage = makeStorageArrays();
            blockG[TEST_NIBBLE_IDX] = NibbleStates.lit(1, 2, 3, 12);

            ChunkLightHelper.syncBlockToVanilla(blockR, blockG, blockB, storage);
            assertEquals(12, storage[TEST_SECTION_Y].getBlocklightArray().get(1, 2, 3));
            assertFalse(storage[TEST_SECTION_Y].isEmpty());

            blockG[TEST_NIBBLE_IDX] = NibbleStates.uninit();
            ChunkLightHelper.syncBlockToVanilla(blockR, blockG, blockB, storage);
            assertEquals(0, storage[TEST_SECTION_Y].getBlocklightArray().get(1, 2, 3));
            assertTrue(storage[TEST_SECTION_Y].isEmpty());
        }

        @Test
        void skyAndBlockLightClearIndependently() {
            final SWMRNibbleArray[] sky = makeEmptyNibbles();
            final SWMRNibbleArray[] block = makeEmptyNibbles();
            final ExtendedBlockStorage[] storage = makeStorageArrays();
            sky[TEST_NIBBLE_IDX] = NibbleStates.lit(1, 2, 3, 4);
            block[TEST_NIBBLE_IDX] = NibbleStates.lit(1, 2, 3, 8);

            ChunkLightHelper.syncSkyToVanilla(sky, null, null, storage);
            ChunkLightHelper.syncBlockToVanilla(block, null, null, storage);
            assertFalse(storage[TEST_SECTION_Y].isEmpty());

            block[TEST_NIBBLE_IDX] = NibbleStates.uninit();
            ChunkLightHelper.syncBlockToVanilla(block, null, null, storage);
            assertFalse(storage[TEST_SECTION_Y].isEmpty(), "sky still differs from daylight");

            sky[TEST_NIBBLE_IDX] = NibbleStates.full();
            ChunkLightHelper.syncSkyToVanilla(sky, null, null, storage);
            assertTrue(storage[TEST_SECTION_Y].isEmpty());
        }
    }

    @Nested
    class GetBlockLight {

        @Test
        void returnsMaxRGB() {
            SWMRNibbleArray[] blockR = makeEmptyNibbles();
            SWMRNibbleArray[] blockG = makeEmptyNibbles();
            SWMRNibbleArray[] blockB = makeEmptyNibbles();

            blockR[TEST_NIBBLE_IDX] = NibbleStates.lit(7, 4, 9, 3);
            blockG[TEST_NIBBLE_IDX] = NibbleStates.lit(7, 4, 9, 14);
            blockB[TEST_NIBBLE_IDX] = NibbleStates.lit(7, 4, 9, 6);

            assertEquals(14, ChunkLightHelper.getBlockLight(blockR, blockG, blockB, 7, 68, 9));
        }

        @Test
        void scalarModeReturnsROnly() {
            SWMRNibbleArray[] blockR = makeEmptyNibbles();
            blockR[TEST_NIBBLE_IDX] = NibbleStates.lit(0, 4, 0, 10);

            assertEquals(10, ChunkLightHelper.getBlockLight(blockR, null, null, 0, 68, 0));
        }

        @Test
        void returnsZeroForOutOfRange() {
            SWMRNibbleArray[] blockR = makeEmptyNibbles();
            assertEquals(0, ChunkLightHelper.getBlockLight(blockR, null, null, 0, -32, 0));
            assertEquals(0, ChunkLightHelper.getBlockLight(blockR, null, null, 0, 300, 0));
        }
    }

    @Nested
    class GetSkyLight {

        @Test
        void returnsMaxRGB() {
            SWMRNibbleArray[] skyR = makeEmptyNibbles();
            SWMRNibbleArray[] skyG = makeEmptyNibbles();
            SWMRNibbleArray[] skyB = makeEmptyNibbles();

            skyR[TEST_NIBBLE_IDX] = NibbleStates.lit(2, 4, 5, 8);
            skyG[TEST_NIBBLE_IDX] = NibbleStates.lit(2, 4, 5, 13);
            skyB[TEST_NIBBLE_IDX] = NibbleStates.lit(2, 4, 5, 5);

            assertEquals(13, ChunkLightHelper.getSkyLight(skyR, skyG, skyB, 2, 68, 5));
        }

        @Test
        void returns15ForAboveMax() {
            SWMRNibbleArray[] skyR = makeEmptyNibbles();
            assertEquals(15, ChunkLightHelper.getSkyLight(skyR, null, null, 0, 300, 0));
        }

        @Test
        void returns0ForBelowMin() {
            SWMRNibbleArray[] skyR = makeEmptyNibbles();
            assertEquals(0, ChunkLightHelper.getSkyLight(skyR, null, null, 0, -32, 0));
        }

        @Test
        void returns15ForNullArray() {
            assertEquals(15, ChunkLightHelper.getSkyLight(null, null, null, 0, 64, 0));
        }

        @Test
        void returns15ForNullNibble() {
            SWMRNibbleArray[] skyR = makeNullNibbles();
            assertEquals(15, ChunkLightHelper.getSkyLight(skyR, null, null, 0, 64, 0));
        }

        @Test
        void returns0ForUninitNibble() {
            SWMRNibbleArray[] skyR = makeEmptyNibbles();
            assertEquals(0, ChunkLightHelper.getSkyLight(skyR, null, null, 0, 64, 0), "UNINIT means all-dark, not full sky");
        }

        @Test
        void scalarModeReturnsROnly() {
            SWMRNibbleArray[] skyR = makeEmptyNibbles();
            skyR[TEST_NIBBLE_IDX] = NibbleStates.lit(0, 4, 0, 7);

            assertEquals(7, ChunkLightHelper.getSkyLight(skyR, null, null, 0, 68, 0));
        }
    }
}
