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

    public GunHandsAwareRenderer(GunGeoModel model) {
        super(model);
        addRenderLayer(new GunHandsLayer(this));
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        boolean firstPerson = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        GunHandsLayer.isFirstPersonPass = firstPerson;
        ((GunGeoModel) getGeoModel()).setStack(stack);
        if (firstPerson) {
            // recoil model kick: gun jumps back toward the camera
            double kick = dev.ignis.createpneumatictacticals.client.RecoilModel.modelKick();
            if (Math.abs(kick) > 0.001) {
                poseStack.translate(0, 0, kick * 0.01);
            }
            // ADS: bring the receiver's camera locator bone to screen center
            AdsTransform.apply(stack, poseStack);
        }
        super.renderByItem(stack, context, poseStack, bufferSource, packedLight, packedOverlay);
        GunHandsLayer.isFirstPersonPass = false;
    }
}