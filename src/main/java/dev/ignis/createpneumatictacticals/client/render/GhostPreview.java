package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;

/**
 * The translucent blue "this is what you would get" preview, shared by the
 * assembly bench's mount ghost and the pegboard's placement ghost: one colour,
 * one draw entry, one place that hides the emissive passes.
 *
 * <p>{@link #tintAll} is the signal {@link GunModulesLayer} and
 * {@link GunGlowLayer} read while a whole stack (receiver plus its installed
 * modules) is being drawn in that colour: a ghost is one flat silhouette, so
 * its modules are tinted too and no glow pass is drawn.
 */
public final class GhostPreview {

    /** translucent blue, the one source for both previews */
    public static final float R = 0.4f, G = 0.6f, B = 1f, A = 0.5f;

    /** set while the whole stack is drawn through the ghost channel */
    static boolean tintAll;

    /** draws a gun or a module stack translucent blue in the caller's pose */
    public static void drawStack(ItemStack stack, PoseStack poseStack, MultiBufferSource buffer,
                                 int packedLight, int packedOverlay) {
        if (stack.getItem() instanceof GunItem) {
            tintAll = true;
            try {
                GunHandsAwareRenderer.renderStandalone(stack, poseStack, buffer, packedLight, packedOverlay, true);
            } finally {
                tintAll = false;
            }
        } else if (stack.getItem() instanceof ModuleItem) {
            ModuleDraw.draw(stack, poseStack, buffer, Minecraft.getInstance().getFrameTime(),
                    packedLight, packedOverlay, true);
        }
    }

    private GhostPreview() {
    }
}
