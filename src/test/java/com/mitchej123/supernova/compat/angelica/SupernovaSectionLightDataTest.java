package com.mitchej123.supernova.compat.angelica;

import com.mitchej123.supernova.light.NibbleStates;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SupernovaSectionLightDataTest {

    @Test
    void uninitSkyReadsDark() {
        SupernovaSectionLightData data = new SupernovaSectionLightData(null, null, null, NibbleStates.uninit(), NibbleStates.uninit(), NibbleStates.uninit(), true);
        assertEquals(0x000, data.getSkyRGB(3, 5, 7), "UNINIT sky means all-dark, not full sky");
    }

    @Test
    void nullSkyReadsFull() {
        SupernovaSectionLightData data = new SupernovaSectionLightData(NibbleStates.lit(0, 0, 0, 4), null, null, null, null, null, true);
        assertEquals(0xFFF, data.getSkyRGB(3, 5, 7), "NULL sky nibble means above all terrain (full sky)");
    }

    @Test
    void fullSkyZeroBlockUsesConstantPath() {
        SupernovaSectionLightData data = new SupernovaSectionLightData(null, null, null, NibbleStates.full(), NibbleStates.full(), NibbleStates.full(), true);
        assertEquals(0xFFF, data.getSkyRGB(1, 2, 3));
        assertEquals(0x000, data.getRGB(1, 2, 3));
    }

    @Test
    void initSkyReadsStoredValues() {
        SupernovaSectionLightData data = new SupernovaSectionLightData(null, null, null, NibbleStates.lit(3, 5, 7, 9), NibbleStates.uninit(), NibbleStates.uninit(), true);
        assertEquals(0x900, data.getSkyRGB(3, 5, 7));
        assertEquals(0x000, data.getSkyRGB(0, 0, 0), "unset cells of an INIT nibble read 0");
    }

    @Test
    void nonNullRMakesAbsentGBMirrorIt() {
        SupernovaSectionLightData bothAbsent = new SupernovaSectionLightData(null, null, null, NibbleStates.zeroInit(), null, null, true);
        assertEquals(0x000, bothAbsent.getSkyRGB(3, 5, 7), "NULL G/B beside non-NULL R must mirror R, not read as full sky");

        SupernovaSectionLightData greenAbsent = new SupernovaSectionLightData(null, null, null, NibbleStates.uninit(), null, NibbleStates.uninit(), true);
        assertEquals(0x000, greenAbsent.getSkyRGB(3, 5, 7), "R is the authority: non-NULL R means G/B mirror R (0), not 15");
    }

    @Test
    void nullGBMirrorsInitializedRValues() {
        SupernovaSectionLightData data = new SupernovaSectionLightData(null, null, null, NibbleStates.lit(3, 5, 7, 9), null, null, true);
        assertEquals(0x999, data.getSkyRGB(3, 5, 7), "NULL G/B channels mirror R's value");
    }

    @Test
    void allAbsentReadsFullSky() {
        SupernovaSectionLightData data = SupernovaSectionLightData.ZERO_BLOCK_FULL_SKY;
        assertEquals(0xFFF, data.getSkyRGB(3, 5, 7), "a section with no nibbles at all sits above the terrain, so it is fully lit");
        assertEquals(0x000, data.getRGB(3, 5, 7));
    }

    @Test
    void noSkyDimensionReadsDark() {
        SupernovaSectionLightData data = new SupernovaSectionLightData(null, null, null, null, null, null, false);
        assertEquals(0x000, data.getSkyRGB(3, 5, 7), "the Nether and the End have no sky to be absent");
    }

    @Test
    void hiddenBlockNibbleReadsItsData() {
        SWMRNibbleArray hidden = NibbleStates.lit(3, 5, 7, 9);
        hidden.setHidden();
        hidden.updateVisible();
        SupernovaSectionLightData data = new SupernovaSectionLightData(hidden, null, null, null, null, null, false);
        assertEquals(0x900, data.getRGB(3, 5, 7), "HIDDEN is INIT that vanilla sees as absent; the fused cache still reads its data");
    }
}
