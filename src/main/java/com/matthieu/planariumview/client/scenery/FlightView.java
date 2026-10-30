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

    /**
     * Sections regroupees par blocs de 4 x 4 x 4 (64 blocs de cote), avec la boite de tous leurs sommets, en coordonnees
     * du monde. Le champ de vision teste d'abord la region : entierement dehors, ses sections sont sautees ; entierement
     * dedans, elles sont toutes gardees sans test. Seules les regions coupees par un bord sont testees section par
     * section. Avec une grande distance de vue, des milliers de tests par image en moins (le vol bouge sans arret, le
     * champ de vision change donc a chaque image).
     */
    private record Region(double[] box, List<Section> members) {}
    private static final List<Region> regions = new ArrayList<>();
    private static boolean regionsDirty = true;
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
                    regionsDirty = true;
                    coverage.ready(key);
                }
            }
            case FlightPayload.DROP -> {
                for (long key : payload.sections()) {
                    coverage.drop(key);
                    pending.remove(key);
                    Section removed = sections.remove(key);
                    if (removed != null) removed.buffers().values().forEach(VertexBuffer::close);
                    regionsDirty = true;
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
                regionsDirty = true;
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
            collectVisible(origin, common, fog);
        } finally {
            poseStack.popPose();
        }
    }

    private static void collectVisible(BlockPos origin, Matrix4f common, FlightFog.Frame fog) {
        if (regionsDirty) buildRegions();
        for (Region region : regions) {
            double[] r = region.box();
            int test = r == null ? SceneryFrustum.INTERSECT : frustum.classify(
                    (float) (r[0] - origin.getX()), (float) (r[1] - origin.getY()), (float) (r[2] - origin.getZ()),
                    (float) (r[3] - origin.getX()), (float) (r[4] - origin.getY()), (float) (r[5] - origin.getZ()));
            if (test == SceneryFrustum.OUTSIDE) continue;
            for (Section section : region.members()) {
                int x = section.origin().getX() - origin.getX();
                int y = section.origin().getY() - origin.getY();
                int z = section.origin().getZ() - origin.getZ();
                float[] box = section.box();
                if (test == SceneryFrustum.INTERSECT && SceneryFrustum.known(box) && !frustum.visible(box[0] + x, box[1] + y,
                        box[2] + z, box[3] + x, box[4] + y, box[5] + z)) continue;
                frame.add(new DrawFrame(section, new Matrix4f(common).translate(x, y, z), fog.section(x, y, z)));
            }
        }
    }

    private static void buildRegions() {
        regionsDirty = false;
        regions.clear();
        Map<Long, List<Section>> groups = new HashMap<>();
        for (Section section : sections.values()) {
            BlockPos at = section.origin();
            groups.computeIfAbsent(BlockPos.asLong(at.getX() >> 6, at.getY() >> 6, at.getZ() >> 6),
                    ignored -> new ArrayList<>()).add(section);
        }
        for (List<Section> members : groups.values()) {
            double[] box = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            boolean unknown = false;
            for (Section section : members) {
                float[] b = section.box();
                if (!SceneryFrustum.known(b)) {
                    unknown = true; // Boite inconnue : toujours testee section par section (et gardee).
                    continue;
                }
                BlockPos at = section.origin();
                box[0] = Math.min(box[0], (double) at.getX() + b[0]);
                box[1] = Math.min(box[1], (double) at.getY() + b[1]);
                box[2] = Math.min(box[2], (double) at.getZ() + b[2]);
                box[3] = Math.max(box[3], (double) at.getX() + b[3]);
                box[4] = Math.max(box[4], (double) at.getY() + b[4]);
                box[5] = Math.max(box[5], (double) at.getZ() + b[5]);
            }
            regions.add(new Region(unknown || box[0] > box[3] ? null : box, members));
        }
    }

    /** Dessine une couche (l'etat de rendu de la couche est deja en place, voir ClientView.draw). */
    static void drawLayer(RenderType type, Matrix4f projection) {
        if (!drawing) {
            return;
        }
        var shader = RenderSystem.getShader();
        if (shader == null) return;
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
        // Toutes les sections partagent la meme ModelViewMat ; seul ChunkOffset change. Le shader est donc prepare une
        // fois (uniformes, textures), puis chaque section met a jour son decalage et se dessine, comme Minecraft le fait
        // pour ses chunks et comme FarRing. drawWithShader refaisait toute la preparation a chaque section : avec une
        // grande distance de vue, des milliers de fois par image (70 % du rendu en vol, 96 chunks).
        boolean applied = false;
        Matrix4f appliedView = null;
        try {
            for (DrawFrame entry : frame) {
                VertexBuffer buffer = entry.section().buffers().get(type);
                if (buffer == null || buffer.getFormat() == null) continue;
                buffer.bind();
                if (offset == null || buffer instanceof ClippedVertexBuffer) {
                    // Sans ChunkOffset (shader d'un autre mod) ou decoupe contre le bocal : leur propre dessin complet,
                    // qui libere le shader a la fin.
                    if (applied) {
                        shader.clear();
                        applied = false;
                    }
                    if (offset != null) {
                        offset.set(entry.fog().chunkOffset());
                        buffer.drawWithShader(entry.fog().modelView(), projection, shader);
                    } else {
                        buffer.drawWithShader(entry.original(), projection, shader);
                    }
                    continue;
                }
                Matrix4f view = entry.fog().modelView();
                if (!applied || view != appliedView) {
                    if (applied) shader.clear();
                    shader.setDefaultUniforms(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, view, projection,
                            net.minecraft.client.Minecraft.getInstance().getWindow());
                    applied = true;
                    shader.apply();
                    appliedView = view;
                }
                offset.set(entry.fog().chunkOffset());
                offset.upload();
                buffer.draw();
            }
        } finally {
            try {
                if (applied) shader.clear();
            } finally {
                try {
                    if (offset != null) {
                        offset.set(x, y, z);
                        RenderSystem.setShaderFogStart(fogStart);
                        RenderSystem.setShaderFogEnd(fogEnd);
                    }
                } finally {
                    VertexBuffer.unbind();
                }
            }
        }
    }

    private static void clearSections() {
        sections.values().forEach(section -> section.buffers().values().forEach(VertexBuffer::close));
        sections.clear();
        regions.clear();
        regionsDirty = true;
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
