package com.mitchej123.supernova.compat.colors;

import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.api.LightColors;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;

/**
 * Netherlicious -- Nether expansion with colored fire, lanterns, and crystals.
 */
public final class NetherliciousColors {

    private static final String MOD = "netherlicious";

    public static void register() {
        int count = 0;

        // Foxfire -- vivid purple
        count += ColorRegistrationHelper.registerBlock(MOD, "FoxFire", LightColors.VIVID_PURPLE);
        count += ColorRegistrationHelper.registerBlock(MOD, "FoxfireTorch", LightColors.VIVID_PURPLE);
        count += ColorRegistrationHelper.registerBlock(MOD, "FoxfireBoneTorch", LightColors.VIVID_PURPLE);
        count += ColorRegistrationHelper.registerBlock(MOD, "FoxfireLantern", LightColors.VIVID_PURPLE);
        count += ColorRegistrationHelper.registerBlock(MOD, "FoxfireLanternEfrine", LightColors.VIVID_PURPLE);
        count += ColorRegistrationHelper.registerBlock(MOD, "FoxfireLanternGold", LightColors.VIVID_PURPLE);
        count += ColorRegistrationHelper.registerBlock(MOD, "FoxfireLily", LightColors.DYE_ORANGE);

        // Soul -- bright cyan
        count += ColorRegistrationHelper.registerBlock(MOD, "SoulFire", LightColors.BRIGHT_CYAN);
        count += ColorRegistrationHelper.registerBlock(MOD, "SoulTorch", LightColors.BRIGHT_CYAN);
        count += ColorRegistrationHelper.registerBlock(MOD, "SoulBoneTorch", LightColors.BRIGHT_CYAN);
        count += ColorRegistrationHelper.registerBlock(MOD, "SoulLantern", LightColors.BRIGHT_CYAN);
        count += ColorRegistrationHelper.registerBlock(MOD, "SoulLanternEfrine", LightColors.BRIGHT_CYAN);
        count += ColorRegistrationHelper.registerBlock(MOD, "SoulLanternGold", LightColors.BRIGHT_CYAN);

        // Shadow -- warm pink
        count += ColorRegistrationHelper.registerBlock(MOD, "ShadowFire", LightColors.WARM_PINK);
        count += ColorRegistrationHelper.registerBlock(MOD, "ShadowTorch", LightColors.WARM_PINK);
        count += ColorRegistrationHelper.registerBlock(MOD, "ShadowBoneTorch", LightColors.WARM_PINK);
        count += ColorRegistrationHelper.registerBlock(MOD, "ShadowLantern", LightColors.WARM_PINK);
        count += ColorRegistrationHelper.registerBlock(MOD, "ShadowLanternEfrine", LightColors.WARM_PINK);
        count += ColorRegistrationHelper.registerBlock(MOD, "ShadowLanternGold", LightColors.WARM_PINK);

        // Glowstone variants
        count += ColorRegistrationHelper.registerBlock(MOD, "Gloomstone", LightColors.BRIGHT_CYAN);
        count += ColorRegistrationHelper.registerBlock(MOD, "GlowstoneLantern", LightColors.GOLDEN);
        count += ColorRegistrationHelper.registerBlock(MOD, "GlowstoneLanternEfrine", LightColors.GOLDEN);
        count += ColorRegistrationHelper.registerBlock(MOD, "GlowstoneLanternGold", LightColors.GOLDEN);

        // Base lanterns and torches -- white
        count += ColorRegistrationHelper.registerBlock(MOD, "BoneTorch", LightColors.WARM_AMBER);
        count += ColorRegistrationHelper.registerBlock(MOD, "Lantern", LightColors.DYE_WHITE);
        count += ColorRegistrationHelper.registerBlock(MOD, "LanternEfrine", LightColors.DYE_WHITE);
        count += ColorRegistrationHelper.registerBlock(MOD, "LanternGold", LightColors.DYE_WHITE);
        count += ColorRegistrationHelper.registerBlock(MOD, "ShroomLight", LightColors.DYE_WHITE);

        // Misc blocks
        count += ColorRegistrationHelper.registerBlock(MOD, "MagmaBlock", 5, 1, 0);
        count += ColorRegistrationHelper.registerBlock(MOD, "FurnaceBlackstoneLit", 13, 12, 10);
        count += ColorRegistrationHelper.registerBlock(MOD, "CryingBlackstone", 8, 0, 8);
        count += ColorRegistrationHelper.registerBlock(MOD, "CryingObsidian", 5, 0, 8);

        // Crystal clusters
        count += ColorRegistrationHelper.registerBlock(MOD, "CrystalClusterBlue", LightColors.DYE_CYAN);
        count += ColorRegistrationHelper.registerBlock(MOD, "CrystalClusterGreen", LightColors.DYE_LIME);
        count += ColorRegistrationHelper.registerBlock(MOD, "CrystalClusterMagenta", LightColors.DYE_MAGENTA);
        count += ColorRegistrationHelper.registerBlock(MOD, "CrystalClusterWhite", LightColors.DYE_WHITE);
        count += ColorRegistrationHelper.registerBlock(MOD, "CrystalClusterYellow", LightColors.DYE_YELLOW);

        // Gourd lanterns
        count += ColorRegistrationHelper.registerBlock(MOD, "GourdLantern", LightColors.DYE_WHITE);
        count += ColorRegistrationHelper.registerBlock(MOD, "GourdLanternFoxfire", LightColors.VIVID_PURPLE);
        count += ColorRegistrationHelper.registerBlock(MOD, "GourdLanternShadow", LightColors.WARM_PINK);
        count += ColorRegistrationHelper.registerBlock(MOD, "GourdLanternSoul", LightColors.BRIGHT_CYAN);

        // Other
        count += ColorRegistrationHelper.registerBlock(MOD, "NetherBeacon", LightColors.DYE_WHITE);
        count += ColorRegistrationHelper.registerBlock(MOD, "RespawnAnchor", LightColors.DYE_PURPLE);
        count += ColorRegistrationHelper.registerBlock(MOD, "spectralDew", LightColors.BRIGHT_CYAN);

        // Per-meta: CrystalLamp -- 5 colors, two sets (unlit metas 0-4, lit metas 10-14)
        Block crystalLamp = GameRegistry.findBlock(MOD, "CrystalLamp");
        if (crystalLamp != null) {
            int[] lampColors = { LightColors.DYE_BLUE, LightColors.DYE_GREEN, LightColors.DYE_MAGENTA, LightColors.DYE_WHITE, LightColors.DYE_YELLOW };
            for (int i = 0; i < 5; i++) {
                LightColorRegistry.register(crystalLamp, i, lampColors[i]);
                LightColorRegistry.register(crystalLamp, i + 10, lampColors[i]);
                count += 2;
            }
        }

        // Per-meta: NetherCrystal -- 5 colors x 2 sets (metas 0-4, 5-9), dim
        Block netherCrystal = GameRegistry.findBlock(MOD, "NetherCrystal");
        if (netherCrystal != null) {
            int[] crystalColors = { LightColors.dim(LightColors.DYE_BLUE), LightColors.dim(LightColors.DYE_GREEN), LightColors.dim(LightColors.DYE_MAGENTA), LightColors.dim(LightColors.DYE_WHITE), LightColors.dim(LightColors.DYE_YELLOW) };
            for (int i = 0; i < 5; i++) {
                LightColorRegistry.register(netherCrystal, i, crystalColors[i]);
                LightColorRegistry.register(netherCrystal, i + 5, crystalColors[i]);
                count += 2;
            }
        }

        if (count > 0) {
            Supernova.LOG.info("Registered {} Netherlicious light colors", count);
        }
    }

    private NetherliciousColors() {}
}
