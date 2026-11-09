package com.matthieu.planariumview.integration.bootstrap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

/** Explicit contracts for fragile optional hooks. No external mod class is loaded or initialized here. */
public final class OptionalMixinLayouts {
    private record Member(boolean field, String name, String descriptor, boolean isStatic) {
        boolean present(ClassNode target) {
            return field
                    ? target.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(descriptor)
                        && ((f.access & Opcodes.ACC_STATIC) != 0) == isStatic)
                    : target.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)
                        && ((m.access & Opcodes.ACC_STATIC) != 0) == isStatic);
        }
    }

    private OptionalMixinLayouts() {}

    /** Null means the known prerequisites are satisfied, not that the injection has already succeeded. */
    public static String incompatibility(String mixin, ClassNode target) {
        String name = mixin.substring(mixin.lastIndexOf('.') + 1);
        List<Member> required = switch (name) {
            case "YaclOptionListMixin" -> List.of(
                    field("yaclScreen", "Ldev/isxander/yacl3/gui/YACLScreen;"),
                    method("getRowWidth", "()I", false));
            case "YaclGroupHeaderMixin" -> List.of(
                    field("expandMinimizeButton", "Ldev/isxander/yacl3/gui/LowProfileButtonWidget;"),
                    field("screen", "Lnet/minecraft/client/gui/screens/Screen;"),
                    field("this$0", "Ldev/isxander/yacl3/gui/OptionListWidget;"));
            case "YaclPendingMixin" -> List.of(
                    field("config", "Ldev/isxander/yacl3/api/YetAnotherConfigLib;"),
                    field("pendingChanges", "Z"));
            case "YaclUndoButtonMixin" -> List.of(
                    field("screen", "Ldev/isxander/yacl3/gui/YACLScreen;"),
                    field("saveFinishedButton", "Lnet/minecraft/client/gui/components/Button;"),
                    field("cancelResetButton", "Lnet/minecraft/client/gui/components/Button;"),
                    field("undoButton", "Lnet/minecraft/client/gui/components/Button;"),
                    method("updateButtons", "()V", false),
                    method("doLayout", "(Lnet/minecraft/client/gui/navigation/ScreenRectangle;)V", false));
            case "DsDimensionInfoMixin" -> List.of(
                    field("seaLevel", "I"), method("<init>", "(Lnet/minecraft/world/level/Level;)V", false),
                    method("getSeaLevel", "()I", false), method("alwaysOutside", "()Z", false));
            case "DsChunkViewMixin" -> List.of(
                    field("level", "Lnet/minecraft/world/level/Level;"),
                    method("getBlockState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", false),
                    method("getFluidState", "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;", false));
            case "DsPointLightsMixin" -> List.of(method("add", "(DDDFFFFF)V", true));
            case "FwLightDataCollectorMixin" -> List.of(method("collectSection", "(JJ)V", false));
            case "ClColorSectionMixin" -> List.of(method("colorSection",
                    "(Lxyz/atmerek/contraptionlights/light/color/ColorLightEngine$Scratch;Lnet/minecraft/world/level/BlockAndTintGetter;IIIZ)[S", true));
            case "ClColorFeatureMixin" -> List.of(method("tag", "(ILnet/minecraft/world/level/BlockAndTintGetter;IIIZ)I", true));
            case "PfPlacementMixin" -> List.of(
                    method("getRandomHeightmapPos", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos$MutableBlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", true),
                    method("getRandomPosition", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos$MutableBlockPos;I)Lnet/minecraft/world/level/block/state/BlockState;", true));
            case "AcAmbersolLightMixin" -> List.of(method("testSkylight",
                    "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)Z", true));
            default -> List.of();
        };
        for (Member member : required) {
            if (!member.present(target)) {
                return (member.field ? "champ " : "methode ") + member.name + " " + member.descriptor
                        + (member.isStatic ? " static" : " instance") + " introuvable";
            }
        }
        return null;
    }

    private static Member field(String name, String descriptor) {
        return new Member(true, name, descriptor, false);
    }

    private static Member method(String name, String descriptor, boolean isStatic) {
        return new Member(false, name, descriptor, isStatic);
    }
}
