package com.matthieu.planariumview;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Deux fichiers de config :
 * - planariumview-common.toml : decide par le serveur (ce qui est capture et a quelle frequence) ;
 * - planariumview-client.toml : choix de chaque joueur (affichage, lumiere, sons).
 */
public final class Config {
    // --- Commun / serveur ---
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec.IntValue RADIUS;
    public static final ModConfigSpec.IntValue REFRESH_SECONDS;
    public static final ModConfigSpec.IntValue BLOCK_UPDATE_SECONDS;

    // --- Client ---
    public static final ModConfigSpec CLIENT_SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.EnumValue<Lighting> LIGHTING;
    public static final ModConfigSpec.EnumValue<Fog> FOG;
    public static final ModConfigSpec.BooleanValue BIOME_COLORS;
    public static final ModConfigSpec.BooleanValue WEATHER;
    public static final ModConfigSpec.BooleanValue CLOUDS;
    public static final ModConfigSpec.BooleanValue COLORED_LIGHT;
    public static final ModConfigSpec.BooleanValue OUTSIDE_AMBIENCE;
    public static final ModConfigSpec.BooleanValue AMBIENT_SOUNDS;
    public static final ModConfigSpec.BooleanValue LUNAR_EVENTS;
    public static final ModConfigSpec.EnumValue<CaveFog> CAVE_FOG;

    static {
        ModConfigSpec.Builder common = new ModConfigSpec.Builder();
        common.comment("Server side: what is captured around the Planarium and how often.",
                "On a multiplayer server, the server's file is the one that counts.");
        RADIUS = common
                .comment("Radius (in blocks) captured around the Planarium. Each block appears 32 times bigger.",
                        "On each player's screen, never larger than their render distance (in chunks), except with NeoVoxy (JohnSnow):",
                        "    https://github.com/NHblock-Johnsnow/neo-voxy-multiversion",
                        "Above ~24, the scenery often goes past the render distance and the capture costs more on the server.")
                .defineInRange("radius", 12, 1, 32);
        BLOCK_UPDATE_SECONDS = common
                .comment("Block updates: a block change in the scenery triggers a new capture, with at most this much delay (in seconds).",
                        "A follow-up capture also runs after a block-triggered capture so lighting can settle; continuous changes can therefore cause captures at this interval.",
                        "Lower values show changes faster, but cost more on busy servers (farms, redstone...).",
                        "0 = OFF (block changes only show with the backup refresh).")
                .defineInRange("blockUpdateSeconds", 5, 0, 60);
        REFRESH_SECONDS = common
                .comment("Backup refresh, in seconds (60 minimum; lower values count as 60): a full new capture of the scenery.",
                        "It catches what isn't a block (paintings, item frames, armor stands...), and block changes too if block updates are OFF.",
                        "0 = OFF. With both OFF, the scenery is a frozen snapshot taken when entering the jar.")
                .defineInRange("refreshSeconds", 60, 0, 3600);
        COMMON_SPEC = common.build();

        ModConfigSpec.Builder client = new ModConfigSpec.Builder();
        client.comment("Client side: each player's own display, lighting and sound choices.");
        ENABLED = client
                .comment("Master switch. false = everything is like in Ars Nouveau: no giant scenery, black sky, Ars lighting.")
                .define("enabled", true);
        LIGHTING = client
                .comment("OFF = like in Ars Nouveau: no sky, full brightness everywhere (scenery, miniature and jar interior).",
                        "SKY_AND_SCENERY = the outside sky and time of day in the jar, real light on the giant scenery and the miniature;",
                        "    the jar interior stays fully lit, like in Ars Nouveau.",
                        "FULL = also the light at the Planarium's spot inside the jar: day and night as seen from there (dark in a cave),",
                        "    and the light (and Contraption Lights color) of torches and lamps next to it, through the glass.")
                .defineEnum("lighting", Lighting.FULL);
        FOG = client
                .comment("Fog on the giant scenery.",
                        "OFF = no fog: every block within the view radius stays visible.",
                        "VIEW_RADIUS = the scenery fades into fog at its own edge (the end of the view radius), so it has no hard border.",
                        "RENDER_DISTANCE = the scenery fades into fog at your render distance, like the rest of the world.")
                .defineEnum("fog", Fog.VIEW_RADIUS);
        CAVE_FOG = client
                .comment("Fog color around the scenery when the Planarium is in a closed cave (the sky never shows there).",
                        "VANILLA = the normal fog color, like vanilla caves.",
                        "DARK = black darkness, only deep in a cave with no view of the outside at all.")
                .defineEnum("caveFog", CaveFog.VANILLA);
        BIOME_COLORS = client
                .comment("Biome effects: grass, leaves and water in the giant scenery use the colors of the real biomes around the Planarium.",
                        "The jar interior takes the Planarium's biome (client-side only): its colors, music, ambient sounds and particles.")
                .define("biomeColors", true);
        WEATHER = client
                .comment("Rain and snow from the outside world fall on the giant scenery, only where the sky is open,",
                        "with the rain sound when it rains near the Planarium.")
                .define("weather", true);
        CLOUDS = client
                .comment("Outside clouds in the jar when the Planarium is near the cloud layer (vanilla cloud shape).",
                        "Ignored while Better Clouds is active (its clouds don't show in the jar).")
                .define("clouds", true);
        COLORED_LIGHT = client
                .comment("Colored light from Contraption Lights on the giant scenery and the miniature (if the mod is installed).")
                .define("coloredLight", true);
        OUTSIDE_AMBIENCE = client
                .comment("Dynamic Surroundings plays the ambience of the Planarium's surroundings inside the jar (biome sounds,",
                        "or silence if the Planarium is sheltered, or cave sounds if it is underground), and its biome fog colors show in the jar.",
                        "Requires Dynamic Surroundings.")
                .define("outsideAmbience", true);
        AMBIENT_SOUNDS = client
                .comment("AmbientSounds plays the sounds of the Planarium's surroundings inside the jar (its dimension, biome,",
                        "weather, height and nearby blocks). Requires AmbientSounds.")
                .define("ambientSounds", true);
        LUNAR_EVENTS = client
                .comment("Enhanced Celestials 2: the jar's moon follows the lunar event of the Planarium's world",
                        "(blood moon, blue moon, harvest moon...). Requires Enhanced Celestials 2 and the synced sky.")
                .define("lunarEvents", true);
        CLIENT_SPEC = client.build();
    }

