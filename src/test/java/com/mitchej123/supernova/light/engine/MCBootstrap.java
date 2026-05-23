package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.util.WorldUtil;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.RegistryNamespacedDefaultedByKey;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * Minimal MC bootstrap for unit tests.
 * <p>
 * Swaps {@code Block.blockRegistry} with a plain {@link RegistryNamespacedDefaultedByKey} to bypass FML's {@code FMLControlledNamespacedRegistry} which
 * requires {@code LaunchClassLoader}. Block instances are created via {@link Unsafe#allocateInstance} to skip the instance initializer that casts the registry
 * to FML's type.
 */
public final class MCBootstrap {

    private static boolean initialized = false;
    private static World serverWorld;

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        final Unsafe unsafe = getUnsafe();

        // noinspection ResultOfMethodCallIgnored
        Block.blockRegistry.getClass();

        RegistryNamespacedDefaultedByKey registry = new RegistryNamespacedDefaultedByKey("minecraft:air");
        setStaticField(unsafe, Block.class, "blockRegistry", registry);

        // Create blocks via Unsafe -- skips Block's instance initializer which does:
        //   delegate = ((FMLControlledNamespacedRegistry)blockRegistry).getDelegate(this, Block.class)
        Block air = createBlock(unsafe, Material.air, 0, false, 0);
        Block stone = createBlock(unsafe, Material.rock, 255, true, 0);

        // Register in the clean registry. This populates both name->object and id->object maps.
        registry.addObject(0, "air", air);
        registry.addObject(1, "stone", stone);

        // Blocks class clinit reads from blockRegistry:
        //   public static final Block air = (Block)Block.blockRegistry.getObject("air");
        @SuppressWarnings("unused") Block forceInit = net.minecraft.init.Blocks.air;

        WorldUtil.setBounds(0, 15);

        // Create stub World via Unsafe (skips constructor entirely)
        serverWorld = createStubWorld(unsafe);
    }

    public static World getServerWorld() {
        if (!initialized) throw new IllegalStateException("Call MCBootstrap.init() first");
        return serverWorld;
    }

    private static Block createBlock(Unsafe unsafe, Material material, int lightOpacity, boolean opaque, int lightValue) {
        try {
            Block block = (Block) unsafe.allocateInstance(Block.class);
            putField(unsafe, block, Block.class, "blockMaterial", material);
            putInt(unsafe, block, Block.class, "lightOpacity", lightOpacity);
            putBoolean(unsafe, block, Block.class, "opaque", opaque);
            putInt(unsafe, block, Block.class, "lightValue", lightValue);
            return block;
        } catch (InstantiationException e) {
            throw new RuntimeException("Failed to create Block instance", e);
        }
    }

    private static World createStubWorld(Unsafe unsafe) {
        try {
            World world = (World) unsafe.allocateInstance(WorldServer.class);
            putBoolean(unsafe, world, World.class, "isRemote", false);
            WorldProvider provider = (WorldProvider) unsafe.allocateInstance(WorldProviderSurface.class);
            putInt(unsafe, provider, WorldProvider.class, "dimensionId", 0);
            putField(unsafe, world, World.class, "provider", provider);
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
