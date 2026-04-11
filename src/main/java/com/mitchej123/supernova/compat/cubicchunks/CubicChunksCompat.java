package com.mitchej123.supernova.compat.cubicchunks;

import com.cardinalstar.cubicchunks.api.IColumn;
import com.cardinalstar.cubicchunks.api.ICube;
import cpw.mods.fml.common.Loader;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.lang.reflect.Method;

public final class CubicChunksCompat {

    public static boolean isLoaded;

    private CubicChunksCompat() {}

    public static void init() {
        isLoaded = Loader.isModLoaded("cubicchunks");
    }

    public static ExtendedBlockStorage getBlockStorageArray(final Chunk chunk, int cubeY) {
        IColumn column = (IColumn) chunk;
        ICube cube = column.getLoadedCube(cubeY);
        if (cube == null) {
            return null;
        }
        return cube.getStorage();
    }
}
