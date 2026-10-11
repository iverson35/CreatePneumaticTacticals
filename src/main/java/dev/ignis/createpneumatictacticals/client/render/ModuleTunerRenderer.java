package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.ignis.createpneumatictacticals.block.entity.ModuleTunerBlockEntity;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;

/**
 * Draws the module lying on the tuning table. The item model is placed by its
 * own bounds — centred on the block, bottom face resting on the block's top
 * surface — so a muzzle brake, a grip or a handguard all sit ON the table
 * instead of floating inside it. Same geo, texture and dye as the inventory
 * icon; standalone module items never animate, so the bones are drawn at rest
 * (see {@link ModuleDraw} for the shared snapshot/atlas handling).
 */
public final class ModuleTunerRenderer implements BlockEntityRenderer<ModuleTunerBlockEntity> {

    /** module bottom rests a hair above the block top: no z-fighting */
    private static final float REST_Y = 1.0f + 0.002f;

    public ModuleTunerRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ModuleTunerBlockEntity tuner, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        ItemStack stack = tuner.getModule();
        if (!(stack.getItem() instanceof ModuleItem moduleItem)) return;
        ModuleGeoModel model = ModuleDraw.geoModel();
        model.setStack(stack);
        BakedGeoModel baked = model.getBakedModel(model.getModelResource(moduleItem));
        float[] bounds = ModelBounds.of(baked);
        if (bounds == null) return;
        // bottom-only handguard attachments are modelled upside down to match
        // their 180°-rolled loc bone; the standalone item flips them back
        // upright, and the placement bounds must follow that flip
        boolean flip = ModuleItemRenderer.isBottomOnly(stack);
        if (flip) bounds = ModuleDraw.flipZ(bounds);
        // the table is solid, so the light stored AT its position is 0 — sample
        // the air above it, where the module actually sits (bench does the same)
        packedLight = LevelRenderer.getLightColor(tuner.getLevel(), tuner.getBlockPos().above());

        poseStack.pushPose();
        try {
            poseStack.translate(0.5f - (bounds[0] + bounds[3]) / 2f, REST_Y - bounds[1],
                    0.5f - (bounds[2] + bounds[5]) / 2f);
            if (flip) poseStack.mulPose(Axis.ZP.rotationDegrees(180f));
            ModuleDraw.draw(stack, poseStack, buffer, partialTick, packedLight, packedOverlay);
        } finally {
            poseStack.popPose();
        }
    }

    /** the module stands above the block: draw it from further out than default */
    @Override
    public int getViewDistance() {
        return 64;
    }
}
