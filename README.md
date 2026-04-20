# Supernova

RGB colored lighting engine for Minecraft 1.7.10. Replaces the vanilla lighting engines with a queue-based BFS propagator inspired by [Starlight](https://github.com/PaperMC/Starlight).

Both block light and sky light are propagated per-channel (R, G, B) using 4-bit-per-channel nibble arrays. A scalar (non-RGB) mode is also available for a fast Starlight-equivalent engine without color. 

Blocks can provide dynamic colored emission via the `ColoredLightSource` interface or be assigned static colors through config files. Per-channel light absorption is configurable via the `ColoredTranslucency` interface or `TranslucencyRegistry` API.

## Features

- Full RGB colored block light and sky light. Torches, glowstone, lava, and modded blocks all emit colored light.
- Scalar (non-RGB) mode for a fast Starlight-inspired engine without color.
- Colored glass and translucent blocks filter light by color.
- Multiple light blending modes (configurable in-game).
- Threaded lighting. Sky and block light update on separate worker threads for minimal TPS impact.
- Customizable block colors and translucency via config files.
- Works with Angelica rendering pipeline and vanilla.

## API

- `ColoredLightSource` / `PositionalColoredLightSource` - dynamic RGB emission per block (metadata or position-dependent)
- `ColoredTranslucency` / `PositionalColoredTranslucency` - per-channel light absorption
- `FaceLightOcclusion` - directional light opacity override per face
- `LightColorRegistry` / `TranslucencyRegistry` - register block colors and translucency at init
- `PackedColorLight` - utilities for the `0x0R0G0B` packed format
- `LightColors` - named color palette constants

## Dependencies

**Required:**
* [GTNHLib](https://github.com/GTNewHorizons/GTNHLib)
* [Hodgepodge](https://github.com/GTNewHorizons/Hodgepodge) (>= 2.7.107)

**Optional:**
* [ChunkAPI](https://github.com/LegacyModdingMC/ChunkAPI) (requires [FalsePatternLib](https://github.com/FalsePattern/FalsePatternLib)) - required for RGB mode persistence; without it, Supernova forces scalar mode
* [Angelica](https://github.com/GTNewHorizons/Angelica) (>= 2.1.6, RGB-aware rendering; falls back to vanilla tinting without it)
* [EndlessIDs](https://github.com/GTMEGA/EndlessIDs)

## Incompatibilities

Supernova replaces the vanilla lighting engine, and does so in such a fashion that it's incompatible with other lighting engines and mixins.

* ArchaicFix - Phosphor - Phosphor must be disabled.
* CoreTweaks - fixHeightmapRange/lightFixStare - Config/Mixin must be disabled.
* Lumi/RPLE - Incompatible. 

## Configuration

- `config/supernova.cfg` - lighting mode (`RGB` or `SCALAR`), client blend mode
- `config/supernova-colors.cfg` - per-block RGB emission overrides
- `config/supernova-translucency.cfg` - per-block RGB absorption overrides

## Credits

- [Starlight](https://github.com/PaperMC/Starlight) by SpottedLeaf (LGPL-3.0). Architecture and core algorithms (BFS propagation, SWMR nibble arrays, deferred lighting) are derived from Starlight's design.
