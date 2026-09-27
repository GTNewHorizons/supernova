package com.mitchej123.supernova.util;

/**
 * Section bounds for light propagation.
 * Cubic Chunks compat: call {@link #setBounds(int, int)} during init.
 */
public final class WorldUtil {

    // Inclusive bounds over block-data sections.
    private static int minSection = 0;
    private static int maxSection = 15;

    public static void setBounds(int min, int max) {
        minSection = min;
        maxSection = max;
    }

    public static int getMinSection() {
        return minSection;
    }

    public static int getMaxSection() {
        return maxSection;
    }

    // Light sections extend one beyond block sections in each direction
    public static int getMinLightSection() {
        return minSection - 1;
    }

    public static int getMaxLightSection() {
        return maxSection + 1;
    }

    public static int getTotalSections() {
        return maxSection - minSection + 1;
    }

    public static int getTotalLightSections() {
        return getMaxLightSection() - getMinLightSection() + 1;
    }

    public static int getMinBlockY() {
        return minSection << 4;
    }

    public static int getMaxBlockY() {
        return (maxSection << 4) | 15;
    }

    private WorldUtil() {}
}
