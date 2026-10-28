package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.client.sound.OutsideSoundClient;
import com.matthieu.planariumview.config.Config;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import static com.matthieu.planariumview.client.scenery.ClientView.ARS_OFFSET;
import static com.matthieu.planariumview.client.scenery.ClientView.GIANT;
import static com.matthieu.planariumview.client.scenery.ClientView.Y_SHIFT;
import static com.matthieu.planariumview.client.scenery.ClientView.liveArea;
import static com.matthieu.planariumview.client.scenery.ClientView.sceneryWorld;
import static com.matthieu.planariumview.client.scenery.ClientView.viewRadius;
import static com.matthieu.planariumview.client.scenery.SceneryState.currentDim;
import static com.matthieu.planariumview.client.scenery.SceneryState.currentRadius;
import static com.matthieu.planariumview.client.scenery.SceneryState.lastInfo;
import static com.matthieu.planariumview.client.scenery.SceneryState.renderData;

/** Relie les particules et les sons au repere, a la lumiere et au rayon visible du decor. */
final class SceneryHooks {
    private SceneryHooks() {
    }

    /**
     * Son d'ambiance d'un bloc du decor (positions du gabarit). On l'attenue comme si on l'ecoutait a la place du
     * Planarium, puis on le joue dans sa direction dans le decor geant.
     */
    /**
     * Particule d'ambiance d'un bloc du decor (positions du gabarit) : creee a sa place dans le decor geant, si elle est
     * dans le rayon de vue (au-dela, le decor n'est pas dessine). Elle prend la lumiere du decor a cet endroit.
     */
    static void onSceneryParticle(net.minecraft.core.particles.ParticleOptions options, double x, double y, double z,
                                  double vx, double vy, double vz) {
        if (options == null || !SceneryParticles.allowed()) {
            return;
        }
        int radius = currentRadius;
        int view = viewRadius();
        double dx = x - radius;
        double dy = y - radius;
        double dz = z - radius;
        if (!liveArea(dx - 0.5, dz - 0.5, view + 0.5)) {
            return;
        }
        int light = net.minecraft.client.renderer.LightTexture.pack(15, 15);
        if (renderData != null && renderData.fakeRenderingWorld instanceof LitRenderingWorld lit) {
            BlockPos at = BlockPos.containing(x, y, z);
            light = net.minecraft.client.renderer.LightTexture.pack(
                    lit.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at),
                    lit.getBrightness(net.minecraft.world.level.LightLayer.SKY, at));
        }
        LitRenderingWorld world = sceneryWorld();
        if (world != null) {
            int spawnLight = light;
            SceneryParticles.inScenery(world, radius, sceneryFrame(),
                    space -> SceneryParticles.spawn(space, options, x, y, z, vx, vy, vz, spawnLight));
        }
    }

    /** Repere des particules des blocs du decor quand le bocal est sur une structure Sable (voir sceneryFrame). */
    private static FramedParticle.Frame sceneryFrame;
    private static BlockPos sceneryFrameOrigin;
    private static int sceneryFrameRadius = -1;
    private static Object sceneryFrameLevel;
    private static long sceneryFrameEpoch;

    /**
     * Bocal sur une structure Sable : le decor suit son inclinaison et ses mouvements (SubLevelView.terrain). Les particules
     * des blocs (eau, feux de camp, pluie...) sont alors simulees dans le gabarit et dessinees dans ce repere, comme celles
     * des mobs. Null sur le terrain normal : elles restent a leur place, sans le cout du repere.
     */
    static FramedParticle.Frame sceneryFrame() {
        if (lastInfo == null || !SubLevelView.hasAnchor()) {
            return null;
        }
        BlockPos origin = lastInfo.origin();
        int radius = currentRadius;
        var level = Minecraft.getInstance().level;
        if (sceneryFrame == null || radius != sceneryFrameRadius || !origin.equals(sceneryFrameOrigin)
                || level != sceneryFrameLevel) {
            long epoch = sceneryFrameEpoch;
            sceneryFrame = new FramedParticle.Frame(cachedTerrain(origin, radius),
                    () -> sceneryFrameEpoch == epoch && Minecraft.getInstance().level == level
                            && currentDim != null && Config.sceneryParticles()
                            && !FlightView.active(), GIANT);
            sceneryFrameOrigin = origin;
            sceneryFrameRadius = radius;
            sceneryFrameLevel = level;
        }
        return sceneryFrame;
    }

    /** Ne pas retenir le monde client apres une sortie, et invalider les anciens reperes. */
    static void clearSceneryFrame() {
        sceneryFrameEpoch++;
        sceneryFrame = null;
        sceneryFrameOrigin = null;
        sceneryFrameRadius = -1;
        sceneryFrameLevel = null;
    }

    /**
     * Matrice du repere du decor, recalculee au plus une fois par milliseconde : toutes les particules d'une meme image
     * la partagent (Frame.matrix en fait une copie).
     */
    private static java.util.function.Supplier<org.joml.Matrix4d> cachedTerrain(BlockPos origin, int radius) {
        long[] computedAt = {0L};
        org.joml.Matrix4d[] cached = {null};
        return () -> {
            long now = System.nanoTime();
            if (cached[0] == null || now - computedAt[0] > 1_000_000L) {
                cached[0] = terrainMatrix(origin, radius);
                computedAt[0] = now;
            }
            return cached[0];
        };
    }

    /** Positions du gabarit -> decor geant dessine, avec le repere mobile d'un bocal sur une structure Sable. */
    private static org.joml.Matrix4d terrainMatrix(BlockPos origin, int radius) {
        PoseStack pose = new PoseStack();
        SubLevelView.terrain(pose, origin.offset(radius, radius, radius));
        pose.translate(-ARS_OFFSET * GIANT, -ARS_OFFSET * GIANT - Y_SHIFT, -ARS_OFFSET * GIANT);
        pose.scale(GIANT, GIANT, GIANT);
        pose.translate(-radius, -radius, -radius);
        return new org.joml.Matrix4d(pose.last().pose());
    }

    /** Death particles follow the same captured origin and moving frame as the rendered mob. */
    static void onMobDeath(double sourceX, double sourceY, double sourceZ, float width, float height) {
        LitRenderingWorld world = sceneryWorld();
        if (!Config.sceneryParticles() || lastInfo == null || world == null || FlightView.active()) return;
        BlockPos origin = lastInfo.origin();
        int radius = currentRadius;
        double x = sourceX - origin.getX(), y = sourceY - origin.getY(), z = sourceZ - origin.getZ();
        if (!liveArea(x - radius - 0.5, z - radius - 0.5, viewRadius() + 1.0)) return;
        SceneryParticles.mobDeath(world, mobFrame(origin, radius), x, y, z, width, height);
    }

    /** Potion particles start at the interpolated mob, in source-world coordinates, just like its death puff. */
    static void onMobParticle(net.minecraft.core.particles.ParticleOptions options,
                                     double sourceX, double sourceY, double sourceZ) {
        onMobParticle(options, sourceX, sourceY, sourceZ, 1, 1, 1);
    }

    static void onMobParticle(net.minecraft.core.particles.ParticleOptions options,
                                    double sourceX, double sourceY, double sourceZ, double vx, double vy, double vz) {
        LitRenderingWorld world = sceneryWorld();
        if (!Config.sceneryParticles() || lastInfo == null || world == null || FlightView.active()) return;
        BlockPos origin = lastInfo.origin();
        int radius = currentRadius;
        double x = sourceX - origin.getX(), y = sourceY - origin.getY(), z = sourceZ - origin.getZ();
        if (!liveArea(x - radius - 0.5, z - radius - 0.5, viewRadius() + 1.0)) return;
        SceneryParticles.mobParticle(world, mobFrame(origin, radius), options, x, y, z, vx, vy, vz);
    }

    private static FramedParticle.Frame mobFrame(BlockPos origin, int radius) {
        var level = Minecraft.getInstance().level;
        return new FramedParticle.Frame(() -> terrainMatrix(origin, radius), () -> Minecraft.getInstance().level == level && currentDim != null && Config.outsideMobs()
                && Config.sceneryParticles() && !FlightView.active(), GIANT);
    }

    /** Explosion du dehors (position par rapport au Planarium) : ses particules, si elle est dans le rayon de vue. */
    static void onOutsideExplosion(double dx, double dy, double dz) {
        int view = viewRadius();
        if (!Config.sceneryParticles() || !liveArea(dx - 0.5, dz - 0.5, view + 2.0)) {
            return;
        }
        LitRenderingWorld world = sceneryWorld();
        if (world != null) {
            int radius = currentRadius;
            SceneryParticles.inScenery(world, radius, sceneryFrame(),
                    space -> SceneryParticles.explosion(space, dx + radius, dy + radius, dz + radius));
        }
    }

    static void onSceneryAmbientSound(double x, double y, double z, net.minecraft.sounds.SoundEvent sound,
                                      net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        if (sound == null || volume <= 0.0F) {
            return;
        }
        int radius = currentRadius;
        double dx = x - radius;
        double dy = y - radius;
        double dz = z - radius;
        double distance = Math.sqrt((dx - 0.5) * (dx - 0.5) + (dy - 0.5) * (dy - 0.5) + (dz - 0.5) * (dz - 0.5));
        double range = sound.getRange(volume);
        if (!(range > 0.0) || distance >= range) {
            return;
        }
        OutsideSoundClient.play(sound, source, (float) (volume * (1.0 - distance / range)) * OutsideSoundClient.AMBIENCE_VOLUME,
                pitch, dx, dy, dz, true);
    }
}
