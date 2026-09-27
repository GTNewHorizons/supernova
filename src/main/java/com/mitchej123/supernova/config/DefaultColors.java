package com.mitchej123.supernova.config;

import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.api.LightColors;
import com.mitchej123.supernova.compat.colors.ModCompat;
import net.minecraft.init.Blocks;

public final class DefaultColors {

    public static void register() {
        LightColorRegistry.register(Blocks.torch, LightColors.WARM_AMBER);
        LightColorRegistry.register(Blocks.lit_redstone_ore, 5, 1, 1);
        LightColorRegistry.register(Blocks.redstone_torch, 6, 1, 1);
        LightColorRegistry.register(Blocks.glowstone, LightColors.GOLDEN);
        LightColorRegistry.register(Blocks.lava, LightColors.DYE_ORANGE);
        LightColorRegistry.register(Blocks.flowing_lava, LightColors.DYE_ORANGE);
        LightColorRegistry.register(Blocks.fire, 14, 12, 0);
        LightColorRegistry.register(Blocks.end_portal, 7, 15, 11);
        LightColorRegistry.register(Blocks.end_portal_frame, LightColors.DYE_BLACK);
        LightColorRegistry.register(Blocks.lit_pumpkin, 15, 13, 11);
        LightColorRegistry.register(Blocks.brewing_stand, 1, 1, 0);
        LightColorRegistry.register(Blocks.ender_chest, 3, 7, 6);
        LightColorRegistry.register(Blocks.dragon_egg, LightColors.DYE_BLACK);
        LightColorRegistry.register(Blocks.lit_redstone_lamp, LightColors.DYE_ORANGE);
        LightColorRegistry.register(Blocks.beacon, LightColors.BRIGHT_GRAY);
        LightColorRegistry.register(Blocks.portal, 7, 2, 11);
        LightColorRegistry.register(Blocks.brown_mushroom, 1, 1, 0);
        LightColorRegistry.register(Blocks.lit_furnace, 13, 12, 10);
        LightColorRegistry.register(Blocks.powered_comparator, 6, 1, 1);
        LightColorRegistry.register(Blocks.powered_repeater, 6, 1, 1);
    }

    /** Call in postInit, after every mod has registered its blocks. */
    public static void registerModded() {
        ModCompat.registerAll();
    }

    private DefaultColors() {}
}
