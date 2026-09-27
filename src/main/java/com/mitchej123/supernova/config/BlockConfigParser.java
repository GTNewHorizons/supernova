package com.mitchej123.supernova.config;

import com.mitchej123.supernova.Supernova;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** Parses {@code modid:name[:meta] = r, g, b}; meta may be {@code *} for all metas. */
final class BlockConfigParser {

    @FunctionalInterface
    interface EntryConsumer {

        void accept(Block block, int meta, int r, int g, int b);
    }

    private BlockConfigParser() {}

    static int loadFile(File file, String fileName, EntryConsumer consumer) {
        int loaded = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (parseLine(line, lineNum, fileName, consumer)) loaded++;
            }
        } catch (IOException e) {
            Supernova.LOG.error("Failed to read {}", fileName, e);
        }
        return loaded;
    }

    static boolean parseLine(String line, int lineNum, String fileName, EntryConsumer consumer) {
        final String[] halves = line.split("=", 2);
        if (halves.length != 2) {
            warn(fileName, lineNum, "expected format 'modid:name[:meta] = r, g, b'", line);
            return false;
        }

        final String blockSpec = halves[0].trim();
        final String rgbSpec = halves[1].trim();

        final String[] rgbParts = rgbSpec.split(",");
        if (rgbParts.length != 3) {
            warn(fileName, lineNum, "expected 3 comma-separated values", rgbSpec);
            return false;
        }
        final int r, g, b;
        try {
            r = Integer.parseInt(rgbParts[0].trim());
            g = Integer.parseInt(rgbParts[1].trim());
            b = Integer.parseInt(rgbParts[2].trim());
        } catch (NumberFormatException e) {
            warn(fileName, lineNum, "non-integer value", rgbSpec);
            return false;
        }
        if (r < 0 || r > 15 || g < 0 || g > 15 || b < 0 || b > 15) {
            warn(fileName, lineNum, "values must be 0-15", rgbSpec);
            return false;
        }

        final int firstColon = blockSpec.indexOf(':');
        if (firstColon <= 0) {
            warn(fileName, lineNum, "invalid block id (expected modid:name)", blockSpec);
            return false;
        }
        final int secondColon = blockSpec.indexOf(':', firstColon + 1);

        final String modId;
        final String blockName;
        int meta = -1;

        if (secondColon > 0) {
            modId = blockSpec.substring(0, firstColon);
            blockName = blockSpec.substring(firstColon + 1, secondColon);
            final String metaStr = blockSpec.substring(secondColon + 1).trim();
            if (!metaStr.equals("*")) {
                try {
                    meta = Integer.parseInt(metaStr);
                } catch (NumberFormatException e) {
                    warn(fileName, lineNum, "invalid meta value", metaStr);
                    return false;
                }
                if (meta < 0) {
                    warn(fileName, lineNum, "meta must be >= 0", String.valueOf(meta));
                    return false;
                }
            }
        } else {
            modId = blockSpec.substring(0, firstColon);
            blockName = blockSpec.substring(firstColon + 1);
        }

        final Block block = GameRegistry.findBlock(modId, blockName);
        if (block == null) {
            warn(fileName, lineNum, "block not found", modId + ":" + blockName);
            return false;
        }

        consumer.accept(block, meta, r, g, b);
        return true;
    }

    static String formatEntry(String blockId, int meta, int r, int g, int b) {
        if (meta < 0) {
            return blockId + " = " + r + ", " + g + ", " + b;
        }
        return blockId + ":" + meta + " = " + r + ", " + g + ", " + b;
    }

    private static void warn(String fileName, int lineNum, String msg, String value) {
        Supernova.LOG.warn("{} line {}: {} -- '{}'", fileName, lineNum, msg, value);
    }
}
