package com.matthieu.planariumview.compat;

import com.matthieu.planariumview.client.scenery.SceneryParticles;

/** Ajoute seulement aux lucioles de DS : leur vol et leur lumiere suivent l'echelle du decor. */
public interface SceneryFirefly {
    SceneryParticles.Space planariumview$space();

    void planariumview$space(SceneryParticles.Space space);
}