    private Config() {
    }

    /** Mods des integrations : une integration est toujours OFF si son mod n'est pas installe. */
    public static final Mod CONTRAPTION_LIGHTS = new Mod("xyz.atmerek.contraptionlights.light.color.ColorLightEngine");
    public static final Mod DYNAMIC_SURROUNDINGS = new Mod("org.orecruncher.dsurround.config.DimensionInfo");
    public static final Mod AMBIENT_SOUNDS_MOD = new Mod("team.creative.ambientsounds.AmbientSounds");
    public static final Mod ENHANCED_CELESTIALS = new Mod("dev.corgitaco.enhancedcelestials2core.EnhancedCelestials");

    /** Mod reconnu par une de ses classes (verifie une seule fois). */
    public static final class Mod {
        private final String className;
        private Boolean present;

        private Mod(String className) {
            this.className = className;
        }

        public boolean present() {
            if (present == null) {
                boolean found;
                try {
                    Class.forName(className, false, Config.class.getClassLoader());
                    found = true;
                } catch (Throwable e) {
                    found = false;
                }
                present = found;
            }
            return present;
        }
    }

    /** Les trois niveaux d'eclairage. */
    public enum Fog {
        OFF, VIEW_RADIUS, RENDER_DISTANCE
    }

    /** Couleur du brouillard dans les cavernes. */
    public enum CaveFog {
        VANILLA, DARK
    }

    public enum Lighting {
        OFF, SKY_AND_SCENERY, FULL
    }

    public static boolean enabled() {
        return ENABLED.get();
    }

    /** Ciel du dehors dans le bocal (soleil, lune, heure, couleur du ciel). */
    public static boolean syncSky() {
        return enabled() && LIGHTING.get() != Lighting.OFF;
    }

    /** Vraie lumiere sur ce qu'on voit a travers le verre : le decor geant et la miniature. */
    public static boolean realLighting() {
        return syncSky();
    }

    /** Lumiere de l'endroit du Planarium a l'interieur du bocal (jour, nuit, caverne, torches a cote). */
    public static boolean outsideLight() {
        return enabled() && LIGHTING.get() == Lighting.FULL;
    }

    public static boolean biomeColors() {
        return enabled() && BIOME_COLORS.get();
    }

    public static boolean weather() {
        return enabled() && WEATHER.get();
    }

    public static boolean clouds() {
        return enabled() && CLOUDS.get() && !BetterCloudsCompat.active();
    }

    public static boolean coloredLight() {
        return enabled() && COLORED_LIGHT.get() && CONTRAPTION_LIGHTS.present();
    }

    public static boolean ambientSounds() {
        return enabled() && AMBIENT_SOUNDS.get() && AMBIENT_SOUNDS_MOD.present();
    }

    /** Obscurite noire dans les cavernes fermees. */
    public static boolean darkCaves() {
        return enabled() && CAVE_FOG.get() == CaveFog.DARK;
    }

    public static boolean lunarEvents() {
        return enabled() && LUNAR_EVENTS.get() && ENHANCED_CELESTIALS.present();
    }

    public static boolean outsideAmbience() {
        return enabled() && OUTSIDE_AMBIENCE.get() && DYNAMIC_SURROUNDINGS.present();
    }
}
