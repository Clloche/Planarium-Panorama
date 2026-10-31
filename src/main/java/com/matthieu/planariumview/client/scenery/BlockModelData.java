package com.matthieu.planariumview.client.scenery;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Donnees de modele d'un bloc des copies (decor geant, anneau, structures Sable, miniature), comme NeoForge les
 * prepare pour les vrais chunks : celles de sa block entity, puis celles que le modele tire de ses voisins
 * (BakedModel.getModelData). Les textures connectees (Fusion : verre, vitres) et les blocs qui imitent un autre bloc
 * (copycats de Create...) en ont besoin ; avec ModelData.EMPTY, ils se dessinaient comme un bloc isole.
 */
public final class BlockModelData {
    private BlockModelData() {
    }

    /** {@code base} : donnees deja connues (celles d'Ars pour la miniature), ou ModelData.EMPTY. */
    public static ModelData of(BakedModel model, BlockAndTintGetter world, BlockPos pos, BlockState state, ModelData base) {
        try {
            ModelData data = base == null ? ModelData.EMPTY : base;
            if (data == ModelData.EMPTY && state.hasBlockEntity()) {
                BlockEntity entity = world.getBlockEntity(pos);
                if (entity != null) data = entity.getModelData();
            }
            ModelData result = model.getModelData(world, pos, state, data);
            return result == null ? ModelData.EMPTY : result;
        } catch (RuntimeException | LinkageError e) {
            // Un modele qui suppose un vrai monde : dessine comme avant, sans ses donnees.
            return base == null ? ModelData.EMPTY : base;
        }
    }
}
