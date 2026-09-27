package com.mitchej123.supernova.light;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SWMRNibbleArrayTest {

    @Test
    void testDefaultIsUninitialised() {
        SWMRNibbleArray arr = new SWMRNibbleArray();
        assertTrue(arr.isUninitialisedUpdating());
        assertTrue(arr.isUninitialisedVisible());
        assertEquals(0, arr.getUpdating(0, 0, 0));
    }

    @Test
    void testNullNibble() {
        SWMRNibbleArray arr = new SWMRNibbleArray(null, true);
        assertTrue(arr.isNullNibbleUpdating());
        assertTrue(arr.isNullNibbleVisible());
        assertEquals(0, arr.getUpdating(0, 0, 0));
    }

    @Test
    void testSetAndGet() {
        SWMRNibbleArray arr = new SWMRNibbleArray();
        arr.set(3, 5, 7, 12);
        assertEquals(12, arr.getUpdating(3, 5, 7));
        assertEquals(0, arr.getVisible(3, 5, 7));
    }

    @Test
    void testUpdateVisible() {
        SWMRNibbleArray arr = new SWMRNibbleArray();
        arr.set(0, 0, 0, 15);
        assertTrue(arr.isDirty());
        assertTrue(arr.updateVisible());
        assertEquals(15, arr.getVisible(0, 0, 0));
        assertFalse(arr.isDirty());
    }

    @Test
    void testSetFull() {
        SWMRNibbleArray arr = new SWMRNibbleArray();
        arr.setFull();
        for (int i = 0; i < 4096; i++) {
            assertEquals(15, arr.getUpdating(i));
        }
    }

    @Test
    void testSetZero() {
        SWMRNibbleArray arr = new SWMRNibbleArray(new byte[SWMRNibbleArray.ARRAY_SIZE]);
        arr.set(0, 0, 0, 10);
        arr.setZero();
        assertEquals(0, arr.getUpdating(0, 0, 0));
    }

    @Test
    void testNullTransition() {
        SWMRNibbleArray arr = new SWMRNibbleArray();
        assertFalse(arr.isNullNibbleUpdating());
        arr.setNull();
        assertTrue(arr.isNullNibbleUpdating());
    }

    @Test
    void testHiddenState() {
        SWMRNibbleArray arr = new SWMRNibbleArray(new byte[SWMRNibbleArray.ARRAY_SIZE]);
        arr.set(5, 5, 5, 8);
        assertTrue(arr.isInitialisedUpdating());
        arr.setHidden();
        assertTrue(arr.isHiddenUpdating());
        assertEquals(8, arr.getUpdating(5, 5, 5));
    }

    @Test
    void testSaveStateNull() {
        SWMRNibbleArray arr = new SWMRNibbleArray(null, true);
        arr.updateVisible();
        assertNull(arr.getSaveState());
    }

    @Test
    void testSaveStateUninit() {
        SWMRNibbleArray arr = new SWMRNibbleArray();
        arr.updateVisible();
        SWMRNibbleArray.SaveState state = arr.getSaveState();
        assertNotNull(state);
        assertNull(state.data);
    }

    @Test
    void testSaveStateWithData() {
        SWMRNibbleArray arr = new SWMRNibbleArray();
        arr.set(0, 0, 0, 10);
        arr.updateVisible();
        SWMRNibbleArray.SaveState state = arr.getSaveState();
        assertNotNull(state);
        assertNotNull(state.data);
        assertEquals(SWMRNibbleArray.ARRAY_SIZE, state.data.length);
    }

    @Test
    void testAllPositions() {
        SWMRNibbleArray arr = new SWMRNibbleArray();
        arr.set(0, 0, 0, 1);
        arr.set(15, 0, 0, 2);
        arr.set(0, 15, 0, 3);
        arr.set(0, 0, 15, 4);
        arr.set(15, 15, 15, 5);

        assertEquals(1, arr.getUpdating(0, 0, 0));
        assertEquals(2, arr.getUpdating(15, 0, 0));
        assertEquals(3, arr.getUpdating(0, 15, 0));
        assertEquals(4, arr.getUpdating(0, 0, 15));
        assertEquals(5, arr.getUpdating(15, 15, 15));
    }

    @Test
    void testSetNonNull() {
        SWMRNibbleArray arr = new SWMRNibbleArray(null, true);
        assertTrue(arr.isNullNibbleUpdating());
        arr.setNonNull();
        assertTrue(arr.isUninitialisedUpdating());
    }

    @Test
    void testFromBytes() {
        byte[] data = new byte[SWMRNibbleArray.ARRAY_SIZE];
        data[0] = (byte) 0xAB; // index 0 = 0xB, index 1 = 0xA
        SWMRNibbleArray arr = new SWMRNibbleArray(data);
        assertTrue(arr.isInitialisedUpdating());
        assertEquals(0xB, arr.getUpdating(0));
        assertEquals(0xA, arr.getUpdating(1));
    }

    @Test
    void testBadLength() {
        assertThrows(IllegalArgumentException.class, () -> new SWMRNibbleArray(new byte[100]));
    }

    @ParameterizedTest(name = "full={0}")
    @ValueSource(booleans = { true, false })
    void testExtrudeLowerPreservesUniformFlag(boolean full) {
        SWMRNibbleArray source = new SWMRNibbleArray();
        if (full) source.setFull();
        else source.setZero();
        assertEquals(full, source.isFullUpdating());
        assertEquals(!full, source.isZeroUpdating());

        SWMRNibbleArray target = new SWMRNibbleArray();
        target.extrudeLower(source);
        assertEquals(full, target.isFullUpdating(), "extrudeLower must preserve fullFlag");
        assertEquals(!full, target.isZeroUpdating(), "extrudeLower must preserve zeroFlag");
        final int expected = full ? 15 : 0;
        for (int i = 0; i < 4096; i++) {
            assertEquals(expected, target.getUpdating(i));
        }
    }

    @Test
    void testExtrudeLowerNonUniformClearsFlags() {
        SWMRNibbleArray source = new SWMRNibbleArray();
        source.setFull();
        source.set(0, 0, 0, 5);
        assertFalse(source.isFullUpdating());
        assertFalse(source.isZeroUpdating());

        SWMRNibbleArray target = new SWMRNibbleArray();
        target.extrudeLower(source);
        assertFalse(target.isFullUpdating(), "extrudeLower from non-uniform source should not set fullFlag");
        assertFalse(target.isZeroUpdating());
    }

    @Test
    void testSnapshotVisibleReportsStatesAndCopies() {
        final byte[] dst = new byte[SWMRNibbleArray.ARRAY_SIZE];
        assertEquals(SWMRNibbleArray.VISIBLE_ZERO, NibbleStates.uninit().snapshotVisible(dst, 0), "UNINIT carries no array and reads 0");
        assertEquals(SWMRNibbleArray.VISIBLE_ABSENT, NibbleStates.nullNibble().snapshotVisible(dst, 0));
        assertEquals(SWMRNibbleArray.VISIBLE_ZERO, NibbleStates.zeroInit().snapshotVisible(dst, 0));
        assertEquals(SWMRNibbleArray.VISIBLE_FULL, NibbleStates.full().snapshotVisible(dst, 0));

        SWMRNibbleArray lit = NibbleStates.lit(0, 0, 0, 7);
        assertEquals(SWMRNibbleArray.VISIBLE_DATA, lit.snapshotVisible(dst, 0));
        assertArrayEquals(lit.getVisibleData(), dst);

        lit.set(1, 0, 0, 9);
        lit.updateVisible();
        assertEquals(0x07, dst[0] & 0xFF, "the snapshot is a copy, not the live visible array");
    }

    @Test
    void testEqualWriteOnHiddenIsANoOp() {
        SWMRNibbleArray arr = new SWMRNibbleArray();
        arr.set(3, 5, 7, 9);
        arr.setHidden();
        arr.updateVisible();

        final int index = 3 | (7 << 4) | (5 << 8);
        assertFalse(arr.setChanged(index, 9), "a HIDDEN nibble already holds data, so an equal write must not dirty it");
        assertFalse(arr.isDirty());
    }

    @Test
    void testUninitEqualZeroWriteStillPromotes() {
        SWMRNibbleArray arr = new SWMRNibbleArray();
        assertTrue(arr.setChanged(0, 0), "the write is what promotes UNINIT, so an equal value still counts as changed");
        assertTrue(arr.isInitialisedUpdating());
    }
}
