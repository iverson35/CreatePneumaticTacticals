package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;

/**
 * GeoItemRenderer that tracks the current ItemDisplayContext: first-person
 * passes set GunHandsLayer.isFirstPersonPass so the hands layer only renders
 * in first person. Also attaches the hands layer.
 */
public final class GunHandsAwareRenderer extends GeoItemRenderer<GeoGunItem> {

    private static GunGeoModel activeModel;

    public GunHandsAwareRenderer(GunGeoModel model) {
        super(model);
        activeModel = model;
        addRenderLayer(new GunHandsLayer(this));
        addRenderLayer(new GunModulesLayer(this));
        addRenderLayer(new GunGlowLayer(this));
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        boolean firstPerson = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        GunHandsLayer.isFirstPersonPass = firstPerson;
        GunModulesLayer.animationsEnabled = firstPerson
                || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        ((GunGeoModel) getGeoModel()).setStack(stack);
        if (context == ItemDisplayContext.GUI) {
            // auto-fitted vanilla-style 3/4 view; geckolib ignores geo.json "display"
            ItemGuiTransform.apply(poseStack,
                    getGeoModel().getBakedModel(getGeoModel().getModelResource(animatable)));
        }
        if (firstPerson) {
            float partialTick = net.minecraft.client.Minecraft.getInstance().getFrameTime();
            // low/high ready pose while sprinting / elytra flying
            ReadyPoseTransform.apply(poseStack,
                    dev.ignis.createpneumatictacticals.Config.readyPose
                            == dev.ignis.createpneumatictacticals.Config.ReadyPose.HIGH,
                    dev.ignis.createpneumatictacticals.client.ReadyModel.progress(partialTick));
            // ADS: bring the receiver's camera locator bone to screen center
            AdsTransform.apply(stack, poseStack);
            // recoil kick, AFTER the ADS alignment: the alignment re-solves the
            // eye bone onto the view axis from the live pose matrix, so a kick
            // applied before it is exactly canceled while aiming. Applying it
            // last keeps it visible relative to the view: the muzzle flips up
            // around the grip anchor and the gun pushes back toward the
            // camera. Hipfire is much louder than the aimed shot.
            double kick = dev.ignis.createpneumatictacticals.client.RecoilModel.modelKick();
            if (Math.abs(kick) > 0.001) {
                float aim = dev.ignis.createpneumatictacticals.client.AimHandler.aimProgress(partialTick);
                aim = aim * aim * (3f - 2f * aim);
                float hipShare = 1f - aim;
                // ADS/tactical: pure backward push only (a muzzle flip would
                // sway the sight picture); hipfire: ~13 deg flip + 8.4 cm push
                float rotDeg = (float) (kick * 4.7 * hipShare);
                float push = (float) (kick * (0.006 + 0.024 * hipShare));
                poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(rotDeg));
                poseStack.translate(0, 0, push);
            }
        }
        super.renderByItem(stack, context, poseStack, bufferSource, packedLight, packedOverlay);
        GunHandsLayer.isFirstPersonPass = false;
        GunModulesLayer.animationsEnabled = false;
    }

    /** the live gun model — used by GunAnimationDriver to pin animation resolution context */
    public static GunGeoModel activeModel() {
        return activeModel;
    }
}