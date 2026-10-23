package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.FarRingPayload;
import com.matthieu.planariumview.network.FlightPayload;
import com.matthieu.planariumview.network.FlightCoverage;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decor en vol (voir FlightServer) : des sections de 16 blocs jusqu'a la distance de vue, rangees par position dans le monde.
 * Une section construite reste telle quelle pendant le deplacement : elle est seulement replacee chaque image dans le
 * cadre du bocal qui bouge (comme le decor normal, voir SubLevelView.terrain). Seules les sections qui entrent dans le
 * rayon sont construites, avec un petit budget par image.
 *
 * Le decor normal reste affiche jusqu'a ce que le premier lot de sections soit pret, puis il est cache pendant le vol.
 * A la fin du vol, les sections restent jusqu'a ce que la nouvelle photo normale soit construite.
 */
public final class FlightView {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long BUDGET_NANOS = 2_500_000L;
    /** Bascule au plus tard apres ce temps, meme si le premier lot n'est pas complet ou construit. */
    private static final long SWITCH_TIMEOUT_NANOS = 3_000_000_000L;

    /** {@code box} : boite des vrais sommets (unites de bloc, autour de l'origine de la section), voir SceneryFrustum. */
    private record Section(BlockPos origin, Map<RenderType, VertexBuffer> buffers, float[] box) {
    }

    private record DrawFrame(Section section, Matrix4f original, FlightFog.Frame fog) {}

    private static ResourceLocation pocket;
    /** Le serveur est en vol (START recu, pas encore STOP). */
    private static boolean flying;
    private static boolean ready;
    private static long startedAt;
    /** Les sections remplacent le decor normal a l'ecran. */
    private static boolean drawing;
    private static final FlightCoverage coverage = new FlightCoverage();
    private static BlockPos viewCenter;
    private static int visibleRadius;
    private static int knownRadius;
    private static final Map<Long, Section> sections = new HashMap<>();
    private static final LinkedHashMap<Long, FarRingPayload.Data> pending = new LinkedHashMap<>();
    /** Matrice de chaque section pour l'image en cours (calculee une fois, utilisee par chaque couche). */
    private static final List<DrawFrame> frame = new ArrayList<>();
    /** Champ de la camera pour les sections du vol (voir prepare). */
    private static final SceneryFrustum frustum = new SceneryFrustum();

    private FlightView() {
    }

