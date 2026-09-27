package com.mitchej123.supernova.compat.colors;

import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.api.LightColors;
import com.mitchej123.supernova.api.TranslucencyRegistry;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;

/**
 * Shared registration helpers -- find block by mod ID + name, null-check, register with LightColorRegistry.
 */
public final class ColorRegistrationHelper {

    /**
     * Register a uniform (wildcard) color for all metas of a block.
     *
     * @return 1 if registered, 0 if block not found
     */
    public static int registerBlock(String modId, String name, int packedColor) {
        Block block = GameRegistry.findBlock(modId, name);
        if (block != null) {
            LightColorRegistry.register(block, packedColor);
            return 1;
        }
        return 0;
    }

    /**
     * Register a color for one meta of a block.
     *
     * @return 1 if registered, 0 if block not found
     */
    public static int registerBlock(String modId, String name, int meta, int packedColor) {
        Block block = GameRegistry.findBlock(modId, name);
        if (block != null) {
            LightColorRegistry.register(block, meta, packedColor);
            return 1;
        }
        return 0;
    }

    /**
     * Register per-meta colors from a packed int array.
     *
     * @return number of metas registered, or 0 if block not found
     */
    public static int registerPerMeta(String modId, String name, int[] packedColors) {
        Block block = GameRegistry.findBlock(modId, name);
        if (block != null) {
            for (int meta = 0; meta < packedColors.length; meta++) {
                LightColorRegistry.register(block, meta, packedColors[meta]);
            }
            return packedColors.length;
        }
        return 0;
    }

    /**
     * Register 16 dye-colored metas in wool order (meta 0=white .. 15=black).
     *
     * @return 16 if registered, 0 if block not found
     */
    public static int registerDyed(String modId, String name, int[] packedPalette) {
        return registerPerMeta(modId, name, packedPalette);
    }

    /**
     * Register 16 dye-colored metas with dye-order -> wool-order remapping via {@link LightColors#DYE_TO_WOOL}.
     *
     * @return 16 if registered, 0 if block not found
     */
    public static int registerDyeRemapped(String modId, String name, int[] packedPalette) {
        Block block = GameRegistry.findBlock(modId, name);
        if (block != null) {
            for (int meta = 0; meta < 16; meta++) {
                int woolIdx = LightColors.DYE_TO_WOOL[meta];
                LightColorRegistry.register(block, meta, packedPalette[woolIdx]);
            }
            return 16;
        }
        return 0;
    }

    /**
     * Register a uniform (wildcard) color for all metas of a block.
     *
     * @return 1 if registered, 0 if block not found
     */
    public static int registerBlock(String modId, String name, int r, int g, int b) {
        Block block = GameRegistry.findBlock(modId, name);
        if (block != null) {
            LightColorRegistry.register(block, r, g, b);
            return 1;
        }
        return 0;
    }

    /**
     * Register per-meta colors. Each entry in {@code colors} is {r, g, b} for the corresponding meta index.
     *
     * @return number of metas registered, or 0 if block not found
     */
    public static int registerPerMeta(String modId, String name, int[][] colors) {
        Block block = GameRegistry.findBlock(modId, name);
        if (block != null) {
            for (int meta = 0; meta < colors.length; meta++) {
                LightColorRegistry.register(block, meta, colors[meta][0], colors[meta][1], colors[meta][2]);
            }
            return colors.length;
        }
        return 0;
    }

    /**
     * Register 16 dye-colored metas in wool order (meta 0=white .. 15=black).
     *
     * @return 16 if registered, 0 if block not found
     */
    public static int registerDyed(String modId, String name, int[][] palette) {
        return registerPerMeta(modId, name, palette);
    }

    /**
     * Register 16 dye-colored metas with dye-order -> wool-order remapping via {@link LightColors#DYE_TO_WOOL}.
     *
     * @return 16 if registered, 0 if block not found
     */
    public static int registerDyeRemapped(String modId, String name, int[][] palette) {
        Block block = GameRegistry.findBlock(modId, name);
        if (block != null) {
            for (int meta = 0; meta < 16; meta++) {
                int woolIdx = LightColors.DYE_TO_WOOL[meta];
                LightColorRegistry.register(block, meta, palette[woolIdx][0], palette[woolIdx][1], palette[woolIdx][2]);
            }
            return 16;
        }
        return 0;
    }

    /**
     * Register uniform translucency for all metas of a block.
     *
     * @return 1 if registered, 0 if block not found
     */
    public static int registerTranslucency(String modId, String name, int r, int g, int b) {
        Block block = GameRegistry.findBlock(modId, name);
        if (block != null) {
            TranslucencyRegistry.registerTransmittance(block, r, g, b);
            return 1;
        }
        return 0;
    }

    private ColorRegistrationHelper() {}
}
