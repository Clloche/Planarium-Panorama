package com.matthieu.planariumview.config;

import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.compat.better_clouds.BetterCloudsCompat;
import com.matthieu.planariumview.compat.simple_clouds.SimpleCloudsCompat;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.LabelOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Menu de configuration, construit avec YACL (Yet Another Config Lib) : onglets General (eclairage, distances et
 * rafraichissement), Affichage, Entites et Integrations ; description a droite, recherche et remise a zero.
 *
 * L'interrupteur general grise les autres options du joueur (leurs valeurs sont gardees).
 * Les options d'integration sont grisees si le mod n'est pas installe, celles du serveur en multijoueur.
 *
 * Chaque changement est sauvegarde tout de suite (pas de bouton "Sauvegarder", seulement "Termine").
 * Le decor est reconstruit une seule fois, en fermant le menu (Termine ou Echap), voir YaclEscapeMixin.
 */
public final class ConfigScreen {
    private static final String KEY = "planariumview.config.";
    /** Rafraichissement de secours : curseur en minutes (0 = OFF, puis 1 a 20 min). */
    private static final int MAX_REFRESH_MINUTES = 20;

    private ConfigScreen() {
    }

    public static Screen create(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        // Sur un serveur multijoueur, le rayon et le rafraichissement sont decides par le serveur.
        boolean serverSide = mc.level != null && mc.getSingleplayerServer() == null;
        boolean dsPresent = Config.DYNAMIC_SURROUNDINGS.present();
        boolean asPresent = Config.AMBIENT_SOUNDS_MOD.present();
        boolean ecPresent = Config.ENHANCED_CELESTIALS.present();
        boolean sablePresent = Config.SABLE.present();

        // Options du joueur grisees quand l'interrupteur general est OFF (si elles sont possibles par ailleurs).
        List<Gated> gated = new ArrayList<>();

        Option<Boolean> master = Option.<Boolean>createBuilder()
                .name(Component.translatable(KEY + "enabled"))
                .description(describe("enabled", onOff(Config.ENABLED.getDefault())))
                .binding(Config.ENABLED.getDefault(), Config.ENABLED::get, Config.ENABLED::set)
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .listener((opt, value) -> refreshGated(gated, value))
                .build();

        // Curseur a trois crans : OFF a gauche, Complet a droite.
        Config.Lighting[] levels = Config.Lighting.values();
        Option<Integer> lighting = Option.<Integer>createBuilder()
                .name(Component.translatable(KEY + "lighting"))
                .description(describe("lighting", lightingLabel(Config.LIGHTING.getDefault())))
                .binding(Config.LIGHTING.getDefault().ordinal(), () -> Config.LIGHTING.get().ordinal(),
                        v -> Config.LIGHTING.set(levels[Math.max(0, Math.min(levels.length - 1, v))]))
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, levels.length - 1).step(1)
                        .formatValue(v -> lightingLabel(levels[Math.max(0, Math.min(levels.length - 1, v))])))
                .build();
        Option<Boolean> biome = bool("biome", Config.BIOME_COLORS.getDefault(), Config.BIOME_COLORS::get, Config.BIOME_COLORS::set);
        Option<Boolean> weather = bool("weather", Config.WEATHER.getDefault(), Config.WEATHER::get, Config.WEATHER::set);
        Option<Boolean> sceneryAmbience = bool("scenery_ambience", Config.SCENERY_AMBIENCE.getDefault(),
                Config.SCENERY_AMBIENCE::get, Config.SCENERY_AMBIENCE::set);
        Option<Boolean> sceneryParticles = bool("scenery_particles", Config.SCENERY_PARTICLES.getDefault(),
                Config.SCENERY_PARTICLES::get, Config.SCENERY_PARTICLES::set,
                Component.translatable("options.particles").withStyle(ChatFormatting.BOLD));
        // Notes qui changent avec "Faire vivre le dehors" et "Mobs du dehors" (voir refreshDependents) : YACL garde les
        // noms et les descriptions construits une fois, on change donc le contenu de ces textes.
        MutableComponent worldSoundsNote = Component.empty();
        MutableComponent mobsNote = Component.empty();
        MutableComponent vehiclesNote = Component.empty();
        MutableComponent mobSoundsNote = Component.empty();
        MutableComponent mobsName = Component.translatable(KEY + "outside_mobs");
        MutableComponent vehiclesName = Component.translatable(KEY + "outside_vehicles");
        MutableComponent mobSoundsName = Component.translatable(KEY + "mob_sounds");
        // Sons du monde exterieur : avec l'avertissement (la zone detaillee reste active).
        Option<Boolean> worldSounds = Option.<Boolean>createBuilder()
                .name(Component.translatable(KEY + "world_sounds"))
                .description(OptionDescription.of(Component.translatable(KEY + "world_sounds.tooltip"), Component.empty(),
                        Component.translatable(KEY + "world_sounds.warning", optionName("radius"), optionName("scenery_ambience"),
                                optionName("outside_live")),
                        Component.empty(),
                        Component.translatable(KEY + "default", onOff(Config.WORLD_SOUNDS.getDefault())).withStyle(ChatFormatting.GRAY),
                        worldSoundsNote))
                .binding(Config.WORLD_SOUNDS.getDefault(), Config.WORLD_SOUNDS::get, Config.WORLD_SOUNDS::set)
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();
        // Faire vivre le dehors : autorisation du monde (config SERVER, envoyee aux joueurs). Modifiable en solo, avec un
        // monde ouvert ; en multijoueur, la valeur du serveur est montree, grisee.
        boolean worldOpen = Config.SERVER_SPEC.isLoaded();
        Option<Boolean> outsideLive = Option.<Boolean>createBuilder()
                .name(Component.translatable(KEY + "outside_live"))
                .description(OptionDescription.of(Component.translatable(KEY + "outside_live.tooltip"), Component.empty(),
                        Component.translatable(KEY + "default", onOff(Config.OUTSIDE_LIVE.getDefault())).withStyle(ChatFormatting.GRAY),
                        Component.empty(),
                        Component.translatable(KEY + (serverSide ? "server_controlled" : worldOpen ? "world_setting" : "world_closed"))
                                .withStyle(ChatFormatting.YELLOW)))
                .binding(Config.OUTSIDE_LIVE.getDefault(),
                        () -> Config.SERVER_SPEC.isLoaded() ? Config.OUTSIDE_LIVE.get() : Config.OUTSIDE_LIVE.getDefault(),
                        value -> {
                            if (Config.SERVER_SPEC.isLoaded()) {
                                Config.OUTSIDE_LIVE.set(value);
                            }
                        })
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .available(!serverSide && worldOpen)
                .build();
        Option<Boolean> outsideMobs = Option.<Boolean>createBuilder()
                .name(mobsName)
                .description(OptionDescription.of(Component.translatable(KEY + "outside_mobs.tooltip", optionName("mob_sounds")),
                        Component.empty(),
                        Component.translatable(KEY + "outside_mobs.warning", optionName("radius")),
                        Component.empty(),
                        Component.translatable(KEY + "default", onOff(Config.OUTSIDE_MOBS.getDefault())).withStyle(ChatFormatting.GRAY),
                        mobsNote))
                .binding(Config.OUTSIDE_MOBS.getDefault(), Config.OUTSIDE_MOBS::get, Config.OUTSIDE_MOBS::set)
                // Sans "Faire vivre le dehors" : la valeur reste, mais en gris (comme une integration sans son mod).
                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(false)
                        .formatValue(v -> outsideLive.pendingValue() ? coloredOnOff(v) : greyOnOff(v)))
                .build();
        Option<Boolean> mobSounds = Option.<Boolean>createBuilder()
                .name(mobSoundsName)
                .description(OptionDescription.of(Component.translatable(KEY + "mob_sounds.tooltip"), Component.empty(),
                        Component.translatable(KEY + "default", onOff(Config.MOB_SOUNDS.getDefault())).withStyle(ChatFormatting.GRAY),
                        mobSoundsNote))
                .binding(Config.MOB_SOUNDS.getDefault(), Config.MOB_SOUNDS::get, Config.MOB_SOUNDS::set)
                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(false)
                        .formatValue(v -> outsideLive.pendingValue() && outsideMobs.pendingValue() ? coloredOnOff(v) : greyOnOff(v)))
                .build();
        Option<Boolean> outsideVehicles = Option.<Boolean>createBuilder()
                .name(vehiclesName)
                .description(OptionDescription.of(Component.translatable(KEY + "outside_vehicles.tooltip", optionName("outside_mobs")),
                        Component.empty(),
                        Component.translatable(KEY + "outside_vehicles.warning", optionName("radius")),
                        Component.empty(),
                        Component.translatable(KEY + "default", onOff(Config.OUTSIDE_VEHICLES.getDefault())).withStyle(ChatFormatting.GRAY),
                        vehiclesNote))
                .binding(Config.OUTSIDE_VEHICLES.getDefault(), Config.OUTSIDE_VEHICLES::get, Config.OUTSIDE_VEHICLES::set)
                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(false)
                        .formatValue(v -> outsideLive.pendingValue() ? coloredOnOff(v) : greyOnOff(v)))
                .build();
        Option<Boolean> miniatureMobs = Option.<Boolean>createBuilder()
                .name(Component.translatable(KEY + "miniature_mobs"))
                .description(describe("miniature_mobs", onOff(Config.MINIATURE_MOBS.getDefault())))
                .binding(Config.MINIATURE_MOBS.getDefault(), Config.MINIATURE_MOBS::get, Config.MINIATURE_MOBS::set)
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();
        // Better Clouds actif ou Simple Clouds installe : leurs nuages remplacent ceux de Minecraft dehors, mais le bocal
        // dessine des nuages vanilla. On laisse le choix, avec un avertissement dans la description.
        String cloudsMod = SimpleCloudsCompat.installed() ? "Simple Clouds" : BetterCloudsCompat.active() ? "Better Clouds" : null;
        OptionDescription cloudsDescription = cloudsMod == null ? describe("clouds", onOff(Config.CLOUDS.getDefault()))
                : OptionDescription.of(Component.translatable(KEY + "clouds.tooltip"), Component.empty(),
                Component.translatable(KEY + "clouds.other_mod", cloudsMod).withStyle(ChatFormatting.YELLOW),
                Component.empty(),
                Component.translatable(KEY + "default", onOff(Config.CLOUDS.getDefault())).withStyle(ChatFormatting.GRAY));
        Option<Boolean> clouds = Option.<Boolean>createBuilder()
                .name(Component.translatable(KEY + "clouds"))
                .description(cloudsDescription)
                .binding(Config.CLOUDS.getDefault(), Config.CLOUDS::get, Config.CLOUDS::set)
                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(false).formatValue(ConfigScreen::coloredOnOff))
                .build();
        Option<Config.Fog> fog = Option.<Config.Fog>createBuilder()
                .name(Component.translatable(KEY + "fog"))
                .description(describe("fog", fogLabel(Config.FOG.getDefault()), optionName("radius")))
                .binding(Config.FOG.getDefault(), Config.FOG::get, Config.FOG::set)
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(Config.Fog.class)
                        .formatValue(ConfigScreen::fogLabel))
                .build();
        Option<Integer> neighbors = Option.<Integer>createBuilder()
                .name(Component.translatable(KEY + "neighbors"))
                .description(describe("neighbors", neighborsLabel(Config.NEIGHBOR_PLANARIUMS.getDefault()), optionName("radius")))
                .binding(Config.NEIGHBOR_PLANARIUMS.getDefault(), Config.NEIGHBOR_PLANARIUMS::get, Config.NEIGHBOR_PLANARIUMS::set)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, 10).step(1)
                        .formatValue(ConfigScreen::neighborsLabel))
                .build();
        Option<Config.CaveFog> caveFog = Option.<Config.CaveFog>createBuilder()
                .name(Component.translatable(KEY + "cave_fog"))
                .description(describe("cave_fog", caveFogLabel(Config.CAVE_FOG.getDefault())))
                .binding(Config.CAVE_FOG.getDefault(), Config.CAVE_FOG::get, Config.CAVE_FOG::set)
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(Config.CaveFog.class)
                        .formatValue(ConfigScreen::caveFogLabel))
                .build();

        Option<Boolean> ambience = integration("ambience", "Dynamic Surroundings", dsPresent,
                Config.OUTSIDE_AMBIENCE.getDefault(), Config.OUTSIDE_AMBIENCE::get, Config.OUTSIDE_AMBIENCE::set,
                optionName("scenery_particles"));
        Option<Boolean> ambientSounds = integration("ambient_sounds", "AmbientSounds", asPresent,
                Config.AMBIENT_SOUNDS.getDefault(), Config.AMBIENT_SOUNDS::get, Config.AMBIENT_SOUNDS::set);
        Option<Boolean> lunar = integration("lunar_events", "Enhanced Celestials 2", ecPresent,
                Config.LUNAR_EVENTS.getDefault(), Config.LUNAR_EVENTS::get, Config.LUNAR_EVENTS::set);
        Option<Boolean> subLevels = integration("sub_levels", "Sable / Create Aeronautics", sablePresent,
                Config.SUB_LEVEL_VIEWS.getDefault(), Config.SUB_LEVEL_VIEWS::get, Config.SUB_LEVEL_VIEWS::set);

        gated.add(new Gated(lighting, () -> true));
        gated.add(new Gated(biome, () -> true));
        gated.add(new Gated(weather, () -> true));
        gated.add(new Gated(sceneryAmbience, () -> true));
        gated.add(new Gated(sceneryParticles, () -> true));
        gated.add(new Gated(worldSounds, () -> true));
        // Grisees sans l'autorisation du monde (leurs valeurs sont gardees) ; les sons des mobs, aussi sans leur affichage.
        gated.add(new Gated(outsideMobs, outsideLive::pendingValue));
        gated.add(new Gated(outsideVehicles, outsideLive::pendingValue));
        gated.add(new Gated(mobSounds, () -> outsideLive.pendingValue() && outsideMobs.pendingValue()));
        gated.add(new Gated(miniatureMobs, () -> true));
        gated.add(new Gated(clouds, () -> true));
        gated.add(new Gated(fog, () -> true));
        gated.add(new Gated(caveFog, () -> true));
        gated.add(new Gated(neighbors, () -> true));
        gated.add(new Gated(ambience, () -> dsPresent));
        gated.add(new Gated(ambientSounds, () -> asPresent));
        gated.add(new Gated(lunar, () -> ecPresent));
        gated.add(new Gated(subLevels, () -> sablePresent));
        refreshGated(gated, Config.ENABLED.get());
        Runnable dependents = () -> refreshDependents(outsideLive.pendingValue(), outsideMobs.pendingValue(),
                worldSoundsNote, mobsNote, vehiclesNote, mobSoundsNote, mobsName, vehiclesName, mobSoundsName);
        dependents.run();
        // Changer l'autorisation ou l'affichage des mobs met a jour les options qui en dependent, tout de suite.
        outsideLive.addListener((opt, value) -> {
            refreshGated(gated, master.pendingValue());
            dependents.run();
        });
        outsideMobs.addListener((opt, value) -> {
            refreshGated(gated, master.pendingValue());
            dependents.run();
        });

        Option<Integer> radius = Option.<Integer>createBuilder()
                .name(Component.translatable(KEY + "radius"))
                .description(describeServer("radius",
                        Component.translatable(KEY + "radius.value", Config.RADIUS.getDefault()), serverSide))
                .binding(Config.RADIUS.getDefault(), Config.RADIUS::get, Config.RADIUS::set)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(1, 32).step(1)
                        .formatValue(v -> Component.translatable(KEY + "radius.value", v)))
                .available(!serverSide)
                .build();
        Option<Integer> farRadius = Option.<Integer>createBuilder()
                .name(Component.translatable(KEY + "far_radius"))
                .description(describeServer("far_radius", farRadiusLabel(Config.FAR_RADIUS.getDefault()), serverSide,
                        optionName("radius")))
                .binding(Config.FAR_RADIUS.getDefault(), Config.FAR_RADIUS::get, Config.FAR_RADIUS::set)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, 96).step(4)
                        .formatValue(ConfigScreen::farRadiusLabel))
                .available(!serverSide)
                .build();
        Option<Integer> blockUpdates = Option.<Integer>createBuilder()
                .name(Component.translatable(KEY + "block_updates"))
                .description(describeServer("block_updates", blockUpdatesLabel(Config.BLOCK_UPDATE_SECONDS.getDefault()), serverSide,
                        optionName("radius"), optionName("refresh")))
                .binding(Config.BLOCK_UPDATE_SECONDS.getDefault(),
                        () -> blockUpdatesShown(Config.BLOCK_UPDATE_SECONDS.get()),
                        v -> Config.BLOCK_UPDATE_SECONDS.set(v < MIN_BLOCK_UPDATE_SECONDS ? 0 : v))
                // Glissiere : OFF, puis 5 a 60 s (la premiere position apres OFF est 5 s, le minimum).
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(MIN_BLOCK_UPDATE_SECONDS - 1, 60).step(1)
                        .formatValue(v -> blockUpdatesLabel(v < MIN_BLOCK_UPDATE_SECONDS ? 0 : v)))
                .available(!serverSide)
                .build();
        Option<Integer> refresh = Option.<Integer>createBuilder()
                .name(Component.translatable(KEY + "refresh"))
                .description(describeServer("refresh", refreshLabel(toMinutes(Config.REFRESH_SECONDS.getDefault())), serverSide,
                        optionName("radius"), optionName("block_updates")))
                .binding(toMinutes(Config.REFRESH_SECONDS.getDefault()),
                        () -> toMinutes(Config.REFRESH_SECONDS.get()),
                        minutes -> Config.REFRESH_SECONDS.set(minutes * 60))
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, MAX_REFRESH_MINUTES).step(1)
                        .formatValue(ConfigScreen::refreshLabel))
                .available(!serverSide)
                .build();

        dirty = false;
        for (Option<?> option : List.of(master, lighting, radius, farRadius, biome, weather, sceneryAmbience, sceneryParticles, worldSounds,
                outsideLive, outsideMobs, outsideVehicles, mobSounds, miniatureMobs, clouds, fog, blockUpdates, refresh, ambience, ambientSounds, lunar, subLevels, caveFog, neighbors)) {
            autoSave(option);
        }

        YetAnotherConfigLib config = YetAnotherConfigLib.createBuilder()
                .title(Component.translatable(KEY + "title"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable(KEY + "section.general"))
                        .option(master)
                        .option(lighting)
                        // Le rayon de vue est decide par le serveur en multijoueur (grise, voir sa description).
                        .option(radius)
                        .option(farRadius)
                        .option(neighbors)
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable(KEY + "group.updates"))
                                .collapsed(false)
                                .option(blockUpdates)
                                .option(refresh)
                                // Certaines options ne s'appliquent completement qu'en ressortant du bocal. YACL montre
                                // les options hors groupe en premier : le message est donc a la fin du dernier groupe.
                                .option(LabelOption.create(Component.translatable(KEY + "reenter").withStyle(ChatFormatting.GRAY)))
                                .build())
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable(KEY + "section.display"))
                        // Groupes repliables, comme ceux de Better Clouds.
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable(KEY + "group.fog"))
                                .collapsed(false)
                                .option(fog)
                                .option(caveFog)
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable(KEY + "group.effects"))
                                .collapsed(false)
                                .option(biome)
                                .option(weather)
                                .option(clouds)
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable(KEY + "group.sounds"))
                                .collapsed(false)
                                .option(sceneryParticles)
                                .option(sceneryAmbience)
                                .option(worldSounds)
                                .build())
                        .build())
                // Entites : l'autorisation du monde en tete de son groupe, les options qui en dependent juste dessous.
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable(KEY + "section.entities"))
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable(KEY + "group.scenery_entities"))
                                .collapsed(false)
                                .option(outsideLive)
                                .option(outsideMobs)
                                .option(outsideVehicles)
                                .option(mobSounds)
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable(KEY + "group.miniature_entities"))
                                .collapsed(false)
                                .option(miniatureMobs)
                                .build())
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable(KEY + "section.integrations"))
                        // Par ordre alphabetique des mods.
                        .option(ambientSounds)
                        .option(ambience)
                        .option(lunar)
                        .option(subLevels)
                        .build())
                .save(ConfigScreen::saveToDisk)
                .build();
        current = config;
        return config.generateScreen(parent);
    }

    /** Le menu ouvert en dernier : ses options sont assez larges pour afficher les valeurs des curseurs (voir YaclOptionListMixin). */
    private static YetAnotherConfigLib current;

    /** Largeur maximale des options de notre menu. */
    public static final int ROW_WIDTH = 320;

    public static boolean isOurs(YetAnotherConfigLib config) {
        return config != null && config == current;
    }

    /** Vrai si une option a change depuis l'ouverture du menu. */
    private static boolean dirty;

    /** Sauvegarde l'option des qu'elle change : le menu n'a donc jamais de changements en attente. */
    private static <T> void autoSave(Option<T> option) {
        option.addListener((opt, value) -> {
            if (opt.changed() && opt.applyValue()) {
                saveToDisk();
                dirty = true;
            }
        });
    }

    /**
     * Applique et sauvegarde tout changement en attente (par exemple apres "Reinitialiser", qui change toutes
     * les options d'un coup). Une option qui ne peut pas prendre la valeur (grisee) oublie son changement :
     * le menu n'affiche ainsi jamais "Annuler" et "Sauvegarder".
     */
    public static void applyPending(YetAnotherConfigLib config) {
        if (!isOurs(config)) {
            return;
        }
        boolean[] saved = {false};
        dev.isxander.yacl3.api.utils.OptionUtils.forEachOptions(config, option -> {
            if (option.changed()) {
                if (option.applyValue()) {
                    saved[0] = true;
                }
                if (option.changed()) {
                    option.forgetPendingValue();
                }
            }
        });
        if (saved[0]) {
            saveToDisk();
            dirty = true;
        }
    }

    private static void saveToDisk() {
        Config.COMMON_SPEC.save();
        Config.CLIENT_SPEC.save();
        // Seulement avec un monde ouvert en solo : en multijoueur, c'est la copie recue du serveur (jamais modifiee ici).
        Minecraft mc = Minecraft.getInstance();
        if (Config.SERVER_SPEC.isLoaded() && mc.getSingleplayerServer() != null) {
            Config.SERVER_SPEC.save();
        }
    }

    /**
     * Options qui dependent de "Faire vivre le dehors" (et, pour les sons des mobs, de "Mobs du dehors") : nom barre et
     * une seule ligne qui dit quelle option est OFF, comme une integration sans son mod. Rien quand tout est ON.
     */
    private static void refreshDependents(boolean live, boolean mobs, MutableComponent worldSoundsNote,
                                          MutableComponent mobsNote, MutableComponent vehiclesNote,
                                          MutableComponent mobSoundsNote, MutableComponent mobsName,
                                          MutableComponent vehiclesName, MutableComponent mobSoundsName) {
        Component liveOff = live ? null : optionOff("outside_live");
        note(mobsNote, liveOff);
        note(vehiclesNote, liveOff);
        note(mobSoundsNote, liveOff != null ? liveOff : mobs ? null : optionOff("outside_mobs"));
        // Les sons du monde restent possibles : seulement ce qui tourne deja pour une autre raison s'entend.
        note(worldSoundsNote, live ? null
                : Component.translatable(KEY + "world_sounds.live_off", optionName("outside_live")).withStyle(ChatFormatting.YELLOW));
        strike(mobsName, !live);
        strike(vehiclesName, !live);
        strike(mobSoundsName, !live || !mobs);
    }

    /** "Faire vivre le dehors est OFF." */
    private static Component optionOff(String name) {
        return Component.translatable(KEY + "option_off", optionName(name)).withStyle(ChatFormatting.YELLOW);
    }

    /** Remplace le contenu d'une note de description (une ligne vide puis le texte), ou la vide. */
    private static void note(MutableComponent holder, Component text) {
        holder.getSiblings().clear();
        if (text != null) {
            holder.getSiblings().add(Component.literal("\n"));
            holder.getSiblings().add(text);
        }
    }

    private static void strike(MutableComponent name, boolean struck) {
        name.setStyle(name.getStyle().withStrikethrough(struck));
    }

    /** Valeur gardee mais sans effet (option dont elle depend OFF) : ON ou OFF en gris. */
    private static Component greyOnOff(boolean v) {
        return onOff(v).copy().withStyle(ChatFormatting.GRAY);
    }

    /** Disponibilite des options du joueur : l'interrupteur general, puis leurs propres conditions. */
    private static void refreshGated(List<Gated> gated, boolean enabled) {
        for (Gated g : gated) {
            g.option().setAvailable(enabled && g.possible().getAsBoolean());
        }
    }

    /** A la fermeture du menu : applique les changements de lumiere et de couleur, sans sortir du bocal. */
    public static void onClosed(YetAnotherConfigLib config) {
        if (isOurs(config) && dirty) {
            dirty = false;
            ClientView.rebuild();
        }
    }

    private record Gated(Option<?> option, BooleanSupplier possible) {
    }

    private static Option<Boolean> bool(String name, boolean defaultValue, Supplier<Boolean> getter,
                                        Consumer<Boolean> setter, Object... tooltipArgs) {
        return Option.<Boolean>createBuilder()
                .name(Component.translatable(KEY + name))
                .description(describe(name, onOff(defaultValue), tooltipArgs))
                .binding(defaultValue, getter, setter)
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();
    }

    /** Option d'integration : grisee, avec "(non installe)" dans son nom, si le mod n'est pas la. */
    private static Option<Boolean> integration(String name, String modName, boolean present, boolean defaultValue,
                                               Supplier<Boolean> getter, Consumer<Boolean> setter, Object... tooltipArgs) {
        MutableComponent label = Component.translatable(KEY + name);
        OptionDescription description = describe(name, onOff(defaultValue), tooltipArgs);
        if (!present) {
            // Mod absent : nom barre (en plus d'etre grise).
            label = label.withStyle(ChatFormatting.STRIKETHROUGH);
            description = OptionDescription.of(Component.translatable(KEY + name + ".tooltip", tooltipArgs), Component.empty(),
                    Component.translatable(KEY + "not_installed.tooltip", modName).withStyle(ChatFormatting.RED));
        }
        if (!present) {
            // Mod absent : affichee OFF (et l'integration reste OFF, voir Config), sans toucher au reglage enregistre.
            defaultValue = false;
            getter = () -> false;
            setter = value -> {
            };
        }
        boolean installed = present;
        return Option.<Boolean>createBuilder()
                .name(label)
                .description(description)
                .binding(defaultValue, getter, setter)
                // Mod absent : OFF en gris plutot qu'en rouge.
                .controller(opt -> BooleanControllerBuilder.create(opt).coloured(false)
                        .formatValue(v -> installed ? coloredOnOff(v) : greyOff()))
                .build();
    }

    /** Explication de l'option + sa valeur par defaut (panneau de droite). */
    private static OptionDescription describe(String name, Component defaultValue, Object... tooltipArgs) {
        return OptionDescription.of(Component.translatable(KEY + name + ".tooltip", tooltipArgs), Component.empty(),
                Component.translatable(KEY + "default", defaultValue).withStyle(ChatFormatting.GRAY));
    }

    private static OptionDescription describeServer(String name, Component defaultValue, boolean serverSide, Object... tooltipArgs) {
        if (!serverSide) {
            return describe(name, defaultValue, tooltipArgs);
        }
        return OptionDescription.of(Component.translatable(KEY + name + ".tooltip", tooltipArgs), Component.empty(),
                Component.translatable(KEY + "default", defaultValue).withStyle(ChatFormatting.GRAY), Component.empty(),
                Component.translatable(KEY + "server_controlled").withStyle(ChatFormatting.YELLOW));
    }

    /** Nom d'un autre reglage cite dans une description, traduit et mis en gras. */
    private static Component optionName(String name) {
        return Component.translatable(KEY + name).withStyle(ChatFormatting.BOLD);
    }

    private static Component fogLabel(Config.Fog value) {
        return Component.translatable(KEY + "fog." + value.name().toLowerCase(Locale.ROOT));
    }

    private static Component caveFogLabel(Config.CaveFog value) {
        return Component.translatable(KEY + "cave_fog." + value.name().toLowerCase(Locale.ROOT));
    }

    private static Component lightingLabel(Config.Lighting value) {
        return Component.translatable(KEY + "lighting." + value.name().toLowerCase(Locale.ROOT));
    }

    /** Mises a jour des blocs : 5 s minimum ; sous ce minimum (mais pas OFF), la valeur compte comme 5 s. */
    private static final int MIN_BLOCK_UPDATE_SECONDS = 5;

    /** Position de la glissiere pour une valeur de la config (la position juste sous 5 s affiche OFF). */
    private static int blockUpdatesShown(int seconds) {
        if (seconds <= 0) {
            return MIN_BLOCK_UPDATE_SECONDS - 1;
        }
        return Math.max(MIN_BLOCK_UPDATE_SECONDS, Math.min(60, seconds));
    }

    private static Component blockUpdatesLabel(Integer seconds) {
        return seconds == 0 ? Component.translatable(KEY + "block_updates.off") : Component.translatable(KEY + "block_updates.value", seconds);
    }

    private static Component neighborsLabel(Integer count) {
        return count == 0 ? Component.translatable(KEY + "neighbors.off") : Component.translatable(KEY + "neighbors.value", count);
    }

    private static Component farRadiusLabel(Integer blocks) {
        return blocks == 0 ? Component.translatable(KEY + "far_radius.off") : Component.translatable(KEY + "radius.value", blocks);
    }

    private static Component refreshLabel(Integer minutes) {
        return minutes == 0 ? Component.translatable(KEY + "refresh.off") : Component.translatable(KEY + "refresh.value", minutes);
    }

    /** Secondes de la config en minutes du curseur (le serveur ne descend jamais sous 1 minute). */
    private static int toMinutes(int seconds) {
        if (seconds <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(MAX_REFRESH_MINUTES, Math.round(seconds / 60.0F)));
    }

    /** Detail des sons du monde exterieur : OFF en gris quand le groupe est OFF (la valeur enregistree ne change pas). */
    /** ON en vert, OFF en rouge, comme les autres options. */
    private static Component coloredOnOff(boolean v) {
        return onOff(v).copy().withStyle(v ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    /** OFF en gris : option qui ne peut pas s'appliquer (mod absent, groupe OFF). Meme gris que le texte grise. */
    private static Component greyOff() {
        return CommonComponents.OPTION_OFF.copy().withStyle(ChatFormatting.GRAY);
    }

    private static Component onOff(boolean v) {
        return v ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
    }
}
