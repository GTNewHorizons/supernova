package com.mitchej123.supernova.compat.colors;

import com.mitchej123.supernova.Supernova;
import cpw.mods.fml.common.Loader;

public enum ModCompat {

    PROJECTRED(ProjectRedColors::register, "ProjRed|Illumination"),
    ET_FUTURUM(EtFuturumColors::register, "etfuturum"),
    BOTANIA(BotaniaColors::register, "Botania"),
    THAUMCRAFT(ThaumcraftColors::register, "Thaumcraft"),
    THAUMIC_EXPLORATION(ThaumicExplorationColors::register, "ThaumicExploration"),
    THAUMIC_BASES(ThaumicBasesColors::register, "thaumicbases"),
    THAUMIC_HORIZONS(ThaumicHorizonsColors::register, "ThaumicHorizons"),
    GADOMANCY(GadomancyColors::register, "gadomancy"),
    TWILIGHT_FOREST(TwilightForestColors::register, "TwilightForest"),
    NATURA(NaturaColors::register, "Natura"),
    CHISEL(ChiselColors::register, "chisel"),
    WITCHERY(WitcheryColors::register, "witchery"),
    BLOOD_ARSENAL(BloodArsenalColors::register, "BloodArsenal"),
    HEE(HEEColors::register, "HardcoreEnderExpansion"),
    EXTRA_UTILITIES(ExtraUtilitiesColors::register, "ExtraUtilities"),
    OPENCOMPUTERS(OpenComputersColors::register, "OpenComputers"),
    NETHERLICIOUS(NetherliciousColors::register, "netherlicious"),
    BIBLIOCRAFT(BiblioCraftColors::register, "BiblioCraft", "BiblioWoodsBoP", "BiblioWoodsForestry", "BiblioWoodsNatura"),
    GALACTICRAFT(GalacticraftColors::register, "GalacticraftCore", "GalacticraftMars"),
    GALAXY_SPACE(GalaxySpaceColors::register, "GalaxySpace"),
    CAMPFIRE_BACKPORT(CampfireBackportColors::register, "campfirebackport"),
    RAILCRAFT(RailcraftColors::register, "Railcraft"),
    HBM(HBMColors::register, "hbm"),
    MISC(
            MiscColors::register,
            "BuildCraft|Core",
            "BuildCraft|Builders",
            "ThermalExpansion",
            "ThermalFoundation",
            "IC2",
            "EnderIO",
            "appliedenergistics2",
            "DraconicEvolution",
            "Ztones",
            "CarpentersBlocks",
            "TConstruct",
            "harvestcraft",
            "catwalks",
            "avaritiaddons",
            "RandomThings",
            "TMechworks",
            "EMT",
            "GraviSuite",
            "AdvancedSolarPanel",
            "ExtraBees",
            "Forestry",
            "MagicBees",
            "FloodLights",
            "computronics",
            "OpenBlocks",
            "lootgames",
            "BiomesOPlenty",
            "ThaumicTinkerer",
            "Automagy",
            "kekztech"),
    ;

    private final Runnable registrar;
    private final String[] modIds;

    ModCompat(Runnable registrar, String... modIds) {
        this.registrar = registrar;
        this.modIds = modIds;
    }

    public static void registerAll() {
        for (ModCompat mod : values()) {
            if (mod.anyModLoaded()) {
                try {
                    mod.registrar.run();
                } catch (Exception e) {
                    Supernova.LOG.error("Failed to register color compat for {}", mod.name(), e);
                }
            }
        }
    }

    private boolean anyModLoaded() {
        for (String modId : modIds) {
            if (Loader.isModLoaded(modId)) return true;
        }
        return false;
    }
}
