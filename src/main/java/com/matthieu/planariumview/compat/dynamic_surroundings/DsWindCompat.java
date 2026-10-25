package com.matthieu.planariumview.compat.dynamic_surroundings;

import com.mojang.logging.LogUtils;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Vent de Dynamic Surroundings 0.4.7 pour les particules du decor geant et des miniatures.
 *
 * DS marque les particules qu'il pousse (fumee, cendres, feuilles...) a leur creation, avec leur exposition au vent
 * la ou elles naissent : pour une particule du decor, c'etait la position dans le bocal (souvent a l'abri, donc sans
 * vent), et la poussee etait en blocs du bocal, 32 fois trop faible a l'echelle du decor. Ici, l'exposition est relue
 * dans le faux monde du decor, a la vraie position du bloc, et multipliee par l'echelle : la derive de DS est
 * proportionnelle a l'exposition, la particule derive donc comme dans le vrai monde. Sans DS 0.4.7 : rien.
 */
public final class DsWindCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean checked;
    private static boolean available;
    private static Object tagLibrary;
    private static Method tagIs;
    private static Object windBlownTag;
    private static Method exposure;
    private static Method blowInWind;
    private static Object particleTweaks;
    private static Field windBlowsParticles;
    /** Exposition au vent sous laquelle DS ne pousse rien (meme seuil que Shelter). */
    private static final int MIN_SKY_LIGHT = 8;
    private static final float SKY_LIGHT_RANGE = 7.0F;

    private DsWindCompat() {
    }

    private static boolean available() {
        if (!checked) {
            checked = true;
            try {
                Class<?> helpers = Class.forName("org.orecruncher.dsurround.mixinutils.MixinHelpers");
                tagLibrary = helpers.getField("TAG_LIBRARY").get(null);
                particleTweaks = helpers.getField("particleTweaksConfig").get(null);
                windBlowsParticles = particleTweaks.getClass().getField("windBlowsParticles");
                Class<?> library = Class.forName("org.orecruncher.dsurround.config.libraries.ITagLibrary");
                tagIs = library.getMethod("is", TagKey.class, ParticleType.class);
                windBlownTag = Class.forName("org.orecruncher.dsurround.tags.ParticleTags").getField("WIND_BLOWN").get(null);
                exposure = Class.forName("org.orecruncher.dsurround.runtime.weather.Shelter")
                        .getMethod("exposure", Level.class, BlockPos.class);
                blowInWind = Class.forName("org.orecruncher.dsurround.mixinutils.IWindBlown")
                        .getMethod("dsurround$blowInWind", float.class);
                available = true;
            } catch (ClassNotFoundException ignored) {
                // Dynamic Surroundings absent, ou plus ancien que 0.4.7 (pas de vent)
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.debug("[Planarium Panorama] Vent de Dynamic Surroundings indisponible pour le decor", e);
            }
        }
        return available;
    }

    /**
     * Une particule du decor vient d'etre creee. {@code world} : faux monde du decor ; {@code pos} : sa position dans
     * ce monde ; {@code scale} : taille du decor (32 pour le decor geant, 1/32 pour une miniature).
     */
    public static void blow(Particle particle, ParticleOptions options, Level world, double x, double y, double z,
                            double scale) {
        if (particle == null || options == null || world == null || !available()) return;
        try {
            if (!windBlowsParticles.getBoolean(particleTweaks)) return;
            if (!(boolean) tagIs.invoke(tagLibrary, windBlownTag, options.getType())) return;
            if (!blowInWind.getDeclaringClass().isInstance(particle)) return;
            BlockPos pos = BlockPos.containing(x, y, z);
            float open;
            try {
                open = (float) exposure.invoke(null, world, pos);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Le faux monde ne repond pas a tout ce que DS lit (hauteur des colonnes) : la lumiere du ciel seule.
                open = Mth.clamp((world.getBrightness(LightLayer.SKY, pos) - MIN_SKY_LIGHT) / SKY_LIGHT_RANGE, 0.0F, 1.0F);
            }
            // 0 aussi : une particule a l'abri dans le decor ne doit pas garder le vent du bocal.
            blowInWind.invoke(particle, (float) (open * scale));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            available = false;
            LOGGER.debug("[Planarium Panorama] Vent de Dynamic Surroundings desactive pour le decor", e);
        }
    }
}