    public static void receive(FlightPayload payload) {
        var level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(payload.pocketDim())) {
            return;
        }
        if (pocket != null && !pocket.equals(payload.pocketDim())) {
            clear();
        }
        pocket = payload.pocketDim();
        switch (payload.kind()) {
            case FlightPayload.START -> {
                clearSections();
                drawing = false;
                flying = true;
                ready = false;
                startedAt = System.nanoTime();
            }
            case FlightPayload.SECTION -> pending.put(payload.section(), payload.data());
            case FlightPayload.VIEW -> {
                long[] view = payload.sections();
                if (view.length == 3 && view[0] > 0 && view[0] <= 96 && view[1] <= view[2]
                        && view[1] >= -2048 && view[2] <= 2047) {
                    coverage.view((int) view[0], (int) view[1], (int) view[2]);
                    viewCenter = BlockPos.of(payload.section());
                    knownRadius = Math.max(knownRadius, (int) view[0]);
                }
            }
            case FlightPayload.EMPTY -> {
                for (long key : payload.sections()) {
                    pending.remove(key);
                    Section removed = sections.remove(key);
                    if (removed != null) removed.buffers().values().forEach(VertexBuffer::close);
                    coverage.ready(key);
                }
            }
            case FlightPayload.DROP -> {
                for (long key : payload.sections()) {
                    coverage.drop(key);
                    pending.remove(key);
                    Section removed = sections.remove(key);
                    if (removed != null) removed.buffers().values().forEach(VertexBuffer::close);
                }
            }
            case FlightPayload.READY -> ready = true;
            case FlightPayload.STOP -> flying = false;
            default -> {
            }
        }
    }

    /** Vrai : le decor normal est cache, les sections du vol sont dessinees a sa place. */
    public static boolean active() {
        return drawing;
    }

    static int visibleRadius() { return Math.max(1, visibleRadius); }
    static int knownRadius() { return knownRadius; }

    /** Chaque image, avant le dessin : bascule entre le vol et le decor normal, construit des sections. */
    static void update(Vec3 camera) {
        if (!Config.enabled()) {
            return;
        }
        // Premier lot recu et construit, ou 3 s depuis le debut du vol meme si des sections attendent encore : a grande
        // vitesse, la file ne se vide jamais et l'ancien decor serait reste affiche tout le vol.
        if (flying && !drawing && ((ready && pending.isEmpty()) || System.nanoTime() - startedAt > SWITCH_TIMEOUT_NANOS)) {
            drawing = true;
        }
        if (!flying && (drawing || !sections.isEmpty()) && ClientView.nearCaughtUp()) {
            // La photo normale de l'arret est construite : elle reprend.
            drawing = false;
            clearSections();
        }
        if (pending.isEmpty()) {
            updateCoverage();
            return;
        }
        long start = System.nanoTime();
        do {
            var iterator = pending.entrySet().iterator();
            Map.Entry<Long, FarRingPayload.Data> next = iterator.next();
            iterator.remove();
            long key = next.getKey();
            try {
                Section built = build(key, next.getValue(), camera);
                Section old = built == null ? sections.remove(key) : sections.put(key, built);
                if (old != null) old.buffers().values().forEach(VertexBuffer::close);
                coverage.ready(key);
            } catch (RuntimeException e) {
                LOGGER.debug("[Planarium Panorama] Section du decor en vol ignoree", e);
            }
        } while (!pending.isEmpty() && System.nanoTime() - start < BUDGET_NANOS);
        updateCoverage();
    }

    private static void updateCoverage() {
        BlockPos center = SubLevelView.anchorPosition();
        if (center == null) center = viewCenter;
        visibleRadius = center == null || coverage.radius() == 0 ? ClientView.viewRadius() : coverage.edge(center);
    }

    private static Section build(long key, FarRingPayload.Data data, Vec3 camera) {
        BlockPos sectionMin = new BlockPos(SectionPos.x(key) << 4, SectionPos.y(key) << 4, SectionPos.z(key) << 4);
        // Positions locales : 0 = un bloc avant la section (voisins des bords).
        List<BlockPos> blocks = new ArrayList<>();
        // Older servers sent four biome columns without a halo; both layouts retain the existing packet format.
        int biomeShift = data.quarts() == 8 ? -7 : 1;
        FarRing.FarWorld world = FarRing.fill(new FarRing.FarWorld(sectionMin.offset(-1, -1, -1), 17, biomeShift), data, blocks::add);
        if (blocks.isEmpty()) {
            return null;
        }
        PoseStack placement = new PoseStack();
        SubLevelView.terrainBlocks(placement, sectionMin);
        Vector3f localCamera = new Matrix4f(placement.last().pose()).invertAffine()
                .transformPosition(new Vector3f((float) camera.x, (float) camera.y, (float) camera.z));
        float[] box = SceneryFrustum.emptyBox();
        Map<RenderType, VertexBuffer> buffers = FarRing.buildFlight(world, blocks,
                new Vec3(localCamera.x, localCamera.y, localCamera.z), box);
        return new Section(sectionMin, buffers, box);
    }

    /**
     * Matrices de l'image : chaque section dans le cadre actuel du bocal, seulement celles dans le champ de la camera.
     * Une fois par image pour toutes les couches. Les sections hors champ restent construites (prets quand on tourne),
     * et la couverture du brouillard compte toujours toutes les sections pretes.
     */
    static void prepare(PoseStack poseStack, Matrix4f projection) {
        frame.clear();
        // Sans cadre de reference, les sections (rangees par position dans le monde) ne peuvent pas etre placees.
        if (!drawing || !SubLevelView.hasAnchor()) {
            return;
        }
        BlockPos anchor = SubLevelView.anchorPosition();
        if (anchor == null) return;
        BlockPos origin = SectionPos.of(anchor).origin();
        poseStack.pushPose();
        try {
            SubLevelView.terrainBlocks(poseStack, origin);
            Matrix4f common = new Matrix4f(poseStack.last().pose());
            FlightFog.Frame fog = FlightFog.frame(common);
            // Test dans le repere commun (le placement du vol, origine proche) : la boite de chaque section est decalee
            // de la meme translation entiere que son dessin. Meme geometrie que le dessin, sans perte de precision.
            frustum.update(common, projection);
            for (Section section : sections.values()) {
                int x = section.origin().getX() - origin.getX();
                int y = section.origin().getY() - origin.getY();
                int z = section.origin().getZ() - origin.getZ();
                float[] box = section.box();
                if (box != null && !frustum.visible(new float[]{box[0] + x, box[1] + y, box[2] + z,
                        box[3] + x, box[4] + y, box[5] + z})) continue;
                frame.add(new DrawFrame(section, new Matrix4f(common).translate(x, y, z), fog.section(x, y, z)));
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Dessine une couche (l'etat de rendu de la couche est deja en place, voir ClientView.draw). */
    static void drawLayer(RenderType type, Matrix4f projection) {
        if (!drawing) {
            return;
        }
        var shader = RenderSystem.getShader();
        var offset = shader.CHUNK_OFFSET;
        float x = 0, y = 0, z = 0;
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        if (offset != null) {
            var previous = offset.getFloatBuffer();
            x = previous.get(0); y = previous.get(1); z = previous.get(2);
            // Position + ChunkOffset now uses source blocks, before the common giant scale in ModelViewMat.
            RenderSystem.setShaderFogStart(fogStart / ClientView.GIANT);
            RenderSystem.setShaderFogEnd(fogEnd / ClientView.GIANT);
        }
        try {
            for (DrawFrame entry : frame) {
                VertexBuffer buffer = entry.section().buffers().get(type);
                if (buffer == null || buffer.getFormat() == null) continue;
                buffer.bind();
                if (offset != null) {
                    offset.set(entry.fog().chunkOffset());
                    buffer.drawWithShader(entry.fog().modelView(), projection, shader);
                } else {
                    buffer.drawWithShader(entry.original(), projection, shader);
                }
            }
        } finally {
            if (offset != null) {
                offset.set(x, y, z);
                RenderSystem.setShaderFogStart(fogStart);
                RenderSystem.setShaderFogEnd(fogEnd);
            }
            VertexBuffer.unbind();
        }
    }

    private static void clearSections() {
        sections.values().forEach(section -> section.buffers().values().forEach(VertexBuffer::close));
        sections.clear();
        pending.clear();
        frame.clear();
        coverage.clear();
        viewCenter = null;
        visibleRadius = 0;
    }

    /** Sortie du bocal ou decor efface. */
    public static void clear() {
        clearSections();
        flying = false;
        ready = false;
        drawing = false;
        pocket = null;
        knownRadius = 0;
    }
}
