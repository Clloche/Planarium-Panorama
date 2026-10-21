package com.matthieu.planariumview.network;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** Sections ready on the client, including empty ones. The fog must stay inside their continuous footprint. */
public final class FlightCoverage {
    private final LongOpenHashSet ready = new LongOpenHashSet();
    private int radius, minSection, maxSection;
    private BlockPos cachedCenter;
    private int cachedEdge;

    public void view(int radius, int minSection, int maxSection) {
        this.radius = Math.max(0, Math.min(96, radius));
        this.minSection = minSection;
        this.maxSection = maxSection;
        cachedCenter = null;
    }

    public int radius() { return radius; }

    public void ready(long section) {
        if (ready.add(section)) cachedCenter = null;
    }

    public void drop(long section) {
        if (ready.remove(section)) cachedCenter = null;
    }

    public void clear() {
        ready.clear();
        radius = 0;
        cachedCenter = null;
    }

    /** Two blocks cover the fractional anchor position and the camera's position inside the jar. */
    public int edge(BlockPos center) {
        if (center.equals(cachedCenter)) return cachedEdge;
        int search = radius + 2;
        double safeSquared = (double) search * search;
        int lowY = Math.max(minSection, (center.getY() - radius) >> 4);
        int highY = Math.min(maxSection, (center.getY() + radius) >> 4);
        for (int x = (center.getX() - search) >> 4; x <= (center.getX() + search) >> 4; x++) {
            double dx = Math.max(0, Math.max((x << 4) - center.getX(), center.getX() - ((x << 4) + 16)));
            for (int z = (center.getZ() - search) >> 4; z <= (center.getZ() + search) >> 4; z++) {
                double dz = Math.max(0, Math.max((z << 4) - center.getZ(), center.getZ() - ((z << 4) + 16)));
                double distance = dx * dx + dz * dz;
                if (distance >= safeSquared) continue;
                for (int y = lowY; y <= highY; y++) {
                    if (!ready.contains(SectionPos.asLong(x, y, z))) {
                        safeSquared = distance;
                        break;
                    }
                }
            }
        }
        cachedCenter = center.immutable();
        cachedEdge = Math.max(0, Math.min(radius, (int) Math.floor(Math.sqrt(safeSquared)) - 2));
        return cachedEdge;
    }
}
