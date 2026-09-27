package com.mitchej123.supernova.world;

import com.mitchej123.supernova.api.ExtendedWorld;
import com.mitchej123.supernova.light.WorldLightManager;

/**
 * Internal extension of {@link ExtendedWorld} exposing engine internals. Mixed into {@code net.minecraft.world.World}.
 */
public interface SupernovaWorld extends ExtendedWorld {

    /** Latched by supernova$shutdown: at most one manager per World object for its lifetime, and null forever after. */
    WorldLightManager supernova$getLightManager();

    /** The field without the lazy construction: a chunk save after supernova$shutdown must not resurrect the manager and its worker threads. */
    WorldLightManager supernova$lightManagerIfPresent();

    void supernova$shutdown();
}
