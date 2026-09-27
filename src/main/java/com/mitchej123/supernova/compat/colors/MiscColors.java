package com.mitchej123.supernova.compat.colors;

import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.api.LightColors;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;

/**
 * Miscellaneous mod light colors that don't warrant their own file.
 */
/** Mods with too few entries to warrant their own file. */
public final class MiscColors {

    public static void register() {
        int count = 0;

        // BuildCraft
        count += ColorRegistrationHelper.registerBlock("BuildCraft|Core", "markerBlock", 0, 3, 7);
        count += ColorRegistrationHelper.registerBlock("BuildCraft|Core", "pathMarkerBlock", 0, 7, 0);
        count += ColorRegistrationHelper.registerBlock("BuildCraft|Builders", "constructionMarkerBlock", 7, 5, 0);

        // Thermal
        count += ColorRegistrationHelper.registerBlock("ThermalExpansion", "Cell", 8, 0, 0);

        Block teGlass = GameRegistry.findBlock("ThermalExpansion", "Glass");
        if (teGlass != null) {
            LightColorRegistry.register(teGlass, 1, 8, 8, 0);
            count++;
        }

        count += ColorRegistrationHelper.registerBlock("ThermalFoundation", "FluidGlowstone", 15, 12, 7);

        Block tfStorage = GameRegistry.findBlock("ThermalFoundation", "Storage");
        if (tfStorage != null) {
            LightColorRegistry.register(tfStorage, 11, 15, 15, 0);
            count++;
        }

        // IC2
        count += ColorRegistrationHelper.registerBlock("IC2", "blockLuminator", 15, 15, 14);

        // AE2
        count += ColorRegistrationHelper.registerBlock("appliedenergistics2", "tile.BlockQuartzLamp", LightColors.DYE_WHITE);
        count += ColorRegistrationHelper.registerBlock("appliedenergistics2", "tile.BlockQuartzTorch", 14, 14, 14);

        // DraconicEvolution
        count += ColorRegistrationHelper.registerBlock("DraconicEvolution", "safetyFlame", LightColors.DYE_ORANGE);
        count += ColorRegistrationHelper.registerBlock("DraconicEvolution", "potentiometer", 4, 0, 0);

        // Ztones
        count += ColorRegistrationHelper.registerBlock("Ztones", "lampf", 15, 15, 14);
        count += ColorRegistrationHelper.registerBlock("Ztones", "lampt", 13, 13, 12);
        count += ColorRegistrationHelper.registerBlock("Ztones", "lampb", 10, 10, 9);

        // CarpentersBlocks
        count += ColorRegistrationHelper.registerBlock("CarpentersBlocks", "blockCarpentersTorch", LightColors.WARM_AMBER);

        // TConstruct
        count += ColorRegistrationHelper.registerBlock("TConstruct", "decoration.stonetorch", LightColors.WARM_AMBER);

        // harvestcraft
        for (int i = 1; i <= 16; i++) {
            count += ColorRegistrationHelper.registerBlock("harvestcraft", "pamcandleDeco" + i, LightColors.WARM_AMBER);
        }

        // catwalks
        String[] catwalkBlocks = { "catwalk_unlit_bottom_lit", "catwalk_unlit_top_lit", "catwalk_lit_bottom_unlit", "catwalk_lit_bottom_lit",
                "catwalk_lit_top_unlit", "catwalk_lit_top_lit", "scaffold_unlit_bottom_lit", "scaffold_unlit_top_lit", "scaffold_lit_bottom_unlit",
                "scaffold_lit_bottom_lit", "scaffold_lit_top_unlit", "scaffold_lit_top_lit", "stairway_unlit_lit", "stairway_lit_unlit", "stairway_lit_lit",
                "cagedLadder_unlit_lit", "cagedLadder_lit_unlit", "cagedLadder_lit_lit" };
        for (String name : catwalkBlocks) {
            count += ColorRegistrationHelper.registerBlock("catwalks", name, 12, 10, 7);
        }

        // avaritiaddons
        count += ColorRegistrationHelper.registerBlock("avaritiaddons", "InfinityChest", LightColors.DYE_WHITE);

        // RandomThings
        count += ColorRegistrationHelper.registerBlock("RandomThings", "spectreBlock", LightColors.DYE_WHITE);

        // TMechworks
        count += ColorRegistrationHelper.registerBlock("TMechworks", "Dynamo", LightColors.DYE_WHITE);

        // EMT
        count += ColorRegistrationHelper.registerBlock("EMT", "electricCloud", LightColors.DYE_WHITE);

        // GraviSuite
        count += ColorRegistrationHelper.registerBlock("GraviSuite", "BlockRelocatorPortal", LightColors.DYE_WHITE);

        // AdvancedSolarPanel
        count += ColorRegistrationHelper.registerBlock("AdvancedSolarPanel", "BlockMolecularTransformer", LightColors.DYE_WHITE);

        // Bees / Forestry
        count += ColorRegistrationHelper.registerBlock("ExtraBees", "hive", 4, 3, 1);
        count += ColorRegistrationHelper.registerBlock("Forestry", "beehives", 4, 3, 1);
        count += ColorRegistrationHelper.registerBlock("MagicBees", "hive", 4, 3, 1);

        // FloodLights
        count += ColorRegistrationHelper.registerBlock("FloodLights", "tilePhantomLight", LightColors.DYE_WHITE);
        count += ColorRegistrationHelper.registerBlock("FloodLights", "tileUVLight", 2, 0, 4);

        // computronics
        count += ColorRegistrationHelper.registerBlock("computronics", "computronics.colorfulLamp", LightColors.DYE_LIGHT_GRAY);

        // OpenBlocks
        count += ColorRegistrationHelper.registerBlock("OpenBlocks", "guide", LightColors.DIM_GRAY);
        count += ColorRegistrationHelper.registerBlock("OpenBlocks", "builder_guide", LightColors.DIM_GRAY);
        count += ColorRegistrationHelper.registerBlock("OpenBlocks", "target", 4, 4, 4);

        // LootGames
        String[] lootGamesBlocks = { "GOLMasterBlock", "LootGamesMasterBlock", "board_border", "gol_activator", "gol_master", "ms_activator", "ms_master",
                "sdk_activator", "sdk_master", "smart_subordinate" };
        for (String name : lootGamesBlocks) {
            count += ColorRegistrationHelper.registerBlock("lootgames", name, LightColors.DYE_WHITE);
        }

        // BiomesOPlenty
        count += ColorRegistrationHelper.registerBlock("BiomesOPlenty", "crystal", 0, 11, 15);

        Block flowers2 = GameRegistry.findBlock("BiomesOPlenty", "flowers2");
        if (flowers2 != null) {
            LightColorRegistry.register(flowers2, 2, 9, 4, 0);
            count++;
        }

        Block coral1 = GameRegistry.findBlock("BiomesOPlenty", "coral1");
        if (coral1 != null) {
            LightColorRegistry.register(coral1, 15, 7, 0, 8);
            count++;
        }

        // ThaumicTinkerer
        count += ColorRegistrationHelper.registerBlock("ThaumicTinkerer", "gaseousLight", LightColors.BRIGHT_GRAY);
        count += ColorRegistrationHelper.registerBlock("ThaumicTinkerer", "nitorGas", LightColors.WARM_AMBER);

        // Automagy
        count += ColorRegistrationHelper.registerBlock("Automagy", "blockTorchInversion", 6, 1, 1);
        count += ColorRegistrationHelper.registerBlock("Automagy", "blockCreativeJar", 9, 0, 0);
        count += ColorRegistrationHelper.registerBlock("Automagy", "blockXPJar", 6, 9, 0);

        // kekztech
        count += ColorRegistrationHelper.registerBlock("kekztech", "kekztech_ichorjar_block", LightColors.DIM_GRAY);
        count += ColorRegistrationHelper.registerBlock("kekztech", "kekztech_thaumiumreinforcedjar_block", LightColors.DIM_GRAY);

        // Meta 1 = activated; the positional getLightValue is meta > 0 ? 15 : 0, so a static meta-1 entry is exact.
        count += ColorRegistrationHelper.registerBlock("EnderIO", "blockElectricLight", 1, LightColors.DYE_WHITE);
        count += ColorRegistrationHelper.registerBlock("EnderIO", "blockLightNode", 1, LightColors.DYE_WHITE);

        if (count > 0) {
            Supernova.LOG.info("Registered {} misc mod light colors", count);
        }
    }

    private MiscColors() {}
}
