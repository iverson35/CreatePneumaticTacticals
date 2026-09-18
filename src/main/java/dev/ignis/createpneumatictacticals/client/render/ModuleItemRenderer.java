package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/** Feeds the rendered stack into ModuleGeoModel before each pass. */
public final class ModuleItemRenderer extends GeoItemRenderer<ModuleItem> {

    public ModuleItemRenderer() {
        super(new ModuleGeoModel());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        ((ModuleGeoModel) getGeoModel()).setStack(stack);
        if (context == ItemDisplayContext.GUI) {
            // auto-fitted vanilla-style 3/4 view; geckolib ignores geo.json "display"
            ItemGuiTransform.apply(poseStack,
                    getGeoModel().getBakedModel(getGeoModel().getModelResource(animatable)));
        }
        super.renderByItem(stack, context, poseStack, bufferSource, packedLight, packedOverlay);
    }
}