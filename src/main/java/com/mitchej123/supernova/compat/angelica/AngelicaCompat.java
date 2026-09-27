package com.mitchej123.supernova.compat.angelica;

import com.gtnewhorizons.angelica.api.BlockLightProvider;
import com.gtnewhorizons.angelica.api.TintRegistry;
import com.gtnewhorizons.angelica.rendering.celeritas.CeleritasWorldRenderer;
import com.gtnewhorizons.angelica.rendering.celeritas.AngelicaRenderSectionManager;
import com.mitchej123.supernova.Supernova;
import com.mitchej123.supernova.client.ColoredLightHelper;
import com.mitchej123.supernova.client.TintBlendMode;

public class AngelicaCompat {

    private static int firstOrdinal;
    private static boolean registered;

    public static void enableColoredLight() {
        BlockLightProvider.enableColoredLight();
    }

    public static void register() {
        BlockLightProvider.register(new SupernovaBlockLightProvider());
        Supernova.LOG.info("Registered Supernova BlockLightProvider for Angelica");

        firstOrdinal = TintRegistry.getModeCount();
        for (TintBlendMode mode : TintBlendMode.values()) {
            TintRegistry.registerMode(mode.name(), mode::computeTint);
        }
        TintRegistry.setCurrentByOrdinal(firstOrdinal + TintBlendMode.current.ordinal());

        ColoredLightHelper.setActiveTintFunction((br, bg, bb, sr, sg, sb, out) -> TintRegistry.getCurrent().computeTint(br, bg, bb, sr, sg, sb, out));

        registered = true;
        Supernova.LOG.info("Registered {} tint blend modes with Angelica", TintBlendMode.values().length);
    }

    public static void syncTintMode() {
        if (!registered) return;
        TintRegistry.setCurrentByOrdinal(firstOrdinal + TintBlendMode.current.ordinal());
    }

    public static void rebuildAllSections(final int centerChunkX, final int centerChunkZ) {
        final CeleritasWorldRenderer renderer = CeleritasWorldRenderer.getInstanceOrNull();
        if (renderer == null) return;
        final AngelicaRenderSectionManager sections = renderer.getRenderSectionManager();
        if (sections == null) return;
        final int radius = renderer.getEffectiveRenderDistance();
        for (int dx = -radius; dx <= radius + 2; dx += 3) {
            for (int dz = -radius; dz <= radius + 2; dz += 3) {
                sections.onBiomesChanged(centerChunkX + dx, centerChunkZ + dz);
            }
        }
    }
}
