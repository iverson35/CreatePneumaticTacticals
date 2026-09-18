package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;

/**
 * First-person player arms (referance/notes/first_person_arms.md):
 * hand-made arm models (left_arm / right_arm geo) textured with the player's
 * own skin, rendered at the receiver model's `leftarm` / `rightarm` ref bones.
 * Arm pose animation is authored in the receiver's animation json — the ref
 * bones belong to the receiver model, so reload/fire/bolt keyframes move the
 * arms automatically.
 *
 * Render scope: first-person display contexts only (third-person model has
 * its own arms). The flag is set by GunHandsAwareRenderer from renderByItem.
 */
public final class GunHandsLayer extends GeoRenderLayer<GeoGunItem> {

    /** thread-local-ish flag set by the renderer during first-person passes */
    public static boolean isFirstPersonPass = false;

    private static final ArmGeoModel LEFT_ARM = new ArmGeoModel("left_arm");
    private static final ArmGeoModel RIGHT_ARM = new ArmGeoModel("right_arm");

    public GunHandsLayer(GeoRenderer<GeoGunItem> renderer) {
        super(renderer);
    }

    @Override
    public void renderForBone(PoseStack poseStack, GeoGunItem animatable, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              float partialTick, int packedLight, int packedOverlay) {
        if (!isFirstPersonPass) return;

        ArmGeoModel arm = switch (bone.getName()) {
            case "leftarm" -> LEFT_ARM;
            case "rightarm" -> RIGHT_ARM;
            default -> null;
        };
        if (arm == null) return;

        AbstractClientPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        ResourceLocation skin = player.getSkinTextureLocation();
        RenderType skinType = arm.getRenderType(animatable, skin);
        VertexConsumer skinBuffer = bufferSource.getBuffer(skinType);
        BakedGeoModel baked = arm.getBakedModel(arm.modelId());

        poseStack.pushPose();
        // bone-space: caller already applied the ref bone's pose to the stack
        getRenderer().reRender(baked, poseStack, bufferSource, animatable, skinType, skinBuffer,
                0, packedLight, packedOverlay, 1, 1, 1, 1);
        poseStack.popPose();
    }

    /**
     * Minimal geo model for a standalone arm asset. Texture is overridden per
     * render (player skin), so getTextureResource is only a fallback.
     */
    static final class ArmGeoModel extends GeoModel<GeoGunItem> {
        private final ResourceLocation modelId;

        ArmGeoModel(String name) {
            this.modelId = new ResourceLocation(CreatePneumaticTacticals.MODID, "geo/gun/" + name + ".geo.json");
        }

        ResourceLocation modelId() {
            return modelId;
        }

        @Override
        public ResourceLocation getModelResource(GeoGunItem animatable) {
            return modelId;
        }

        @Override
        public ResourceLocation getTextureResource(GeoGunItem animatable) {
            return modelId; // unused; skin texture is passed per render
        }

        @Override
        public ResourceLocation getAnimationResource(GeoGunItem animatable) {
            return new ResourceLocation(CreatePneumaticTacticals.MODID, "animations/gun/placeholder.animation.json");
        }
    }
}