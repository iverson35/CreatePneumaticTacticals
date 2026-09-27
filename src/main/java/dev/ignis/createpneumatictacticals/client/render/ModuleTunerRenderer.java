package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ignis.createpneumatictacticals.block.entity.ModuleTunerBlockEntity;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

import java.util.Map;

/**
 * Draws the module lying on the tuning table. The item model is placed by its
 * own bounds — centred on the block, bottom face resting on the block's top
 * surface — so a muzzle brake, a grip or a handguard all sit ON the table
 * instead of floating inside it. Same geo, texture and dye as the inventory
 * icon; standalone module items never animate, so the bones are drawn at rest.
 */
public final class ModuleTunerRenderer implements BlockEntityRenderer<ModuleTunerBlockEntity> {

    /** module bottom rests a hair above the block top: no z-fighting */
    private static final float REST_Y = 1.0f + 0.002f;

    /** BER render path of the module item, mirroring the bench's gun renderer */
    private static final ModuleItemRenderer MODULE_RENDERER = new ModuleItemRenderer();

    public ModuleTunerRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ModuleTunerBlockEntity tuner, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        ItemStack stack = tuner.getModule();
        if (!(stack.getItem() instanceof ModuleItem moduleItem)) return;
        ModuleGeoModel model = (ModuleGeoModel) MODULE_RENDERER.getGeoModel();
        model.setStack(stack);
        BakedGeoModel baked = model.getBakedModel(model.getModelResource(moduleItem));
        if (baked == null) return;
        float[] bounds = GunWorkbenchRenderer.modelBounds(baked);
        if (bounds == null) return;
        // bottom-only handguard attachments are modelled upside down to match
        // their 180°-rolled loc bone; the standalone item flips them back
        // upright, and the placement bounds must follow that flip
        boolean flip = ModuleItemRenderer.isBottomOnly(stack);
        if (flip) bounds = flippedZ(bounds);
        // the table is solid, so the light stored AT its position is 0 — sample
        // the air above it, where the module actually sits (bench does the same)
        packedLight = LevelRenderer.getLightColor(tuner.getLevel(), tuner.getBlockPos().above());

        poseStack.pushPose();
        try {
            poseStack.translate(0.5f - (bounds[0] + bounds[3]) / 2f, REST_Y - bounds[1],
                    0.5f - (bounds[2] + bounds[5]) / 2f);
            if (flip) poseStack.mulPose(Axis.ZP.rotationDegrees(180f));
            drawModel(baked, moduleItem, model, poseStack, buffer, partialTick, packedLight, packedOverlay);
        } finally {
            poseStack.popPose();
        }
    }

    /** the module stands above the block: draw it from further out than default */
    @Override
    public int getViewDistance() {
        return 64;
    }

    private static void drawModel(BakedGeoModel baked, ModuleItem animatable, ModuleGeoModel model,
                                  PoseStack poseStack, MultiBufferSource buffer, float partialTick,
                                  int packedLight, int packedOverlay) {
        // the baked model is SHARED with the on-gun pass (same geo path = same
        // GeckoLib cache entry), so a gun's reload/fire animation playing on
        // those bones would otherwise leak onto the table: draw at rest, then
        // restore — restoring (not re-resetting) keeps the same-frame world
        // render of the gun intact
        Map<CoreGeoBone, float[]> saved = GunAnimations.snapshotBones(model);
        GunAnimations.resetToRestPose(model);
        try {
            ResourceLocation texture = model.getTextureResource(animatable);
            // that shared model may also have its UVs rewritten to atlas slot
            // coordinates by a gun render; this pass binds the standalone
            // texture, so consult the registry first (retarget(null) restores
            // the snapshot original and short-circuits for never-atlased models)
            GunTextureAtlas.retarget(baked, null);
            RenderType type = RenderType.entityCutoutNoCull(texture);
            VertexConsumer consumer = buffer.getBuffer(type);
            // reRender (isReRender=true) skips handleAnimations and the item
            // centering offset, exactly like the bench's staged gun
            MODULE_RENDERER.reRender(baked, poseStack, buffer, animatable, type, consumer,
                    partialTick, packedLight, packedOverlay, 1f, 1f, 1f, 1f);
        } finally {
            GunAnimations.restoreBones(saved);
        }
    }

    /** Z-180 model flip (x, y, z) -> (-x, -y, z) applied to an AABB */
    private static float[] flippedZ(float[] b) {
        return new float[]{-b[3], -b[4], b[2], -b[0], -b[1], b[5]};
    }
}
