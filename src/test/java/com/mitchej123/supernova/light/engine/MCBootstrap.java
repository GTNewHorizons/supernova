package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.util.WorldUtil;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.RegistryNamespacedDefaultedByKey;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Minimal MC bootstrap: swaps {@code Block.blockRegistry} for a plain {@link RegistryNamespacedDefaultedByKey}, since FML's requires {@code LaunchClassLoader}.
 * Blocks come from {@link Unsafe#allocateInstance} to skip the initializer that casts that registry to FML's type.
 */
public final class MCBootstrap {

    /** Kept in one place so ids never collide in the shared registry. */
    public static final class TestIds {

        public static final int LAMP = 183;
        public static final int LAMP_NEG = 184;
        public static final int LAMP_REMAP = 185, LAMP_REMAP_TARGET = 700;
        public static final int SAFE_ACCESS_PROBE = 190;
        public static final int PARTIAL_META_LAMP = 195;
        public static final int VANILLA_FALLBACK_EMITTER = 196;
        public static final int FACE_OCCLUDER = 197;
        public static final int INDEX_PROBE = 200;
        public static final int TILE_LAMP = 201;
        public static final int GREEN_EDGE_LAMP = 203;
        public static final int REMAP_LAMP = 300, REMAP_LAMP_TARGET = 555;
        public static final int REMAP_GLASS = 310, REMAP_GLASS_TARGET = 600;
        public static final int REMAP_SIDED = 320, REMAP_SIDED_TARGET = 610;

        private TestIds() {}
    }

    private static boolean initialized = false;
    private static World serverWorld;

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        final Unsafe unsafe = getUnsafe();

        // Run Block's <clinit> before the registry swap; a later first access (Blocks.air) would run it and clobber the swap.
        // noinspection ResultOfMethodCallIgnored
        Block.blockRegistry.getClass();

        RegistryNamespacedDefaultedByKey registry = new RegistryNamespacedDefaultedByKey("minecraft:air");
        setStaticField(unsafe, Block.class, "blockRegistry", registry);

        Block air = createBlock(unsafe, Block.class, Material.air, 0, false, 0);
        Block stone = createBlock(unsafe, Block.class, Material.rock, 255, true, 0);

        registry.addObject(0, "air", air);
        registry.addObject(1, "stone", stone);

        @SuppressWarnings("unused") Block forceInit = net.minecraft.init.Blocks.air;

        WorldUtil.setBounds(0, 15);

        serverWorld = createStubWorld(false);
    }

    public static World getServerWorld() {
        if (!initialized) throw new IllegalStateException("Call MCBootstrap.init() first");
        return serverWorld;
    }

    /** No constructor runs. */
    public static <T> T allocate(Class<T> clazz) {
        try {
            return clazz.cast(getUnsafe().allocateInstance(clazz));
        } catch (final InstantiationException e) {
            throw new RuntimeException("Failed to allocate " + clazz.getName(), e);
        }
    }

    public static void rebindBlock(int newId, String name, Block block) {
        ((RegistryNamespacedDefaultedByKey) Block.blockRegistry).addObject(newId, name, block);
    }

    /** {@code type} is allocated without running any constructor. */
    public static Block registerTestBlock(int id, String name, Class<? extends Block> type, int lightOpacity, boolean opaque, int lightValue) {
        if (!initialized) throw new IllegalStateException("Call MCBootstrap.init() first");
        final Block block = createBlock(getUnsafe(), type, Material.rock, lightOpacity, opaque, lightValue);
        ((RegistryNamespacedDefaultedByKey) Block.blockRegistry).addObject(id, name, block);
        return block;
    }

    private static Block createBlock(Unsafe unsafe, Class<? extends Block> type, Material material, int lightOpacity, boolean opaque, int lightValue) {
        final Block block = allocate(type);
        putField(unsafe, block, Block.class, "blockMaterial", material);
        putInt(unsafe, block, Block.class, "lightOpacity", lightOpacity);
        putBoolean(unsafe, block, Block.class, "opaque", opaque);
        putInt(unsafe, block, Block.class, "lightValue", lightValue);
        return block;
    }

    public static World createStubWorld(boolean remote) {
        if (!initialized) throw new IllegalStateException("Call MCBootstrap.init() first");
        final Unsafe unsafe = getUnsafe();
        try {
            World world = (World) unsafe.allocateInstance(WorldServer.class);
            putBoolean(unsafe, world, World.class, "isRemote", remote);
            return world;
        } catch (InstantiationException e) {
            throw new RuntimeException("Failed to create stub World", e);
        }
    }

    private static void setStaticField(Unsafe unsafe, Class<?> clazz, String name, Object value) {
        try {
            Field f = clazz.getDeclaredField(name);
            Object base = unsafe.staticFieldBase(f);
            long offset = unsafe.staticFieldOffset(f);
            unsafe.putObject(base, offset, value);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Static field not found: " + clazz.getName() + "." + name, e);
        }
    }

    private static void putField(Unsafe unsafe, Object obj, Class<?> clazz, String name, Object value) {
        try {
            Field f = clazz.getDeclaredField(name);
            unsafe.putObject(obj, unsafe.objectFieldOffset(f), value);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field not found: " + clazz.getName() + "." + name, e);
        }
    }

    private static void putInt(Unsafe unsafe, Object obj, Class<?> clazz, String name, int value) {
        try {
            Field f = clazz.getDeclaredField(name);
            unsafe.putInt(obj, unsafe.objectFieldOffset(f), value);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field not found: " + clazz.getName() + "." + name, e);
        }
    }

    private static void putBoolean(Unsafe unsafe, Object obj, Class<?> clazz, String name, boolean value) {
        try {
            Field f = clazz.getDeclaredField(name);
            unsafe.putBoolean(obj, unsafe.objectFieldOffset(f), value);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Field not found: " + clazz.getName() + "." + name, e);
        }
    }

    private static Unsafe getUnsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Unsafe", e);
        }
    }

    private MCBootstrap() {}
}
