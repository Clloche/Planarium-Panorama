package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.config.Config;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

/** Environmental immersion through the glass; the player and the pocket remain physically dry. */
public final class OutsideFluidView {
    private static UnderwaterLoop loop;

    private OutsideFluidView() {}

    public static FluidState at(Vec3 position) {
        if (!Config.enabled() || !SkySync.inKnownJar(Minecraft.getInstance().level)) {
            return Fluids.EMPTY.defaultFluidState();
        }
        FluidState sample = SubLevelView.outsideFluid(position);
        return sample != null ? sample : ClientView.outsideFluid(position);
    }

    /**
     * Liquide du bocal entier, et non a la position donnee (gardee pour les appelants) : tout le bocal est soit
     * immerge, soit au sec. Sinon, avec une surface qui traverse le bocal, l'ambiance changeait en montant ou en
     * descendant.
     */
    public static FogType fogAt(Vec3 position) {
        return jarFluid();
    }

    /** Points testes le long de l'axe vertical du bocal (coordonnees de la poche : 32 blocs de large, centre a 16). */
    private static final int SAMPLES = 12;
    private static final double JAR_CENTER = 16.0;
    private static final double JAR_FLOOR = 1.0;
    private static final double JAR_CEILING = 31.0;
    /** Immerge au-dessus de 60 % du bocal sous le liquide, de nouveau au sec sous 40 % : pas de clignotement en surface. */
    private static final float ENTER = 0.6F;
    private static final float LEAVE = 0.4F;

    private static FogType jarState = FogType.NONE;
    private static long jarStateTick = Long.MIN_VALUE;

    /** Etat du bocal, recalcule au plus une fois par tick. */
    public static FogType jarFluid() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Config.enabled() || !SkySync.inKnownJar(mc.level)) {
            jarState = FogType.NONE;
            jarStateTick = Long.MIN_VALUE;
            return FogType.NONE;
        }
        long tick = mc.level.getGameTime();
        if (tick == jarStateTick) return jarState;
        jarStateTick = tick;
        int water = 0;
        int lava = 0;
        for (int i = 0; i < SAMPLES; i++) {
            double y = JAR_FLOOR + (JAR_CEILING - JAR_FLOOR) * (i + 0.5) / SAMPLES;
            FluidState fluid = at(new Vec3(JAR_CENTER, y, JAR_CENTER));
            if (fluid.is(FluidTags.WATER)) water++;
            else if (fluid.is(FluidTags.LAVA)) lava++;
        }
        float waterShare = water / (float) SAMPLES;
        float lavaShare = lava / (float) SAMPLES;
        if (jarState == FogType.WATER && waterShare >= LEAVE) return jarState;
        if (jarState == FogType.LAVA && lavaShare >= LEAVE) return jarState;
        jarState = lavaShare >= ENTER ? FogType.LAVA : waterShare >= ENTER ? FogType.WATER : FogType.NONE;
        return jarState;
    }

    /**
     * Vrai pendant que Minecraft calcule le champ de vision : le joueur est au sec dans le bocal, donc pas de zoom
     * sous-marin (il toucherait aussi l'interieur du bocal et pomperait quand la surface de l'eau traverse le bocal).
     */
    private static boolean fovQuery;

    public static void fovQuery(boolean active) {
        fovQuery = active;
    }

    public static FogType visualFog(Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        if (fovQuery || !Config.syncSky() || !SkySync.inKnownJar(mc.level)) return FogType.NONE;
        Vec3 position = camera.getPosition();
        BlockPos pos = BlockPos.containing(position);
        FluidState actual = mc.level.getFluidState(pos);
        if (!actual.isEmpty() && position.y < pos.getY() + actual.getHeight(mc.level, pos)) return FogType.NONE;
        return fogAt(position);
    }

    /** Fluid fog distances are in outside blocks, whereas the scenery is enlarged by GIANT. */
    public static boolean applyFog(Camera camera) {
        FogType fluid = visualFog(camera);
        if (fluid == FogType.NONE) return false;
        float end = (fluid == FogType.WATER ? 96 : 2) * ClientView.GIANT;
        end = Math.min(end, (ClientView.edgeRadius() + 0.5F) * ClientView.GIANT);
        RenderSystem.setShaderFogStart(fluid == FogType.WATER ? 0 : 0.25F * ClientView.GIANT);
        RenderSystem.setShaderFogEnd(end);
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
        return true;
    }

    public static boolean underwaterAmbience() {
        Minecraft mc = Minecraft.getInstance();
        return Config.sceneryAmbience() && mc.player != null
                && fogAt(mc.player.getEyePosition()) == FogType.WATER;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        if (loop != null && (loop.isStopped() || !mc.getSoundManager().isActive(loop))) loop = null;
        if (mc.player != null && !mc.player.isUnderWater() && underwaterAmbience() && loop == null) {
            loop = new UnderwaterLoop();
            mc.getSoundManager().play(loop);
        }
    }

    private static final class UnderwaterLoop extends AbstractTickableSoundInstance {
        private final net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        private int fade;

        UnderwaterLoop() {
            super(SoundEvents.AMBIENT_UNDERWATER_LOOP, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            looping = true;
            relative = true;
            volume = 0;
        }

        @Override public void tick() {
            if (player != Minecraft.getInstance().player || player.isRemoved() || player.isUnderWater()) {
                stop();
                return;
            }
            fade = underwaterAmbience() ? Math.min(40, fade + 1) : fade - 2;
            volume = Math.max(0, fade / 40.0F);
            if (fade < 0) stop();
        }

        @Override public boolean canStartSilent() { return true; }
    }
}
