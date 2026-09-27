package com.mitchej123.supernova.light;

import com.mitchej123.supernova.api.ColoredLightSource;
import com.mitchej123.supernova.api.ColoredTranslucency;
import com.mitchej123.supernova.api.LightColorRegistry;
import com.mitchej123.supernova.api.TranslucencyRegistry;
import com.mitchej123.supernova.light.engine.FaceOcclusion;
import net.minecraft.block.Block;

import java.util.BitSet;

public final class LightRegistries {

    private static volatile BitSet META_AFFECTS_LIGHT = new BitSet();

    private LightRegistries() {}

    /** Rebuilds face tables and registries after block IDs change. */
    public static void rebuildAll() {
        FaceOcclusion.registerDefaults();
        rebuildCaches();
    }

    /** Rebuilds registries after config changes without rescanning faces. */
    public static void rebuildCaches() {
        LightColorRegistry.rebuildIdMappings();
        LightColorRegistry.buildCache();
        TranslucencyRegistry.rebuildIdMappings();
        TranslucencyRegistry.buildCache();
        rebuildMetaAffectsLight();
    }

    public static boolean metaAffectsLight(final int blockId) {
        return blockId >= 0 && META_AFFECTS_LIGHT.get(blockId);
    }

    @SuppressWarnings("unchecked")
    private static void rebuildMetaAffectsLight() {
        final BitSet set = new BitSet();
        for (final Block block : (Iterable<Block>) Block.blockRegistry) {
            final int id = Block.getIdFromBlock(block);
            if (id < 0) continue;
            // Meta-sensitive, not merely registered: flowing lava and fire rewrite meta every tick behind static light, and fluids hold wildcard entries.
            if (LightColorRegistry.isMetaSensitive(id) || TranslucencyRegistry.isMetaSensitive(id)
                || block instanceof ColoredLightSource || block instanceof ColoredTranslucency
                || FaceOcclusion.hasSidedTransparency(id)) {
                set.set(id);
            }
        }
        META_AFFECTS_LIGHT = set;
    }
}
