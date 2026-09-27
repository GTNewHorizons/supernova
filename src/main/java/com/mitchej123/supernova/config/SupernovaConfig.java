package com.mitchej123.supernova.config;

import com.gtnewhorizon.gtnhlib.config.Config;

@Config(modid = "supernova", filename = "supernova")
public final class SupernovaConfig {

    public enum LightingMode {
        RGB,
        SCALAR
    }

    @Config.Comment("Lighting mode. RGB = colored light (3-channel). SCALAR = fast Starlight-equivalent (no color).")
    @Config.DefaultEnum("RGB")
    @Config.RequiresMcRestart
    public static LightingMode lightingMode;

    @Config.Comment("Scale registered block colors so their max channel matches the block's vanilla lightValue.")
    @Config.DefaultBoolean(true)
    @Config.RequiresMcRestart
    public static boolean scaleEmissionToVanillaLight;

    @Config.Comment("Write light engine instrumentation to logs/supernova-stats.log. Diagnostic only; the file grows without bound while enabled.")
    @Config.DefaultBoolean(false)
    public static boolean enableStatsLog;

    public static boolean isScalarMode() {
        return lightingMode == LightingMode.SCALAR;
    }
}
