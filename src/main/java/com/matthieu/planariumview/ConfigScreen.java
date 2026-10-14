package com.matthieu.planariumview;

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
 * Menu de configuration, construit avec YACL (Yet Another Config Lib) : onglets (General avec l'interrupteur,
 * l'eclairage et le rayon de vue ; Affichage avec le rafraichissement ; Integrations), description de l'option a droite, recherche, remise a zero par option et globale.
 *
 * L'interrupteur general grise les autres options du joueur (leurs valeurs sont gardees).
 * Les options d'integration sont grisees si le mod n'est pas installe, celles du serveur en multijoueur.
 *
 * Chaque changement est sauvegarde tout de suite (pas de bouton "Sauvegarder", seulement "Termine").
 * Le decor est reconstruit une seule fois, en fermant le menu (Termine ou Echap), voir YaclEscapeMixin.
 */
public final class ConfigScreen {
    private static final String KEY = "planariumview.config.";
    private static final int MAX_REFRESH_SECONDS = 60;

    private ConfigScreen() {
    }

    public static Screen create(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        // Sur un serveur multijoueur, le rayon et le rafraichissement sont decides par le serveur.
        boolean serverSide = mc.level != null && mc.getSingleplayerServer() == null;
        boolean clPresent = Config.CONTRAPTION_LIGHTS.present();
        boolean dsPresent = Config.DYNAMIC_SURROUNDINGS.present();
        boolean asPresent = Config.AMBIENT_SOUNDS_MOD.present();
        boolean ecPresent = Config.ENHANCED_CELESTIALS.present();

        // Options du joueur grisees quand l'interrupteur general est OFF (si elles sont possibles par ailleurs).
        List<Gated> gated = new ArrayList<>();

        Option<Boolean> master = Option.<Boolean>createBuilder()
                .name(Component.translatable(KEY + "enabled"))
                .description(describe("enabled", onOff(Config.ENABLED.getDefault())))
                .binding(Config.ENABLED.getDefault(), Config.ENABLED::get, Config.ENABLED::set)
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .listener((opt, value) -> {
                    for (Gated g : gated) {
                        g.option().setAvailable(value && g.possible().getAsBoolean());
                    }
                })
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
        boolean betterClouds = BetterCloudsCompat.active();
        Option<Boolean> clouds = Option.<Boolean>createBuilder()
                .name(Component.translatable(KEY + "clouds"))
                .description(betterClouds
                        ? OptionDescription.of(Component.translatable(KEY + "clouds.tooltip"), Component.empty(),
                        Component.translatable(KEY + "clouds.better_clouds").withStyle(ChatFormatting.RED))
                        : describe("clouds", onOff(Config.CLOUDS.getDefault())))
                .binding(Config.CLOUDS.getDefault(), () -> !betterClouds && Config.CLOUDS.get(), Config.CLOUDS::set)
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();
        Option<Config.Fog> fog = Option.<Config.Fog>createBuilder()
                .name(Component.translatable(KEY + "fog"))
                .description(describe("fog", fogLabel(Config.FOG.getDefault())))
                .binding(Config.FOG.getDefault(), Config.FOG::get, Config.FOG::set)
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(Config.Fog.class)
                        .formatValue(ConfigScreen::fogLabel))
                .build();
        Option<Config.CaveFog> caveFog = Option.<Config.CaveFog>createBuilder()
                .name(Component.translatable(KEY + "cave_fog"))
                .description(describe("cave_fog", caveFogLabel(Config.CAVE_FOG.getDefault())))
                .binding(Config.CAVE_FOG.getDefault(), Config.CAVE_FOG::get, Config.CAVE_FOG::set)
                .controller(opt -> EnumControllerBuilder.create(opt).enumClass(Config.CaveFog.class)
                        .formatValue(ConfigScreen::caveFogLabel))
                .build();

        Option<Boolean> color = integration("color", "Contraption Lights", clPresent,
                Config.COLORED_LIGHT.getDefault(), Config.COLORED_LIGHT::get, Config.COLORED_LIGHT::set);
        Option<Boolean> ambience = integration("ambience", "Dynamic Surroundings", dsPresent,
                Config.OUTSIDE_AMBIENCE.getDefault(), Config.OUTSIDE_AMBIENCE::get, Config.OUTSIDE_AMBIENCE::set);
        Option<Boolean> ambientSounds = integration("ambient_sounds", "AmbientSounds", asPresent,
                Config.AMBIENT_SOUNDS.getDefault(), Config.AMBIENT_SOUNDS::get, Config.AMBIENT_SOUNDS::set);
        Option<Boolean> lunar = integration("lunar_events", "Enhanced Celestials 2", ecPresent,
                Config.LUNAR_EVENTS.getDefault(), Config.LUNAR_EVENTS::get, Config.LUNAR_EVENTS::set);

        gated.add(new Gated(lighting, () -> true));
        gated.add(new Gated(biome, () -> true));
        gated.add(new Gated(weather, () -> true));
        gated.add(new Gated(clouds, () -> !betterClouds));
        gated.add(new Gated(fog, () -> true));
        gated.add(new Gated(caveFog, () -> true));
        gated.add(new Gated(color, () -> clPresent));
        gated.add(new Gated(ambience, () -> dsPresent));
        gated.add(new Gated(ambientSounds, () -> asPresent));
        gated.add(new Gated(lunar, () -> ecPresent));
        boolean enabled = Config.ENABLED.get();
        for (Gated g : gated) {
            g.option().setAvailable(enabled && g.possible().getAsBoolean());
        }

        Option<Integer> radius = Option.<Integer>createBuilder()
                .name(Component.translatable(KEY + "radius"))
                .description(describeServer("radius",
                        Component.translatable(KEY + "radius.value", Config.RADIUS.getDefault()), serverSide))
                .binding(Config.RADIUS.getDefault(), Config.RADIUS::get, Config.RADIUS::set)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(1, 32).step(1)
                        .formatValue(v -> Component.translatable(KEY + "radius.value", v)))
                .available(!serverSide)
                .build();
        Option<Integer> refresh = Option.<Integer>createBuilder()
                .name(Component.translatable(KEY + "refresh"))
                .description(describeServer("refresh", refreshLabel(Config.REFRESH_SECONDS.getDefault()), serverSide))
                .binding(Config.REFRESH_SECONDS.getDefault(),
                        () -> Math.max(0, Math.min(MAX_REFRESH_SECONDS, Config.REFRESH_SECONDS.get())),
                        Config.REFRESH_SECONDS::set)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, MAX_REFRESH_SECONDS).step(1)
                        .formatValue(ConfigScreen::refreshLabel))
                .available(!serverSide)
                .build();

        dirty = false;
        for (Option<?> option : List.of(master, lighting, radius, biome, weather, clouds, fog, refresh, color, ambience, ambientSounds, lunar, caveFog)) {
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
                        // Certaines options ne s'appliquent completement qu'en ressortant du bocal.
                        .option(LabelOption.create(Component.translatable(KEY + "reenter").withStyle(ChatFormatting.GRAY)))
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable(KEY + "section.display"))
                        .option(biome)
                        .option(weather)
                        .option(clouds)
                        .option(refresh)
                        // Groupe repliable, comme ceux de Better Clouds.
                        .group(OptionGroup.createBuilder()
                                .name(Component.translatable(KEY + "group.fog"))
                                .collapsed(false)
                                .option(fog)
                                .option(caveFog)
                                .build())
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable(KEY + "section.integrations"))
                        .option(color)
                        .option(ambience)
                        .option(ambientSounds)
                        .option(lunar)
                        .build())
                .save(ConfigScreen::saveToDisk)
                .build();
        current = config;
        return config.generateScreen(parent);
    }

    /** Le menu ouvert en dernier : ses options sont moins larges (voir YaclOptionListMixin). */
    private static YetAnotherConfigLib current;

    /** Largeur maximale des options de notre menu. */
    public static final int ROW_WIDTH = 280;

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
                                        Consumer<Boolean> setter) {
        return Option.<Boolean>createBuilder()
                .name(Component.translatable(KEY + name))
                .description(describe(name, onOff(defaultValue)))
                .binding(defaultValue, getter, setter)
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();
    }

    /** Option d'integration : grisee, avec "(non installe)" dans son nom, si le mod n'est pas la. */
    private static Option<Boolean> integration(String name, String modName, boolean present, boolean defaultValue,
                                               Supplier<Boolean> getter, Consumer<Boolean> setter) {
        MutableComponent label = Component.translatable(KEY + name);
        OptionDescription description = describe(name, onOff(defaultValue));
        if (!present) {
            // Mod absent : nom barre (en plus d'etre grise).
            label = label.withStyle(ChatFormatting.STRIKETHROUGH);
            description = OptionDescription.of(Component.translatable(KEY + name + ".tooltip"), Component.empty(),
                    Component.translatable(KEY + "not_installed.tooltip", modName).withStyle(ChatFormatting.RED));
        }
        if (!present) {
            // Mod absent : affichee OFF (et l'integration reste OFF, voir Config), sans toucher au reglage enregistre.
            defaultValue = false;
            getter = () -> false;
            setter = value -> {
            };
        }
        return Option.<Boolean>createBuilder()
                .name(label)
                .description(description)
                .binding(defaultValue, getter, setter)
                .controller(opt -> BooleanControllerBuilder.create(opt).onOffFormatter().coloured(true))
                .build();
    }

    /** Explication de l'option + sa valeur par defaut (panneau de droite). */
    private static OptionDescription describe(String name, Component defaultValue) {
        return OptionDescription.of(Component.translatable(KEY + name + ".tooltip"), Component.empty(),
                Component.translatable(KEY + "default", defaultValue).withStyle(ChatFormatting.GRAY));
    }

    private static OptionDescription describeServer(String name, Component defaultValue, boolean serverSide) {
        if (!serverSide) {
            return describe(name, defaultValue);
        }
        return OptionDescription.of(Component.translatable(KEY + name + ".tooltip"), Component.empty(),
                Component.translatable(KEY + "default", defaultValue).withStyle(ChatFormatting.GRAY), Component.empty(),
                Component.translatable(KEY + "server_controlled").withStyle(ChatFormatting.YELLOW));
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

    private static Component refreshLabel(Integer seconds) {
        return seconds == 0 ? Component.translatable(KEY + "refresh.off") : Component.translatable(KEY + "refresh.value", seconds);
    }

    private static Component onOff(boolean v) {
        return v ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
    }
}
