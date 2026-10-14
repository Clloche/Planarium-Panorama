package com.matthieu.planariumview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Cote client : etat du ciel synchronise avec le monde d'origine du Planarium.
 */
public final class SkySync {
    private static ResourceLocation dim;
    private static long syncedDayTime;
    private static long syncedGameTime;
    private static boolean daylightCycle;
    private static float rain;
    private static float thunder;
    private static int skyColor;
    private static int fogColor;
    /** Lumiere du ciel et des blocs au Planarium (0 a 15), -1 si inconnue. */
    private static int outsideSky = -1;
    private static int outsideBlock = -1;
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** Derniere valeur ecrite dans le log (pour ne pas le remplir). */
    private static final java.util.Map<String, String> lastLogged = new java.util.HashMap<>();
    /**
     * Copie de la lightmap normale de Minecraft, pour le decor geant (la vraie lumiere exterieure).
     * Dans le bocal, la lightmap de Minecraft elle-meme devient celle du bocal : tout le jeu l'utilise
     * (Sodium compris), et seul le decor geant passe sur cette copie.
     */
    private static final ResourceLocation GIANT_LIGHTMAP =
            ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "giant_lightmap");
    private static net.minecraft.client.renderer.texture.DynamicTexture giantTexture;
    private static boolean giantReady;
    /** Derniere lightmap du bocal ecrite (pour savoir si Minecraft l'a recalculee depuis). */
    private static final int[] lastWritten = new int[256];
    private static boolean hasWritten;
    private static boolean errorLogged;
    private static final java.util.Set<String> hooksSeen = new java.util.HashSet<>();
    /** Vrai si Minecraft recalcule la lightmap pendant l'appel en cours (lu au debut de l'appel). */
    private static boolean recomputing = true;

    public static void lightmapWillRecompute(boolean value) {
        recomputing = value;
    }

    /** Derniere fois que le point d'entree 'envoi' s'est execute (System.nanoTime). */
    private static long lastUploadHook = Long.MIN_VALUE / 2;
    /** Vrai pendant notre propre envoi de la lightmap (pour ne pas la traiter deux fois). */
    private static boolean selfUpload;

    /** Appele juste avant l'envoi d'une DynamicTexture a la carte graphique (voir DynamicTextureMixin). */
    public static void beforeTextureUpload(net.minecraft.client.renderer.texture.DynamicTexture texture) {
        if (selfUpload) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return;
        }
        net.minecraft.client.renderer.LightTexture lightTexture = mc.gameRenderer.lightTexture();
        if (lightTexture == null
                || ((com.matthieu.planariumview.mixin.LightTextureAccessor) lightTexture).planariumview$getTexture() != texture) {
            return;
        }
        afterLightmapUpdate(lightTexture, 0.0F, "envoi");
    }

    /** Derniere fois que le point d'entree 'lightmap' s'est execute (System.nanoTime). */
    private static long lastLightmapHook = Long.MIN_VALUE / 2;
    /** Texture d'origine de la lightmap pendant que le decor geant utilise la copie (null sinon). */
    private static ResourceLocation vanillaLocation;

    /** Effets de dimension utilises a la place de ceux du bocal : ciel normal, mais eclairage toujours plein. */
    public static final DimensionSpecialEffects EFFECTS = new SyncedEffects();

    private SkySync() {
    }

    public static void receive(SkyPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        dim = payload.pocketDim();
        syncedDayTime = payload.dayTime();
        syncedGameTime = level != null ? level.getGameTime() : 0L;
        daylightCycle = payload.daylightCycle();
        rain = payload.rain();
        thunder = payload.thunder();
        skyColor = payload.skyColor();
        fogColor = payload.fogColor();
        outsideSky = payload.outsideSky();
        outsideBlock = payload.outsideBlock();
        sourceTypeId = payload.dimensionType();
        hasSky = payload.hasSky();
        planariumName = payload.planariumName();
        if (!payload.effects().equals(sourceEffectsId)) {
            sourceEffectsId = payload.effects();
            sourceEffects = lookupEffects(sourceEffectsId);
        }
        logState("recu du serveur");
        if (level != null && inJar(level)) {
            net.minecraft.world.level.lighting.LayerLightEventListener sky =
                    level.getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.SKY);
            StringBuilder text = new StringBuilder("cote client : type de dimension ")
                    .append(level.dimensionTypeRegistration().unwrapKey().map(k -> k.location().toString()).orElse("?"))
                    .append(" ciel ").append(level.dimensionType().hasSkyLight())
                    .append(", moteur ").append(sky.getClass().getSimpleName())
                    .append(", ciel au centre (y 40, 31, 16, 1) :");
            for (int y : new int[]{40, 31, 16, 1}) {
                text.append(' ').append(level.getBrightness(net.minecraft.world.level.LightLayer.SKY,
                        new net.minecraft.core.BlockPos(16, y, 16)));
            }
            net.minecraft.world.level.chunk.DataLayer layer = sky.getDataLayerData(net.minecraft.core.SectionPos.of(1, 1, 1));
            text.append(", section du centre ").append(layer == null ? "absente" : layer.isEmpty() ? "vide" : "presente");
            String line = text.toString();
            if (!line.equals(lastLogged.get("client"))) {
                lastLogged.put("client", line);
                LOGGER.info("[Planarium Panorama] Diagnostic du bocal : {}", line);
            }
        }
        JarLight.rebuild(WallColors.faceLight(), outsideBlock, WallColors.faceDim());
        if (level != null && inJar(level)) {
            JarLight.updateReach(level);
            JarLight.tickRemesh(level);
        }
        SeasonColors.receive(payload.seasonTicks());
    }

    public static void clear() {
        dim = null;
        outsideSky = -1;
        outsideBlock = -1;
        SeasonColors.receive(-1);
        JarLight.clear();
    }

    /** Vrai si l'interieur du bocal doit suivre la lumiere qui arrive au Planarium. */
    private static boolean followsOutsideLight(ClientLevel level) {
        return outsideSky >= 0 && isActive(level) && Config.outsideLight();
    }

    /** Lumiere des blocs au Planarium (0 a 15), -1 si inconnue. */
    public static int outsideBlock() {
        return outsideBlock;
    }

    /** Vrai si les parois et le plancher dessines par Ars prennent la vraie lumiere du bocal (voir ProjectorLighting). */
    public static boolean projectorFollowsOutside() {
        ClientLevel level = Minecraft.getInstance().level;
        return inJar(level) && isActive(level) && Config.outsideLight();
    }

    /** Vrai dans un bocal d'Ars Nouveau. */
    private static boolean inJar(ClientLevel level) {
        return level != null && level.dimensionTypeRegistration().is(com.hollingsworth.arsnouveau.ArsNouveau.DIMENSION_TYPE_KEY);
    }

    /**
     * Lumiere des parois et du plancher du bocal (dessines par Ars a pleine luminosite).
     * Avec la vraie luminosite : lumiere du ciel pleine, que la lightmap du bocal ramene a la lumiere qui arrive au Planarium.
     */
    public static int projectorLight(int packedLight) {
        return followsOutsideLight(Minecraft.getInstance().level)
                ? net.minecraft.client.renderer.LightTexture.pack(Math.max(0, outsideBlock), 15)
                : packedLight;
    }

    /**
     * Appele juste apres que Minecraft a calcule sa lightmap (une fois par tick, avant le dessin).
     * On en tire la lightmap de l'interieur du bocal, sans recalculer celle de Minecraft :
     * - la lumiere du ciel du bocal devient celle qui arrive au Planarium (pleine dehors, faible dans une piece,
     *   nulle dans une caverne), en suivant l'heure comme d'habitude ;
     * - la lumiere des blocs autour du Planarium (torches) entre dans le bocal comme de la lumiere de bloc ;
     * - les lumieres placees dans le bocal restent telles quelles.
     * Sans la vraie luminosite, le bocal n'a pas de lumiere du ciel, comme dans Ars Nouveau.
     */
    public static void afterLightmapUpdate(net.minecraft.client.renderer.LightTexture lightTexture, float partialTick,
                                           String entryPoint) {
        if (!hooksSeen.contains(entryPoint)) {
            hooksSeen.add(entryPoint);
            LOGGER.info("[Planarium Panorama] Lightmap : point d'entree '{}' actif", entryPoint);
        }
        // Le point d'entree 'rendu' n'est qu'un secours, pour les packs ou l'autre ne s'execute pas.
        // Quand 'lightmap' fonctionne, un autre mod (Darkness...) peut modifier la lightmap entre les deux :
        // 'rendu' prendrait alors cette lightmap deja convertie pour le bocal comme copie du decor geant (clignotement noir).
        // Point d'entree principal : 'envoi', juste avant que la lightmap soit envoyee a la carte graphique.
        // Tous les mods qui recalculent la lightmap (Minecraft, Iris, Darkness...) passent par la, avec la version finale.
        // 'lightmap' (fin du calcul de Minecraft) et 'rendu' (avant le dessin) ne servent que si 'envoi' ne fonctionne pas.
        long now = System.nanoTime();
        boolean upload = entryPoint.equals("envoi");
        if (upload) {
            lastUploadHook = now;
        } else if (now - lastUploadHook < 2_000_000_000L) {
            return;
        } else if (entryPoint.equals("lightmap")) {
            lastLightmapHook = now;
        } else if (now - lastLightmapHook < 2_000_000_000L) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        com.matthieu.planariumview.mixin.LightTextureAccessor access =
                (com.matthieu.planariumview.mixin.LightTextureAccessor) lightTexture;
        ClientLevel level = mc.level;
        // Eclairage "Ciel et decor" : le decor geant a la lightmap normale, l'interieur reste tout eclaire (Ars).
        // Eclairage "Complet" : l'interieur du bocal prend en plus la lumiere de l'endroit du Planarium.
        // Eclairage OFF : rien ici, tout reste comme dans Ars.
        boolean follow = followsOutsideLight(level);
        if (!inJar(level) || !isActive(level) || !Config.realLighting()) {
            giantReady = false;
            hasWritten = false;
            if (inJar(level)) {
                logState("lightmap du bocal non appliquee (eclairage " + Config.LIGHTING.get()
                        + ", active " + Config.enabled() + ", bocal connu " + inKnownJar(level) + ")");
            }
            return;
        }
        try {
            com.mojang.blaze3d.platform.NativeImage vanilla = access.planariumview$getLightPixels();
            if (vanilla == null) {
                return;
            }
            // Si la lightmap contient encore exactement ce qu'on a ecrit, Minecraft ne l'a pas recalculee : rien a faire.
            if (hasWritten && giantReady && sameAsWritten(vanilla)) {
                return;
            }
            if (hasWritten && giantReady && entryPoint.equals("lightmap") && !recomputing) {
                // Minecraft n'a rien recalcule : c'est un autre mod (Darkness...) qui a retouche notre lightmap du bocal.
                // On la garde telle quelle, sans en faire la copie du decor geant (elle est deja convertie pour le bocal).
                for (int sky = 0; sky < 16; sky++) {
                    for (int block = 0; block < 16; block++) {
                        lastWritten[sky * 16 + block] = vanilla.getPixelRGBA(block, sky);
                    }
                }
                return;
            }
            if (giantTexture == null) {
                giantTexture = new net.minecraft.client.renderer.texture.DynamicTexture(16, 16, false);
                mc.getTextureManager().register(GIANT_LIGHTMAP, giantTexture);
            }
            // Copie de la lightmap normale, lue pendant qu'on reecrit celle de Minecraft.
            int[] normal = new int[256];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    normal[y * 16 + x] = vanilla.getPixelRGBA(x, y);
                }
            }
            // 1. Le decor geant : la lightmap normale (vraie luminosite), ou pleine.
            com.mojang.blaze3d.platform.NativeImage source = giantTexture.getPixels();
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    source.setPixelRGBA(x, y, normal[y * 16 + x]);
                }
            }
            giantTexture.upload();
            giantReady = true;
            // 2. La lightmap de Minecraft devient celle du bocal.
            net.minecraft.world.level.dimension.DimensionType type = level.dimensionType();
            float outside = follow ? net.minecraft.client.renderer.LightTexture.getBrightness(type, outsideSky) : 0.0F;
            for (int sky = 0; sky < 16; sky++) {
                // Niveau de ciel equivalent : aussi lumineux que "ce ciel" vu depuis l'endroit du Planarium.
                float skyIndex = inverseBrightness(type, net.minecraft.client.renderer.LightTexture.getBrightness(type, sky) * outside);
                for (int block = 0; block < 16; block++) {
                    // La lumiere des torches autour du Planarium est ajoutee bloc par bloc (voir JarLight), pas ici.
                    // Sans la lumiere du dehors ("Ciel et decor", ou avant de la recevoir) : interieur tout eclaire, comme Ars.
                    int color = follow ? sample(normal, block, skyIndex) : arsBright(type, block, sky);
                    vanilla.setPixelRGBA(block, sky, color);
                    lastWritten[sky * 16 + block] = color;
                }
            }
            hasWritten = true;
            if (!upload) {
                selfUpload = true;
                try {
                    access.planariumview$getTexture().upload();
                } finally {
                    selfUpload = false;
                }
            }
            logState("lightmap du bocal appliquee");
        } catch (Exception e) {
            giantReady = false;
            hasWritten = false;
            if (!errorLogged) {
                errorLogged = true;
                LOGGER.warn("[Planarium Panorama] Impossible de preparer la lightmap du bocal", e);
            }
        }
    }

    /**
     * Lightmap de l'interieur avec "Ciel et decor" : le ciel toujours en plein jour (l'interieur reste eclaire,
     * quelle que soit l'heure dehors) et la lumiere des blocs, eclaircie comme dans Ars Nouveau. Meme fin de calcul
     * que LightTexture (gamma), sans le plancher de luminosite de "forceBrightLightmap" qui eclairait les pieces fermees.
     */
    private static int arsBright(net.minecraft.world.level.dimension.DimensionType type, int block, int sky) {
        // Lumiere des blocs (teinte chaude des torches), et ciel en plein jour : l'interieur reste eclaire,
        // sauf la ou ni le ciel ni une lumiere n'arrivent (une piece fermee reste noire, comme avec OFF).
        float light = net.minecraft.client.renderer.LightTexture.getBrightness(type, block) * 1.5F;
        float day = net.minecraft.client.renderer.LightTexture.getBrightness(type, sky);
        float r = Mth.clamp(Math.max(light, day), 0.0F, 1.0F);
        float g = Mth.clamp(Math.max(light * ((light * 0.6F + 0.4F) * 0.6F + 0.4F), day), 0.0F, 1.0F);
        float b = Mth.clamp(Math.max(light * (light * light * 0.6F + 0.4F), day), 0.0F, 1.0F);
        float gamma = Math.max(0.0F, Minecraft.getInstance().options.gamma().get().floatValue());
        r = Mth.lerp(gamma, r, notGamma(r));
        g = Mth.lerp(gamma, g, notGamma(g));
        b = Mth.lerp(gamma, b, notGamma(b));
        r = Mth.clamp(Mth.lerp(0.04F, r, 0.75F), 0.0F, 1.0F);
        g = Mth.clamp(Mth.lerp(0.04F, g, 0.75F), 0.0F, 1.0F);
        b = Mth.clamp(Mth.lerp(0.04F, b, 0.75F), 0.0F, 1.0F);
        return 0xFF000000 | (int) (b * 255.0F) << 16 | (int) (g * 255.0F) << 8 | (int) (r * 255.0F);
    }

    private static float notGamma(float value) {
        float inverse = 1.0F - value;
        return 1.0F - inverse * inverse * inverse * inverse;
    }

    private static boolean sameAsWritten(com.mojang.blaze3d.platform.NativeImage image) {
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                if (image.getPixelRGBA(block, sky) != lastWritten[sky * 16 + block]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Diagnostic : la lumiere qui arrive au Planarium, ecrite dans le log quand elle change. */
    private static void logState(String step) {
        String line = "ciel " + outsideSky + ", blocs " + outsideBlock;
        if (!line.equals(lastLogged.get(step))) {
            lastLogged.put(step, line);
            LOGGER.info("[Planarium Panorama] Lumiere au Planarium, {} : {}", step, line);
        }
    }

    /** Niveau de lumiere (0 a 15, avec decimales) qui donne cette luminosite. Inverse de LightTexture.getBrightness. */
    private static float inverseBrightness(net.minecraft.world.level.dimension.DimensionType type, float brightness) {
        float ambient = type.ambientLight();
        float h = ambient >= 1.0F ? 1.0F : Mth.clamp((brightness - ambient) / (1.0F - ambient), 0.0F, 1.0F);
        float ratio = 4.0F * h / (1.0F + 3.0F * h);
        return Mth.clamp(ratio * 15.0F, 0.0F, 15.0F);
    }

    /** Couleur de la lightmap entre deux cases (interpolation sur la lumiere des blocs et du ciel). */
    private static int sample(int[] image, float blockIndex, float skyIndex) {
        int x0 = Mth.floor(blockIndex);
        int y0 = Mth.floor(skyIndex);
        int x1 = Math.min(x0 + 1, 15);
        int y1 = Math.min(y0 + 1, 15);
        float fx = blockIndex - x0;
        float fy = skyIndex - y0;
        int c00 = image[y0 * 16 + x0];
        int c10 = image[y0 * 16 + x1];
        int c01 = image[y1 * 16 + x0];
        int c11 = image[y1 * 16 + x1];
        int result = 0xFF000000;
        for (int shift = 0; shift < 24; shift += 8) {
            float top = Mth.lerp(fx, (c00 >> shift) & 255, (c10 >> shift) & 255);
            float bottom = Mth.lerp(fx, (c01 >> shift) & 255, (c11 >> shift) & 255);
            int value = Mth.clamp(Math.round(Mth.lerp(fy, top, bottom)), 0, 255);
            result |= value << shift;
        }
        return result;
    }

    /**
     * Avant et apres le dessin du decor geant (et de sa meteo) : il utilise la copie de la lightmap normale,
     * qui garde la vraie lumiere du ciel exterieur. On change seulement de texture, rien n'est recalcule.
     */
    public static void giantLight(boolean on, float partialTick) {
        com.matthieu.planariumview.mixin.LightTextureAccessor access =
                (com.matthieu.planariumview.mixin.LightTextureAccessor) Minecraft.getInstance().gameRenderer.lightTexture();
        if (on) {
            if (giantReady && vanillaLocation == null) {
                vanillaLocation = access.planariumview$getLocation();
                access.planariumview$setLocation(GIANT_LIGHTMAP);
            }
        } else if (vanillaLocation != null) {
            access.planariumview$setLocation(vanillaLocation);
            vanillaLocation = null;
        }
    }

    /** Vrai si on est dans le bocal dont on connait le monde d'origine (sans tenir compte des options). */
    public static boolean inKnownJar(ClientLevel level) {
        return dim != null && level != null && level.dimension().location().equals(dim);
    }

    /** Pluie du monde d'origine (0 a 1) si on est dans ce bocal, meme si le ciel synchronise est desactive. */
    public static float rainLevel(ClientLevel level) {
        return dim != null && level != null && level.dimension().location().equals(dim) ? rain : 0.0F;
    }

    private static int weatherScope;

    /** Debut du dessin du ciel, des nuages ou du brouillard : la pluie du dehors compte (voir LevelWeatherMixin). */
    public static void enterWeatherScope() {
        weatherScope++;
    }

    /** Apres le dessin du ciel : plus de pluie du dehors pour le reste du bocal (voir SkyWeatherScopeMixin). */
    public static void resetWeatherScope() {
        weatherScope = 0;
    }

    public static void exitWeatherScope() {
        weatherScope = Math.max(0, weatherScope - 1);
    }

    /** Vrai pendant le dessin du ciel du bocal, quand il montre le ciel du dehors. */
    public static boolean inWeatherScope(ClientLevel level) {
        return weatherScope > 0 && skyActive(level);
    }

    public static float thunderLevel(ClientLevel level) {
        return dim != null && level != null && level.dimension().location().equals(dim) ? thunder : 0.0F;
    }

    private static ResourceLocation sourceEffectsId;
    private static DimensionSpecialEffects sourceEffects;

    /**
     * Le ciel d'une dimension moddee (l'Aether par exemple) : on utilise ses propres effets dans le bocal.
     * Null pour le ciel normal (on garde alors EFFECTS) ou si on ne le trouve pas.
     */
    public static DimensionSpecialEffects sourceEffects() {
        return sourceEffects;
    }

    private static DimensionSpecialEffects lookupEffects(ResourceLocation id) {
        if (id == null || id.getNamespace().equals("minecraft")) {
            return null;
        }
        try {
            Object effects = Class.forName("net.neoforged.neoforge.client.DimensionSpecialEffectsManager")
                    .getMethod("getForType", ResourceLocation.class).invoke(null, id);
            if (effects instanceof DimensionSpecialEffects found
                    && !(found instanceof DimensionSpecialEffects.OverworldEffects)) {
                LOGGER.info("[Planarium Panorama] Ciel de la dimension d'origine dans le bocal : {}", id);
                return found;
            }
        } catch (Throwable e) {
            LOGGER.debug("[Planarium Panorama] Effets de dimension introuvables : {}", id, e);
        }
        return null;
    }

    /** Vrai pendant qu'on lit la vraie hauteur des nuages de la dimension d'origine (voir sourceCloudHeight). */
    private static boolean rawQuery;
    private static DimensionSpecialEffects anyEffects;
    private static ResourceLocation anyEffectsId;

    /** Vrai si le bocal affiche en ce moment le ciel d'une dimension moddee. */
    public static boolean usesSourceEffects(DimensionSpecialEffects effects) {
        return !rawQuery && effects != null && effects == sourceEffects && skyActive(Minecraft.getInstance().level);
    }

    /**
     * Hauteur des nuages de la dimension du Planarium (192 pour l'Overworld, plus bas dans l'Aether),
     * ou NaN si elle n'a pas de nuages ou qu'on ne la connait pas. Sert aux sons d'altitude de Dynamic Surroundings.
     */
    public static float sourceCloudHeight() {
        ResourceLocation id = sourceEffectsId;
        if (id == null) {
            return Float.NaN;
        }
        if (!id.equals(anyEffectsId)) {
            anyEffectsId = id;
            anyEffects = null;
            try {
                Object effects = Class.forName("net.neoforged.neoforge.client.DimensionSpecialEffectsManager")
                        .getMethod("getForType", ResourceLocation.class).invoke(null, id);
                if (effects instanceof DimensionSpecialEffects found) {
                    anyEffects = found;
                }
            } catch (Throwable e) {
                LOGGER.debug("[Planarium Panorama] Effets de dimension introuvables : {}", id, e);
            }
        }
        if (anyEffects == null) {
            return Float.NaN;
        }
        rawQuery = true;
        try {
            return anyEffects.getCloudHeight();
        } finally {
            rawQuery = false;
        }
    }

    /** Faux si la dimension du Planarium n'a pas de ciel (le Nether...) : pas de ciel ni d'heure dans le bocal. */
    private static boolean hasSky = true;

    /** Vrai si le bocal montre le ciel du dehors (soleil, heure, couleur du ciel). Sinon, seule la lumiere suit le dehors. */
    public static boolean skyActive(ClientLevel level) {
        return hasSky && isActive(level);
    }

    /** Effets du bocal quand la dimension du Planarium n'a pas de ciel : pas de ciel dessine, vraie lightmap. */
    public static final DimensionSpecialEffects NO_SKY_EFFECTS = new NoSkyEffects();

    /** Effets a utiliser dans le bocal (ciel synchronise, ciel d'une dimension moddee, ou pas de ciel). */
    public static DimensionSpecialEffects jarEffects() {
        if (!hasSky) {
            return NO_SKY_EFFECTS;
        }
        return sourceEffects != null ? sourceEffects : EFFECTS;
    }

    private static String planariumName = "";

    /**
     * Titre du bocal pour Traveler's Titles (au lieu de "???") : le nom donne au Planarium,
     * ou "Planarium" s'il n'en a pas (ou si on ne le connait pas encore).
     */
    public static net.minecraft.network.chat.Component jarTitle(ClientLevel level) {
        if (inKnownJar(level) && planariumName != null && !planariumName.isBlank()) {
            return net.minecraft.network.chat.Component.literal(planariumName);
        }
        return net.minecraft.network.chat.Component.translatable("block.ars_nouveau.planarium");
    }

    /**
     * Vrai si le Planarium est sous terre : plafond au-dessus, peu de lumiere du ciel, presque pas de colonnes a ciel ouvert
     * autour de lui, et presque aucune vue vers dehors depuis lui (une entree de caverne ou un surplomb garde son ciel).
     * Le bocal ne montre alors pas le ciel : le bord du decor est ferme par la couleur du brouillard, ou par
     * l'obscurite si l'option le demande (voir blackCave et ClientView.drawCaveDome).
     */
    public static boolean underground(ClientLevel level) {
        return skyActive(level) && outsideSky >= 0 && outsideSky < 8 && WeatherView.roofedAtPlanarium()
                && WeatherView.openSkyFraction() < 0.15F && ClientView.openSkyShare() < 0.01F;
    }

    /**
     * Obscurite noire (option Couleur dans les cavernes = sombre, biome sans brouillard special) : regle bien plus stricte que underground, pour ne jamais
     * noircir une maison ou une entree de caverne. Aucune vue vers dehors, meme par une fenetre.
     */
    /**
     * Couleur du brouillard du biome du Planarium. Lue cote client quand c'est possible : Dynamic Surroundings change
     * la couleur du brouillard de certains biomes (orange dans les badlands, par exemple), seulement cote client.
     */
    private static int biomeFogColor() {
        try {
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = OutsideAmbience.knownBiomeHolder();
            if (biome != null) {
                return biome.value().getFogColor() & 0xFFFFFF;
            }
        } catch (Exception ignored) {
            // Couleur envoyee par le serveur.
        }
        return fogColor;
    }

    /** Couleur de brouillard des biomes "normaux" (celle de presque tous les biomes vanilla et de worldgen). */
    private static final int NORMAL_FOG = 0xC0D8FF;

    /**
     * Vrai si le biome du Planarium a son propre brouillard, loin du bleu pale habituel (par exemple les cavernes
     * d'Alex's Caves) : la caverne prend alors ce brouillard, tel quel, au lieu du noir.
     */
    public static boolean customBiomeFog() {
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = OutsideAmbience.knownBiomeHolder();
        if (biome == null) {
            return false;
        }
        int fog = biome.value().getFogColor() & 0xFFFFFF;
        // Proche du bleu pale habituel (beaucoup de packs de worldgen le varient a peine) : brouillard normal.
        boolean custom = Math.abs(((fog >> 16) & 0xFF) - ((NORMAL_FOG >> 16) & 0xFF)) > 0x30
                || Math.abs(((fog >> 8) & 0xFF) - ((NORMAL_FOG >> 8) & 0xFF)) > 0x30
                || Math.abs((fog & 0xFF) - (NORMAL_FOG & 0xFF)) > 0x30;
        if (fog != lastLoggedFog) {
            lastLoggedFog = fog;
            LOGGER.info("[Planarium Panorama] Brouillard du biome {} : #{} ({})",
                    biome.unwrapKey().map(k -> k.location().toString()).orElse("?"),
                    String.format("%06X", fog), custom ? "special, garde au lieu du noir" : "normal");
        }
        return custom;
    }

    private static int lastLoggedFog = -1;

    public static boolean blackCave(ClientLevel level) {
        boolean result = Config.darkCaves() && !customBiomeFog() && underground(level) && outsideSky < 5
                && WeatherView.openSkyFraction() < 0.10F && ClientView.openSkyShare() <= 0.0F;
        String state = Config.darkCaves() + "/" + underground(level) + "/" + result;
        if (!state.equals(lastCaveState)) {
            lastCaveState = state;
            LOGGER.info("[Planarium Panorama] Caverne : option sombre {}, fermee {}, noire {} (ciel {}, colonnes ouvertes {} %, vue {} %)",
                    Config.darkCaves(), underground(level), result, outsideSky,
                    Math.round(WeatherView.openSkyFraction() * 100), Math.round(ClientView.openSkyShare() * 100));
        }
        return result;
    }

    private static String lastCaveState = "";

    public static boolean isActive(ClientLevel level) {
        return dim != null && level != null && Config.syncSky() && level.dimension().location().equals(dim);
    }

    public static long dayTime(ClientLevel level) {
        long time = syncedDayTime;
        if (daylightCycle) {
            time += level.getGameTime() - syncedGameTime;
        }
        return time;
    }

    /** Meme formule que DimensionType.timeOfDay pour l'Overworld. */
    public static float timeOfDay(ClientLevel level) {
        net.minecraft.world.level.dimension.DimensionType source = sourceType(level);
        if (source != null) {
            // Le type de dimension d'origine compte les jours a sa facon (l'Aether : des jours 3 fois plus longs).
            return source.timeOfDay(dayTime(level));
        }
        double d = Mth.frac(dayTime(level) / 24000.0 - 0.25);
        double e = 0.5 - Math.cos(d * Math.PI) / 2.0;
        return (float) (d * 2.0 + e) / 3.0F;
    }

    public static int moonPhase(ClientLevel level) {
        net.minecraft.world.level.dimension.DimensionType source = sourceType(level);
        if (source != null) {
            return source.moonPhase(dayTime(level));
        }
        return (int) (Math.floorMod(dayTime(level) / 24000L, 8L));
    }

    private static ResourceLocation sourceTypeId;

    /** Le type de dimension du Planarium, ou null si inconnu. */
    private static net.minecraft.world.level.dimension.DimensionType sourceType(ClientLevel level) {
        ResourceLocation id = sourceTypeId;
        if (id == null) {
            return null;
        }
        net.minecraft.world.level.dimension.DimensionType type = level.registryAccess()
                .registry(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                .map(registry -> registry.get(id)).orElse(null);
        // Une dimension a heure fixe garde aussi son heure fixe dans le bocal (DimensionType.timeOfDay s'en charge).
        if (type == null || type == level.dimensionType()) {
            return null;
        }
        return type;
    }

    /** Couleur du ciel, comme ClientLevel.getSkyColor mais avec le biome et la meteo du monde d'origine. */
    public static Vec3 skyColor(ClientLevel level) {
        float time = timeOfDay(level);
        float brightness = Mth.clamp(Mth.cos(time * ((float) Math.PI * 2F)) * 2.0F + 0.5F, 0.0F, 1.0F);
        float r = (skyColor >> 16 & 255) / 255.0F * brightness;
        float g = (skyColor >> 8 & 255) / 255.0F * brightness;
        float b = (skyColor & 255) / 255.0F * brightness;
        if (rain > 0.0F) {
            float grey = (r * 0.3F + g * 0.59F + b * 0.11F) * 0.6F;
            float keep = 1.0F - rain * 0.75F;
            r = r * keep + grey * (1.0F - keep);
            g = g * keep + grey * (1.0F - keep);
            b = b * keep + grey * (1.0F - keep);
        }
        if (thunder > 0.0F) {
            float grey = (r * 0.3F + g * 0.59F + b * 0.11F) * 0.2F;
            float keep = 1.0F - thunder * 0.75F;
            r = r * keep + grey * (1.0F - keep);
            g = g * keep + grey * (1.0F - keep);
            b = b * keep + grey * (1.0F - keep);
        }
        // Eclair : le ciel s'illumine un instant, comme dehors (voir LightningView).
        if (level.getSkyFlashTime() > 0) {
            float flash = 0.45F;
            r = r * (1.0F - flash) + 0.8F * flash;
            g = g * (1.0F - flash) + 0.8F * flash;
            b = b * (1.0F - flash) + flash;
        }
        return new Vec3(r, g, b);
    }

    private static final class NoSkyEffects extends DimensionSpecialEffects {
        NoSkyEffects() {
            super(Float.NaN, false, SkyType.NONE, false, false);
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
            return Vec3.fromRGB24(biomeFogColor());
        }

        @Override
        public boolean forceBrightLightmap() {
            return false;
        }

        @Override
        public boolean isFoggyAt(int x, int y) {
            return false;
        }
    }

    private static final class SyncedEffects extends DimensionSpecialEffects {
        SyncedEffects() {
            // Pas de nuages (ils seraient a la mauvaise echelle), ciel normal, eclairage du bocal conserve.
            super(Float.NaN, true, SkyType.NORMAL, true, false);
        }

        @Override
        public Vec3 getBrightnessDependentFogColor(Vec3 color, float brightness) {
            Vec3 fog = Vec3.fromRGB24(biomeFogColor());
            return fog.multiply(brightness * 0.94F + 0.06F, brightness * 0.94F + 0.06F, brightness * 0.91F + 0.09F);
        }

        @Override
        public boolean forceBrightLightmap() {
            // Avec la vraie luminosite, la lightmap suit la lumiere et l'heure ; sinon tout reste eclaire comme dans Ars.
            // Le ciel synchronise n'est utilise qu'avec l'eclairage "Ciel et decor" ou "Complet" : vraie lightmap.
            return false;
        }

        @Override
        public boolean isFoggyAt(int x, int y) {
            return false;
        }
    }
}
