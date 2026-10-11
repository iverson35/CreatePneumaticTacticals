package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

/**
 * The one entry point that draws the machine's chassis, shared by the block
 * entity renderer (world pass) and {@link MachineItemRenderer} (item icon).
 *
 * <p>Everything goes through {@link GeoRenderer#reRender} with
 * {@code isReRender = true}: it skips {@code handleAnimations}, the item
 * centring translate and the model scale, so the model is drawn exactly at the
 * caller's pose, at 1:1 with the world, with whatever bone offsets the caller
 * has written. The caller owns {@code pushPose}/{@code popPose} and the
 * position/orientation; this class owns the render type and texture.
 */
public final class MachineModelDraw implements GeoRenderer<MachineAnimatable> {

    public static final MachineModelDraw INSTANCE = new MachineModelDraw();

    private MachineModelDraw() {
    }

    /**
     * Baked machine model, or null when it is not available (missing resource
     * or a failed bake). {@code GeoModel.getBakedModel} THROWS
     * {@code GeckoLibException} on a miss, and a block entity renderer that
     * throws kills the whole game, so callers ask here first and skip the pass.
     */
    @Nullable
    public static BakedGeoModel bakedModel() {
        if (Minecraft.getInstance().getResourceManager()
                .getResource(MachineGeoModel.MODEL_ID).isEmpty()) {
            return null;
        }
        return MachineGeoModel.INSTANCE.getBakedModel(MachineGeoModel.MODEL_ID);
    }

    @Override
    public GeoModel<MachineAnimatable> getGeoModel() {
        return MachineGeoModel.INSTANCE;
    }

    @Override
    public MachineAnimatable getAnimatable() {
        return MachineAnimatable.INSTANCE;
    }

    /** draw the baked model at the current pose (no extra translate, no animation pass) */
    public void draw(BakedGeoModel baked, PoseStack poseStack, MultiBufferSource buffer,
                     int packedLight, int packedOverlay) {
        RenderType type = RenderType.entityCutoutNoCull(MachineGeoModel.TEXTURE);
        reRender(baked, poseStack, buffer, MachineAnimatable.INSTANCE, type, buffer.getBuffer(type),
                0f, packedLight, packedOverlay, 1f, 1f, 1f, 1f);
    }

    // --- GeoRenderer plumbing: this drawer fires no GeckoLib render events ---

    @Override
    public void fireCompileRenderLayersEvent() {
    }

    @Override
    public boolean firePreRenderEvent(PoseStack poseStack, BakedGeoModel model,
                                      MultiBufferSource bufferSource, float partialTick, int packedLight) {
        return true; // "proceed": nothing to fire, nothing can cancel this draw
    }

    @Override
    public void firePostRenderEvent(PoseStack poseStack, BakedGeoModel model,
                                    MultiBufferSource bufferSource, float partialTick, int packedLight) {
    }

    @Override
    public void updateAnimatedTextureFrame(MachineAnimatable animatable) {
    }

    /** never used: only {@link #draw} calls into this renderer, and it never resolves a texture itself */
    @Override
    public ResourceLocation getTextureLocation(MachineAnimatable animatable) {
        return MachineGeoModel.TEXTURE;
    }
}
