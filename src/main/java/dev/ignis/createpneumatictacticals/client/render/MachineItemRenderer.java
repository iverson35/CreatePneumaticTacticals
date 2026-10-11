package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

import java.util.Map;

/**
 * Inventory/hand/dropped icon of the machine: the same geo and texture as the
 * placed block, fitted into the slot with the vanilla-style 3/4 view
 * ({@link ItemGuiTransform}, exactly like the module and gun icons).
 */
public final class MachineItemRenderer extends BlockEntityWithoutLevelRenderer {

    public static final MachineItemRenderer INSTANCE = new MachineItemRenderer();

    private MachineItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        BakedGeoModel baked = MachineModelDraw.bakedModel();
        if (baked == null) return;
        // GeckoLib's own item renderers ignore the geo.json "display" block, so
        // the icon is fitted by measuring the baked model (see ItemGuiTransform)
        ItemGuiTransform.apply(poseStack, baked, false);
        // ItemGuiTransform solves for a renderer that translates +0.5/+0.51/+0.5
        // (GeoItemRenderer.preRender, gated on !isReRender) — MachineModelDraw
        // draws through reRender, which skips it, so add it here: without this
        // the icon sits ~0.85 model-widths off the slot centre.
        poseStack.translate(0.5f, 0.51f, 0.5f);
        // the baked model is shared with the world pass (same geo path = same
        // GeckoLib cache entry), and that pass writes the moving parts' bone
        // offsets: draw at rest, then restore (not re-reset — a leftover reset
        // would leak into the same-frame world render)
        Map<CoreGeoBone, float[]> saved = GunAnimations.snapshotBones(MachineGeoModel.INSTANCE);
        GunAnimations.resetToRestPose(MachineGeoModel.INSTANCE);
        try {
            MachineModelDraw.INSTANCE.draw(baked, poseStack, bufferSource, packedLight, packedOverlay);
        } finally {
            GunAnimations.restoreBones(saved);
        }
    }
}
