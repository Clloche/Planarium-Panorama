package com.matthieu.planariumview.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.client.scenery.SceneryParticles;
import com.matthieu.planariumview.config.Config;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Cercles de pluie de Dynamic Surroundings sur l'eau du decor geant.
 * Son handler lit un ClientLevel : on reprend ses reglages et sa particule, avec l'eau et la couleur du faux monde.
 */
public final class DsRipplesCompat {
    private static boolean checked;
    private static Method enabled;
    private static Method matchesFluid;
    private static Method create;
    private static Object settings;
    private static Object tags;
    private static Object rippleTag;
    private static Field style;
    private static Field growth;

    private DsRipplesCompat() {
    }

    private static boolean ready() {
        if (!checked) {
            checked = true;
            try {
                Class<?> handler = Class.forName("org.orecruncher.dsurround.effects.WaterRippleHandler");
                enabled = handler.getMethod("doRipples");
                settings = read(handler, "CONFIG");
                tags = read(handler, "TAG_LIBRARY");
                style = settings.getClass().getField("waterRippleStyle");
                Class<?> library = Class.forName("org.orecruncher.dsurround.config.libraries.ITagLibrary");
                matchesFluid = library.getMethod("is", TagKey.class, FluidState.class);
                rippleTag = Class.forName("org.orecruncher.dsurround.tags.FluidTags").getField("WATER_RIPPLES").get(null);
                Class<?> particle = Class.forName("org.orecruncher.dsurround.effects.particles.WaterRippleParticle");
                growth = particle.getDeclaredField("growthRate");
                growth.setAccessible(true);
                create = particle.getMethod("create", style.getType(), ClientLevel.class, double.class, double.class, double.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                disable(error);
            }
        }
        return create != null;
    }

    private static Object read(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void disable(Throwable error) {
        create = null;
        LogUtils.getLogger().debug("[Planarium Panorama] Cercles de pluie Dynamic Surroundings indisponibles", error);
    }

    /** Null : garder l'eclaboussure vanilla (style NONE, fluide non autorise, Particle Rain, mod absent...). */
    public static ParticleOptions ripple(LitRenderingWorld world, BlockPos pos, FluidState fluid) {
        if (!fluid.isSource() || !Config.outsideAmbience() || !ready()) return null;
        try {
            if (!(boolean) enabled.invoke(null) || !(boolean) matchesFluid.invoke(tags, rippleTag, fluid)) return null;
            Object selected = style.get(settings);
            int color = world.getBlockTint(pos, BiomeColors.WATER_COLOR_RESOLVER);
            return new SceneryParticles.Made(at -> make(selected, color, at));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            disable(error);
            return null;
        }
    }

    private static Particle make(Object selected, int color, Vec3 at) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || create == null) return null;
        Particle particle = null;
        try {
            particle = (Particle) create.invoke(null, selected, level, at.x, at.y, at.z);
            if (particle == null) return null;
            // Le constructeur decale Y sans bouger sa boite : la remettre sur la surface deja calculee.
            // Meme petit decalage au-dessus de l'eau que DS, a l'echelle du decor (1/9 + 0.1 - 0.2).
            particle.setPos(at.x, at.y + (1.0 / 9.0 - 0.1) * ClientView.GIANT, at.z);
            particle.setColor(((color >> 16) & 255) / 255.0F, ((color >> 8) & 255) / 255.0F, (color & 255) / 255.0F);
            // SceneryParticles met la taille initiale a l'echelle; DS ajoute ensuite growthRate a chaque tick.
            // Adapter ce champ une seule fois, avant que la particule entre dans le moteur.
            growth.setFloat(particle, growth.getFloat(particle) * ClientView.GIANT);
            return particle;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (particle != null) particle.remove();
            disable(error);
            return null;
        }
    }
}
