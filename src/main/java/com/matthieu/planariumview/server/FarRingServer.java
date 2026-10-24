package com.matthieu.planariumview.server;

import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.FarRingPayload;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anneau lointain : au-dela du rayon de vue, une photo figee des blocs jusqu'a {@code farRadius}.
 *
 * Le rayon proche garde sa capture et ses mises a jour (ServerView). L'anneau est capture une fois en entrant, puis
 * seulement avec le rafraichissement de secours, ou quand un Planarium sur un sous-niveau (Sable) s'est arrete
 * quelques secondes. La capture se fait chunk par chunk avec un petit budget par tick : l'entree dans le bocal n'attend
 * pas l'anneau. Seuls les blocs visibles sont envoyes (ceux qui touchent une case eclairee qui laisse passer la vue),
 * avec la lumiere des cases autour : pas l'interieur du sol ni les cavernes noires.
 */
public final class FarRingServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Les tickets expirent seuls (30 s) si une capture est abandonnee sans les retirer. */
    /**
     * Cavernes sombres gardees dans l'anneau lointain quand le Planarium est a ciel ouvert : seulement a cette distance
     * verticale de lui (surplombs, entrees de grottes). Plus bas ou plus haut, le relief les cache en general, et elles
     * alourdiraient beaucoup la photo. Planarium sous un plafond (grotte) : toute la hauteur de l'anneau.
     */
    private static final int DARK_BAND = 24;
    private static final TicketType<UUID> TICKET = TicketType.create("planariumview_far", UUID::compareTo, 600);
    /** Un Planarium qui bouge doit rester immobile ce temps avant la capture de l'anneau. */
    private static final int STILL_TICKS = 100;
    /** Temps max d'attente des chunks d'une capture (ceux qui manquent encore sont laisses vides). */
    private static final int LOAD_WAIT_TICKS = 400;

    /** {@code round} : le client coupe son decor detaille en cylindre (meme version), sinon en cube (ancien client). */
    private record Key(ResourceKey<Level> source, BlockPos center, int near, int far, int height, boolean round) {
    }

    /** Photos deja prises, reutilisees par une nouvelle entree (ou un autre joueur) tant qu'elles sont recentes. */
    /**
     * {@code lightDue} : tick apres lequel la photo n'est plus reutilisee, parce qu'une source de lumiere qui semble
     * cachee sous terre a change (voir HiddenChanges). Long.MAX_VALUE = aucune.
     */
    private record Cached(FarRingPayload.Data data, long takenAt, LongOpenHashSet changed, long[] lightDue) {
        Cached(FarRingPayload.Data data, long takenAt) {
            this(data, takenAt, new LongOpenHashSet(), new long[]{Long.MAX_VALUE});
        }
    }

    /**
     * Une photo gardee est oubliee quand ce nombre de positions de blocs differentes a change dans sa zone (explosion,
     * construction, terraformage). En dessous (bambou qui pousse, feuilles, redstone), elle reste reutilisee : c'est une
     * photo figee, et une jungle change sans arret. Positions distinctes : une horloge de redstone compte une fois.
     */
    private static final int MAX_CHANGED_POSITIONS = 256;
    /** Duree max de reutilisation d'une photo quand le rafraichissement de secours est OFF (5 minutes). */
    private static final int MAX_AGE_WITHOUT_REFRESH = 20 * 60 * 5;

    /**
     * Age max d'une photo reutilisee a une nouvelle entree : le rafraichissement de secours, ou 5 minutes s'il est OFF.
     * Ne declenche jamais de nouvelle photo pendant qu'on reste dans le bocal (seul le rafraichissement le fait).
     */
    private static int maxCacheAge() {
        int refresh = refreshTicks();
        return refresh > 0 ? refresh : MAX_AGE_WITHOUT_REFRESH;
    }

    private static final Map<Key, Cached> CACHE = new HashMap<>();

    private static final class State {
        Key sent;
        ResourceKey<Level> pocket;
        Job job;
        BlockPos lastCenter;
        /** Position reelle du Planarium au dernier mouvement (un vaisseau a l'arret peut trembler d'un bloc). */
        BlockPos stillAt;
        long movedAt = Long.MIN_VALUE / 2;
        long nextRefresh;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private FarRingServer() {
    }

    private static int refreshTicks() {
        int seconds = Config.REFRESH_SECONDS.get();
        return seconds > 0 ? Math.max(seconds, 60) * 20 : 0;
    }

    /**
     * Chaque tick, pour un joueur suivi dans un bocal. {@code center} : centre de la derniere photo du rayon detaille ;
     * {@code live} : position actuelle du Planarium (elle bouge avec un vaisseau, la photo seulement tous les quelques
     * blocs) ; {@code near} : rayon de cette photo ; {@code ready} : le client a deja recu cette photo.
     */
    static void tick(ServerPlayer player, ServerLevel source, ResourceKey<Level> pocket, BlockPos center, BlockPos live,
                     int near, boolean ready) {
        UUID id = player.getUUID();
        State state = STATES.computeIfAbsent(id, ignored -> new State());
        long now = player.server.getTickCount();
        if (state.pocket != null && !state.pocket.equals(pocket)) {
            forget(player.server, id);
            state = STATES.computeIfAbsent(id, ignored -> new State());
        }
        state.pocket = pocket;
        if (!center.equals(state.lastCenter)) {
            if (state.lastCenter != null) {
                state.movedAt = now;
            }
            state.lastCenter = center;
            cancel(player.server, state);
        }
        // Un vaisseau qui derive lentement change de photo seulement tous les quelques blocs : l'arret se juge sur sa
        // position reelle, sinon l'anneau etait recapture (et reconstruit) a chaque photo.
        if (state.stillAt == null || state.stillAt.distSqr(live) > 2) {
            if (state.stillAt != null) {
                state.movedAt = now;
                cancel(player.server, state);
            }
            state.stillAt = live;
        }
        int far = Config.FAR_RADIUS.get();
        if (far <= near || !ready || !player.connection.hasChannel(FarRingPayload.TYPE)) {
            cancel(player.server, state);
            if (state.sent != null) {
                state.sent = null;
                PacketDistributor.sendToPlayer(player, FarRingPayload.clear(pocket.location()));
            }
            return;
        }
        // Un client qui connait les Planariums voisins coupe aussi son decor detaille en cylindre (voir DetailShape).
        boolean round = player.connection.hasChannel(com.matthieu.planariumview.network.NeighborPocketsPayload.TYPE);
        Key want = new Key(source.dimension(), center, near, far, Math.max(near, far), round);
        if (state.job != null) {
            if (!state.job.key.equals(want)) {
                cancel(player.server, state);
            } else {
                if (state.job.step(source, now)) {
                    Job done = state.job;
                    state.job = null;
                    FarRingPayload.Data data = done.result();
                    CACHE.put(want, new Cached(data, now));
                    send(player, pocket, want, data);
                    state.sent = want;
                    int refresh = refreshTicks();
                    state.nextRefresh = refresh > 0 ? now + refresh : Long.MAX_VALUE;
                    LOGGER.debug("[Planarium Panorama] Anneau lointain envoye : rayon {}, {} blocs visibles, {} ms de capture en {} ticks",
                            far, done.visible.blocks.size(), done.workNanos / 1_000_000L, now - done.startedAt);
                }
                return;
            }
        }
        if (now - state.movedAt < STILL_TICKS) {
            return;
        }
        boolean refreshDue = now >= state.nextRefresh;
        if (want.equals(state.sent) && !refreshDue) {
            return;
        }
        int refresh = refreshTicks();
        Cached cached = CACHE.get(want);
        boolean lightDue = cached != null && now >= cached.lightDue[0];
        boolean fresh = cached != null && now - cached.takenAt < maxCacheAge() && !lightDue;
        if (cached != null && !fresh) {
            LOGGER.debug("[Planarium Panorama] Anneau lointain : photo gardee {} ({} s), nouvelle capture",
                    lightDue ? "a relire (lumiere sous terre changee)" : "expiree", (now - cached.takenAt) / 20);
            CACHE.remove(want);
        }
        if (fresh && !(want.equals(state.sent) && refreshDue)) {
            // Photo recente de la meme zone (nouvelle entree dans le bocal, ou un autre joueur) : tout de suite.
            LOGGER.debug("[Planarium Panorama] Anneau lointain : photo gardee reutilisee ({} s, {} positions changees depuis)",
                    (now - cached.takenAt) / 20, cached.changed.size());
            send(player, pocket, want, cached.data);
            state.sent = want;
            state.nextRefresh = refresh > 0 ? cached.takenAt + refresh : Long.MAX_VALUE;
            return;
        }
        state.job = new Job(want, id, now);
        pruneCache(now);
    }

    private static void send(ServerPlayer player, ResourceKey<Level> pocket, Key key, FarRingPayload.Data data) {
        PacketDistributor.sendToPlayer(player,
                new FarRingPayload(pocket.location(), key.center, key.near, key.far, key.height, data));
    }

    private static void pruneCache(long now) {
        long keep = maxCacheAge();
        CACHE.values().removeIf(cached -> now - cached.takenAt > keep);
        // Au plus quelques photos en memoire (environ 1 Mo chacune).
        while (CACHE.size() > 16) {
            Iterator<Map.Entry<Key, Cached>> oldest = CACHE.entrySet().iterator();
            Map.Entry<Key, Cached> worst = oldest.next();
            while (oldest.hasNext()) {
                Map.Entry<Key, Cached> next = oldest.next();
                if (next.getValue().takenAt < worst.getValue().takenAt) worst = next;
            }
            CACHE.remove(worst.getKey());
        }
    }

    /**
     * Fermeture du monde : les photos et captures en cours sont oubliees. La cle du cache ne contient pas la sauvegarde :
     * en solo, un autre monde ouvert ensuite aurait pu recevoir l'anneau du precedent aux memes coordonnees.
     */
    static void clearAll() {
        CACHE.clear();
        STATES.clear();
    }

    /** Le joueur n'est plus suivi (sorti du bocal, deconnecte, vue OFF). */
    static void forget(net.minecraft.server.MinecraftServer server, UUID id) {
        State state = STATES.remove(id);
        if (state != null) {
            cancel(server, state);
        }
    }

    /**
     * Un bloc a change dans le monde : on note sa position dans les photos gardees qui le contiennent (hors du rayon
     * detaille, qui n'est pas dans la photo, sauf les sources lumineuses). Au-dela de {@link #MAX_CHANGED_POSITIONS}
     * positions differentes, la photo est oubliee. Le joueur deja dans le bocal garde son anneau fige.
     */
    static void blockChanged(ResourceKey<Level> source, BlockPos pos, boolean lightChanged, HiddenChanges.Check hidden,
                             long now) {
        if (CACHE.isEmpty()) {
            return;
        }
        var iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Cached> entry = iterator.next();
            Key key = entry.getKey();
            int dx = pos.getX() - key.center.getX(), dy = pos.getY() - key.center.getY(), dz = pos.getZ() - key.center.getZ();
            // Une source de lumiere eclaire jusqu'a 15 blocs, meme depuis le rayon detaille : la lumiere de la photo
            // n'est plus juste (feu de camp retire, lampe ajoutee). Oubliee tout de suite, sans attendre les 256 positions.
            // Source qui semble cachee sous terre (voir HiddenChanges) : la photo reste reutilisable jusqu'a son echeance,
            // au lieu d'etre oubliee a chaque bloc (un mineshaft en feu l'invalidait sans arret).
            if (lightChanged && key.source.equals(source) && Math.abs(dy) <= key.height + 16
                    && (long) dx * dx + (long) dz * dz <= (long) (key.far + 15) * (key.far + 15)) {
                if (hidden.slowFor(key.center)) {
                    long[] due = entry.getValue().lightDue();
                    due[0] = Math.min(due[0], now + HiddenChanges.DELAY_TICKS);
                } else {
                    LOGGER.debug("[Planarium Panorama] Anneau lointain : photo gardee invalidee (source de lumiere modifiee)");
                    iterator.remove();
                    continue;
                }
            }
            // Meme cylindre que la capture de l'anneau (voir Job.inBox) : les coins du carre ne comptent pas.
            if (!key.source.equals(source) || Math.abs(dy) > key.height + 1
                    || (long) dx * dx + (long) dz * dz > (long) key.far * key.far + key.far) continue;
            boolean inNear = Math.abs(dy) <= key.near && (key.round
                    ? com.matthieu.planariumview.DetailShape.horizontal(dx, dz, key.near)
                    : Math.abs(dx) <= key.near && Math.abs(dz) <= key.near);
            if (inNear) continue;
            Cached cached = entry.getValue();
            if (cached.changed.add(pos.asLong()) && cached.changed.size() > MAX_CHANGED_POSITIONS) {
                LOGGER.debug("[Planarium Panorama] Anneau lointain : photo gardee invalidee apres {} positions modifiees",
                        cached.changed.size());
                iterator.remove();
            }
        }
    }

    private static void cancel(net.minecraft.server.MinecraftServer server, State state) {
        if (state.job != null) {
            state.job.release(server);
            state.job = null;
        }
    }

    /** Capture d'un anneau, faite morceau par morceau. */
    private static final class Job {
        final Key key;
        final UUID owner;
        final long startedAt;
        final List<ChunkPos> chunks = new ArrayList<>();
        int index;
        boolean ticketsAdded;
        long workNanos;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final VisibleCapture visible;
        /** Planarium sous un plafond (grotte, surplomb) : ses parois sombres se voient sur toute la hauteur. */
        Boolean covered;

        Job(Key key, UUID owner, long now) {
            this.key = key;
            this.owner = owner;
            this.startedAt = now;
            BlockPos c = key.center;
            minX = c.getX() - key.far;
            maxX = c.getX() + key.far;
            minZ = c.getZ() - key.far;
            maxZ = c.getZ() + key.far;
            minY = c.getY() - key.height;
            maxY = c.getY() + key.height;
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    chunks.add(new ChunkPos(cx, cz));
                }
            }
            // Les chunks les plus proches d'abord.
            ChunkPos middle = new ChunkPos(c);
            chunks.sort(java.util.Comparator.comparingInt(chunk -> Math.max(Math.abs(chunk.x - middle.x), Math.abs(chunk.z - middle.z))));
            visible = new VisibleCapture(minX, minY, minZ, minX, minZ, ((maxX - minX) >> 2) + 1);
        }

        /** Vrai quand la capture est finie. */
        boolean step(ServerLevel level, long now) {
            if (!ticketsAdded) {
                for (ChunkPos chunk : chunks) {
                    level.getChunkSource().addRegionTicket(TICKET, chunk, 0, owner);
                }
                ticketsAdded = true;
                return false;
            }
            long start = System.nanoTime();
            try {
                while (index < chunks.size()) {
                    ChunkPos pos = chunks.get(index);
                    LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                    if (chunk == null) {
                        if (now - startedAt < LOAD_WAIT_TICKS) {
                            return false;
                        }
                        index++;
                        continue;
                    }
                    if (!CaptureBudget.available()) {
                        return false;
                    }
                    long chunkStart = System.nanoTime();
                    if (covered == null) {
                        covered = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                                key.center.getX(), key.center.getZ()) > key.center.getY() + 1;
                    }
                    final boolean underCeiling = covered;
                    visible.capture(level, chunk, minX, maxX, minY, maxY, minZ, maxZ, new VisibleCapture.Shape() {
                        @Override
                        public boolean contains(int x, int y, int z) {
                            return inBox(x, y, z);
                        }

                        @Override
                        public boolean skip(int x, int y, int z) {
                            return inNear(x, y, z);
                        }

                        @Override
                        public boolean darkVisible(int x, int y, int z) {
                            return underCeiling || Math.abs(y - key.center.getY()) <= DARK_BAND;
                        }
                    });
                    index++;
                    CaptureBudget.spend(System.nanoTime() - chunkStart);
                }
                return finish(level);
            } finally {
                workNanos += System.nanoTime() - start;
            }
        }

        private boolean finish(ServerLevel level) {
            release(level.getServer());
            return true;
        }

        void release(net.minecraft.server.MinecraftServer server) {
            if (!ticketsAdded) {
                return;
            }
            ServerLevel level = server.getLevel(key.source);
            if (level != null) {
                for (ChunkPos chunk : chunks) {
                    level.getChunkSource().removeRegionTicket(TICKET, chunk, 0, owner);
                }
            }
            ticketsAdded = false;
        }

        /** Cylindre autour du Planarium (comme le brouillard), pas un carre : les coins ne se verraient qu'a moitie. */
        private boolean inBox(int x, int y, int z) {
            if (y < minY || y > maxY) return false;
            long dx = x - key.center.getX(), dz = z - key.center.getZ();
            return dx * dx + dz * dz <= (long) key.far * key.far + key.far;
        }

        private boolean inNear(int x, int y, int z) {
            BlockPos c = key.center;
            if (Math.abs(y - c.getY()) > key.near) return false;
            // Meme cylindre que le decor detaille cote client (voir DetailShape) : les coins sont dans l'anneau. Un
            // ancien client dessine tout le cube : l'anneau saute alors le cube, sinon les coins seraient en double.
            return key.round ? com.matthieu.planariumview.DetailShape.horizontal(x - c.getX(), z - c.getZ(), key.near)
                    : Math.abs(x - c.getX()) <= key.near && Math.abs(z - c.getZ()) <= key.near;
        }

        FarRingPayload.Data result() {
            return visible.result();
        }
    }
}
