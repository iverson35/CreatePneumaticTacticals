package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.module.HandguardPosition;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
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
        boolean flip = isBottomOnly(stack);
        if (context == ItemDisplayContext.GUI) {
            // auto-fitted vanilla-style 3/4 view; geckolib ignores geo.json "display"
            ItemGuiTransform.apply(poseStack,
                    getGeoModel().getBakedModel(getGeoModel().getModelResource(animatable)), flip);
        } else if (flip) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(180));
        }
        super.renderByItem(stack, context, poseStack, bufferSource, packedLight, packedOverlay);
    }

    /**
     * Bottom-only handguard attachments are modeled upside down to match the
     * 180°-rolled bottom loc bone (so they mount upright); the item render
     * flips them back so the standalone item looks right-side up.
     */
    private static boolean isBottomOnly(ItemStack stack) {
        ResourceLocation id = ModuleItem.getModuleId(stack);
        if (id == null) return false;
        ModuleDefinition def = ModuleManager.get(id);
        return def != null && def.positions.size() == 1
                && def.positions.get(0) == HandguardPosition.BOTTOM;
    }
}