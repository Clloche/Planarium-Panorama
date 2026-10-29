package com.matthieu.planariumview.config;


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
    public static final ModConfigSpec.IntValue FAR_RADIUS;
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
    public static final ModConfigSpec.BooleanValue SCENERY_AMBIENCE;
    public static final ModConfigSpec.BooleanValue SCENERY_PARTICLES;
    public static final ModConfigSpec.BooleanValue WORLD_SOUNDS;
    public static final ModConfigSpec.BooleanValue OUTSIDE_MOBS;
    public static final ModConfigSpec.BooleanValue OUTSIDE_VEHICLES;
    public static final ModConfigSpec.BooleanValue MINIATURE_MOBS;
    public static final ModConfigSpec.BooleanValue OUTSIDE_AMBIENCE;
    public static final ModConfigSpec.BooleanValue AMBIENT_SOUNDS;
    public static final ModConfigSpec.BooleanValue LUNAR_EVENTS;
    public static final ModConfigSpec.BooleanValue SUB_LEVEL_VIEWS;
    public static final ModConfigSpec.EnumValue<CaveFog> CAVE_FOG;
    public static final ModConfigSpec.IntValue NEIGHBOR_PLANARIUMS;

    static {
        ModConfigSpec.Builder common = new ModConfigSpec.Builder();
        common.comment("Server side: what is captured around the Planarium and how often.",
                "On a multiplayer server, the server's file is the one that counts.");
        RADIUS = common
                .comment("Detail radius (in blocks) around the Planarium: there, the scenery stays alive (block changes, animated",
                        "blocks, particles, sounds, mobs, colored light). Each block appears 32 times bigger.",
                        "Past it, farRadius continues the scenery as a frozen snapshot. A larger value costs the server more on each update.")
                .defineInRange("radius", 12, 1, 32);
        FAR_RADIUS = common
                .comment("View distance (in blocks): past the detail radius, the scenery continues up to this distance as a frozen",
                        "snapshot (blocks only: no animations, particles, sounds or mobs there; Sable structures still show and move).",
                        "Captured when entering the jar, again with the backup refresh, and when a moving Planarium (Sable) has stopped",
                        "for a few seconds. While moving, world-anchored sections continue the scenery up to this distance.",
                        "Distant chunks are loaded without simulation; fog follows the terrain already built on your client.",
                        "0 = OFF (or any value not larger than the detail radius).")
                .defineInRange("farRadius", 64, 0, 96);
        BLOCK_UPDATE_SECONDS = common
                .comment("Block updates in the detail radius: a block change there shows up with at most this much delay (in seconds).",
                        "Changes that don't affect light (redstone, doors...) only send the changed blocks; others take a new capture,",
                        "followed by a second one a bit later so lighting can settle. Continuous changes can therefore cause updates at this interval.",
                        "Lower values show changes faster, but cost more on busy servers (farms, redstone...).",
                        "5 minimum (lower values count as 5). 0 = OFF (block changes only show with the backup refresh).")
                .defineInRange("blockUpdateSeconds", 10, 0, 60);
        REFRESH_SECONDS = common
                .comment("Backup refresh, in seconds (60 to 1200, default 300; lower values count as 60): a full new capture of the scenery.",
                        "It catches what isn't a block (paintings, item frames, armor stands...), and block changes too if block updates are OFF.",
                        "0 = OFF. With both OFF, the scenery is a frozen snapshot taken when entering the jar.")
                .defineInRange("refreshSeconds", 300, 0, 1200);
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
                        "OFF = no fog: the whole scenery stays visible.",
                        "SCENERY_EDGE = the scenery fades into fog at its own edge (the view distance), so it has no hard border.",
                        "RENDER_DISTANCE = the scenery fades into fog at your render distance, like the rest of the world",
                        "    (the frozen snapshot past the detail radius is then not drawn).")
                .defineEnum("fog", Fog.SCENERY_EDGE);
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
        SCENERY_AMBIENCE = client
                .comment("Ambient sounds of the scenery blocks (fire, lava, flowing water, portals, campfires...), from their direction",
                        "in the scenery. Made on your side, like Minecraft does around the player: works even when the outside world is paused.")
                .define("sceneryAmbience", true);
        SCENERY_PARTICLES = client
                .comment("Particles of the scenery blocks (torch and campfire smoke, flames, lava...), 32 times bigger like the scenery,",
                        "and the particles of outside explosions. Follows Minecraft's Particles setting.")
                .define("sceneryParticles", true);
        WORLD_SOUNDS = client
                .comment("Sounds from the outside world around the Planarium (mobs, redstone, explosions, jukeboxes...), from their direction",
                        "in the scenery. Sounds directly caused by a player are excluded.",
                        "While ON, the detail radius around the Planarium keeps running as if you were there (machines, mobs, furnaces).",
                        "OFF: stops keeping that area active, unless outside mobs or vehicles are ON or a player is there; scenery ambience remains.")
                .define("worldSounds", true);
        OUTSIDE_MOBS = client
                .comment("Mobs around the Planarium, in the detail radius of the giant scenery (up to 64 mobs and vehicles combined, the closest).",
                        "While ON, the detail radius and a small surrounding margin keep running so they move, even with outside world sounds OFF.",
                        "OFF: mobs are not shown and their sounds are not played either.")
                .define("outsideMobs", false);
        OUTSIDE_VEHICLES = client
                .comment("Boats, rafts and minecarts around the Planarium, in the detail radius of the giant scenery.",
                        "Independent of outsideMobs. Mob passengers are shown when both options are ON.",
                        "While ON, the detail radius and a small surrounding margin keep running, even with outside world sounds OFF.",
                        "Up to 64 mobs and vehicles combined, the closest.")
                .define("outsideVehicles", false);
        MINIATURE_MOBS = client
                .comment("Mobs inside the miniature of the jar, 32 times smaller like its blocks, with small idle animations",
                        "(breathing, wings, looking around). They stay at their last known positions; these copies do not",
                        "keep the pocket running. Up to 4 miniatures within 8 blocks, with at most 64 mobs each.")
                .define("miniatureMobs", true);
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
        SUB_LEVEL_VIEWS = client
                .comment("Show Sable / Create Aeronautics structures in the giant scenery and miniatures.",
                        "OFF stops their captures for this player. Physical structures, their interior lighting and Ars Sable still work.",
                        "If ships do not reappear after switching this back ON, leave and rejoin the world.")
                .define("subLevelViews", true);
        NEIGHBOR_PLANARIUMS = client
                .comment("Inside of the other Planariums in the detail radius, at real size where they stand in the scenery",
                        "(a frozen snapshot of their blocks and light, taken when entering the jar: purely visual).",
                        "Number shown, nearest first. 0 = OFF.")
                .defineInRange("neighborPlanariums", 4, 0, 10);
        CLIENT_SPEC = client.build();
    }

    private Config() {
    }

    /** Mods des integrations : une integration est toujours OFF si son mod n'est pas installe. */
    public static final Mod CONTRAPTION_LIGHTS = new Mod("xyz.atmerek.contraptionlights.light.color.ColorLightEngine");
    public static final Mod DYNAMIC_SURROUNDINGS = new Mod("org.orecruncher.dsurround.config.DimensionInfo");
    public static final Mod AMBIENT_SOUNDS_MOD = new Mod("team.creative.ambientsounds.AmbientSounds");
    public static final Mod ENHANCED_CELESTIALS = new Mod("dev.corgitaco.enhancedcelestials2core.EnhancedCelestials");
    public static final Mod SABLE = new Mod("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");

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
        OFF, SCENERY_EDGE, RENDER_DISTANCE
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
        return enabled() && CLOUDS.get();
    }

    public static boolean sceneryAmbience() {
        return enabled() && SCENERY_AMBIENCE.get();
    }

    /** Nombre de Planariums voisins dont on dessine l'interieur (0 = aucun). */
    public static int neighborPlanariums() {
        return enabled() ? NEIGHBOR_PLANARIUMS.get() : 0;
    }

    public static boolean sceneryParticles() {
        return enabled() && SCENERY_PARTICLES.get();
    }

    public static boolean worldSounds() {
        return enabled() && WORLD_SOUNDS.get();
    }

    /** Mobs du dehors dans le decor geant (voir OutsideMobs). */
    public static boolean outsideMobs() {
        return enabled() && OUTSIDE_MOBS.get();
    }

    public static boolean outsideVehicles() {
        return enabled() && OUTSIDE_VEHICLES.get();
    }

    /** Mobs dans la miniature du bocal (voir MiniEntities). */
    public static boolean miniatureMobs() {
        return enabled() && MINIATURE_MOBS.get();
    }

    public static boolean outsideEntities() {
        return outsideMobs() || outsideVehicles();
    }

    // Blocs et explosions : avec les sons du monde exterieur (plus de reglage separe). Les mobs : seulement s'ils sont
    // aussi affiches (on n'entend pas des mobs qu'on ne voit pas).
    public static boolean mobSounds() {
        return worldSounds() && outsideMobs();
    }

    public static boolean blockSounds() {
        return worldSounds();
    }

    public static boolean explosionSounds() {
        return worldSounds();
    }

    /** Musique des jukebox : fait partie des sons du monde exterieur. */
    public static boolean jukeboxSounds() {
        return worldSounds();
    }

    /** Compatibilite automatique avec Contraption Lights pour le decor geant et la miniature. */
    public static boolean coloredLight() {
        return enabled() && CONTRAPTION_LIGHTS.present();
    }

    /**
     * Couleurs a l'interieur du bocal (lumiere du dehors par les parois, parois, structures Sable du bocal) :
     * compatibilite automatique, comme pour le decor et la miniature.
     */
    public static boolean jarColoredLight() {
        return coloredLight();
    }

    public static boolean subLevelViews() {
        return enabled() && SUB_LEVEL_VIEWS.get() && SABLE.present();
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
