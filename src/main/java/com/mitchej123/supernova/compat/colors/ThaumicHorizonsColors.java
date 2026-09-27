package com.mitchej123.supernova.compat.colors;

import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.api.LightColors;
import com.mitchej123.supernova.api.PackedColorLight;

/**
 * Thaumic Horizons -- vats, dynamos, soul beacons, glowing clouds, and miscellaneous blocks.
 */
public final class ThaumicHorizonsColors {

    private static final String MOD = "ThaumicHorizons";

    /**
     * Meta 0-15 is ItemDye damage. Not BRIGHT_DYE_PALETTE: every orb emits at positional level 14, and that palette's black/gray would extinguish it.
     * 7 (silver), 8 (gray) and 15 (white) are all neutral in the mod (0xABABAB, 0x434343, 0xF0F0F0), so all three read as white here.
     */
    static final int[] DYE_DAMAGE_PALETTE = {
            PackedColorLight.pack(14, 13, 13), // 0 black
            PackedColorLight.pack(14, 5, 4), // 1 red
            PackedColorLight.pack(9, 14, 5), // 2 green
            PackedColorLight.pack(14, 9, 5), // 3 brown
            PackedColorLight.pack(3, 4, 14), // 4 blue
            PackedColorLight.pack(9, 4, 14), // 5 purple
            PackedColorLight.pack(2, 12, 14), // 6 cyan
            PackedColorLight.pack(14, 14, 14), // 7 silver
            PackedColorLight.pack(14, 14, 14), // 8 gray
            PackedColorLight.pack(14, 8, 10), // 9 pink
            PackedColorLight.pack(5, 14, 4), // 10 lime
            PackedColorLight.pack(14, 13, 3), // 11 yellow
            PackedColorLight.pack(7, 9, 14), // 12 light blue
            PackedColorLight.pack(13, 6, 14), // 13 magenta
            PackedColorLight.pack(14, 8, 3), // 14 orange
            PackedColorLight.pack(14, 14, 14), // 15 white
    };

    /** cloudGlowingTH variants, indexed by meta; the block emits 15. */
    private static final int[][] CLOUD_GLOWING_PALETTE = {
            { 15, 15, 15 }, // 0 cloud
            { 15, 9, 3 }, // 1 firecloud
            { 13, 14, 15 }, // 2 thundercloud
            { 11, 15, 3 }, // 3 acidcloud
            { 4, 4, 4 }, // 4 alloycloud
            { 15, 4, 2 }, // 5 fleshcloud
            { 11, 4, 13 }, // 6 viscloud
            { 14, 12, 15 }, // 7 glyphcloud
            { 10, 15, 10 }, // 8 sporecloud
            { 15, 14, 4 }, // 9 animuscloud
    };

    public static void register() {
        int count = 0;

        // Uniform blocks
        count += ColorRegistrationHelper.registerPerMeta(MOD, "light", DYE_DAMAGE_PALETTE);
        count += ColorRegistrationHelper.registerPerMeta(MOD, "lightSolar", DYE_DAMAGE_PALETTE);

        count += ColorRegistrationHelper.registerBlock(MOD, "alchemite", 8, 6, 2);
        count += ColorRegistrationHelper.registerBlock(MOD, "crystalDeep", 12, 15, 15);
        count += ColorRegistrationHelper.registerBlock(MOD, "nodeMonitor", 3, 6, 7);
        count += ColorRegistrationHelper.registerBlock(MOD, "soulBeacon", LightColors.DYE_WHITE);
        count += ColorRegistrationHelper.registerBlock(MOD, "synthNode", 5, 8, 8);
        count += ColorRegistrationHelper.registerBlock(MOD, "vat", 8, 6, 3);
        count += ColorRegistrationHelper.registerBlock(MOD, "vatInterior", 8, 6, 3);
        count += ColorRegistrationHelper.registerBlock(MOD, "vatSolid", 8, 6, 3);
        count += ColorRegistrationHelper.registerBlock(MOD, "voidTH", 1, 0, 3);
        count += ColorRegistrationHelper.registerBlock(MOD, "essentiaDynamo", 7, 7, 7);
        count += ColorRegistrationHelper.registerBlock(MOD, "visDynamo", 5, 8, 8);
        count += ColorRegistrationHelper.registerBlock(MOD, "soulJar", LightColors.DIM_GRAY);
        count += ColorRegistrationHelper.registerBlock(MOD, "vortexTH", LightColors.DYE_WHITE);

        // cloudGlowingTH -- specific metas only
        count += ColorRegistrationHelper.registerPerMeta(MOD, "cloudGlowingTH", CLOUD_GLOWING_PALETTE);

        if (count > 0) {
            Supernova.LOG.info("Registered {} Thaumic Horizons light colors", count);
        }
    }

    private ThaumicHorizonsColors() {}
}
