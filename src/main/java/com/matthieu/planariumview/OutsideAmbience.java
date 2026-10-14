package com.matthieu.planariumview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

/**
 * Cote client : l'ambiance a l'endroit du Planarium, utilisee par la compatibilite Dynamic Surroundings.
 */
public final class OutsideAmbience {
    private static ResourceLocation dim;
    private static ResourceKey<Biome> biome;
    private static boolean sheltered;
    private static boolean underground;
    private static ResourceLocation sourceDim;
    private static int y;
    private static int seaLevel;
    private static int height;

    private OutsideAmbience() {
    }

    public static void receive(AmbiencePayload payload) {
        dim = payload.pocketDim();
        biome = ResourceKey.create(Registries.BIOME, payload.biome());
        sheltered = payload.sheltered();
        underground = payload.underground();
        sourceDim = payload.sourceDim();
        y = payload.y();
        seaLevel = payload.seaLevel();
        height = payload.height();
    }

    public static void clear() {
        dim = null;
        biome = null;
    }

    /** Vrai si on est dans le bocal dont on connait l'exterieur (sans regarder les options). */
    public static boolean known() {
        ClientLevel level = Minecraft.getInstance().level;
        return dim != null && biome != null && level != null && level.dimension().location().equals(dim);
    }

    /** Le biome a cote du Planarium si on est dans son bocal (sans regarder les options), ou null. */
    public static Holder<Biome> knownBiomeHolder() {
        return known() ? knownBiome(Minecraft.getInstance().level) : null;
    }

    /** Vrai si on est dans le bocal dont on connait l'exterieur, et que l'option est activee. */
    public static boolean active() {
        ClientLevel level = Minecraft.getInstance().level;
        return dim != null && biome != null && level != null && Config.outsideAmbience()
                && level.dimension().location().equals(dim);
    }

    /** Le biome a cote du Planarium, ou null si l'ambiance exterieure n'est pas active. */
    public static Holder<Biome> biomeHolder() {
        if (!active()) {
            return null;
        }
        ClientLevel level = Minecraft.getInstance().level;
        return level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(biome).orElse(null);
    }

    /** Le biome a cote du Planarium pour ce bocal, meme si l'option d'ambiance est desactivee. */
    public static Holder<Biome> knownBiome(ClientLevel level) {
        if (dim == null || biome == null || level == null || !level.dimension().location().equals(dim)) {
            return null;
        }
        return level.registryAccess().registryOrThrow(Registries.BIOME).getHolder(biome).orElse(null);
    }

    public static boolean sheltered() {
        return sheltered;
    }

    /**
     * Dimension du Planarium, sa hauteur, et le niveau de la mer et la hauteur de cette dimension
     * (pour savoir si Dynamic Surroundings y jouerait les sons d'altitude : vent des nuages, espace).
     */
    public static ResourceLocation sourceDim() {
        return sourceDim;
    }

    public static int y() {
        return y;
    }

    public static int sourceSeaLevel() {
        return seaLevel;
    }

    public static int sourceHeight() {
        return height;
    }

    /** La meme position, a la hauteur du Planarium (pour la temperature, qui baisse en altitude). */
    public static net.minecraft.core.BlockPos atPlanariumHeight(net.minecraft.core.BlockPos pos) {
        return sourceDim == null ? pos : new net.minecraft.core.BlockPos(pos.getX(), y, pos.getZ());
    }

    /**
     * Temperature du biome a cette position (sans saison), comme Minecraft la calcule (Biome.getTemperature est privee) :
     * modificateur du biome (glace gelee...), puis plus froid au-dessus de Y 80 (sans le petit bruit aleatoire de Minecraft).
     */
    public static float biomeTemperature(Holder<Biome> biome, net.minecraft.core.BlockPos pos) {
        Biome value = biome.value();
        float temperature = value.getModifiedClimateSettings().temperatureModifier()
                .modifyTemperature(pos, value.getBaseTemperature());
        if (pos.getY() > 80) {
            temperature -= (pos.getY() - 80) * 0.05F / 40.0F;
        }
        return temperature;
    }

    public static boolean underground() {
        return underground;
    }
}
