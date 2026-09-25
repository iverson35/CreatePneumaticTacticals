package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import net.minecraft.client.renderer.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;
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

    /**
     * Receiver base pass joins the shared runtime atlas (GunTextureAtlas)
     * when its texture fits: the receiver, every module and every other gun
     * on screen then draw through ONE RenderType. Falls back to the
     * per-texture type otherwise (UVs restored first).
     */
    @Override
    public RenderType getRenderType(GeoGunItem animatable, ResourceLocation texture,
                                    @org.jetbrains.annotations.Nullable MultiBufferSource bufferSource,
                                    float partialTick) {
        GunGeoModel model = (GunGeoModel) getGeoModel();
        BakedGeoModel baked = model.getBakedModel(model.getModelResource(animatable));
        GunTextureAtlas.Slot slot = GunTextureAtlas.acquire(texture, null);
        if (baked != null && slot != null && GunTextureAtlas.retarget(baked, slot))
            return GunTextureAtlas.CUTOUT;
        if (baked != null) GunTextureAtlas.retarget(baked, null);
        return super.getRenderType(animatable, texture, bufferSource, partialTick);
    }

    /**
     * ImmediatelyFast HUD batching: GeckoLib's GUI branch ends with a forced
     * endBatch + depth/lighting flips; inside IF's hotbar batch that draws
     * this slot and every queued slot to its left against the world's
     * leftover depth buffer (translucent icons) — see GeoGuiBatchCompat.
     * Queue-only while batching, vanilla GeckoLib path otherwise.
     */
    @Override
    protected void renderInGui(ItemDisplayContext transformType, PoseStack poseStack,
                               MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (GeoGuiBatchCompat.isHudBatching()) {
            GeoGuiBatchCompat.renderQueued(this, animatable, currentItemStack,
                    poseStack, bufferSource, packedLight);
            return;
        }
        super.renderInGui(transformType, poseStack, bufferSource, packedLight, packedOverlay);
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
        // LOD: the pass pose translation is the item position in camera
        // space (entity/hand transforms applied, display offsets < 1
        // block), so its length is the camera distance. GUI has no world
        // position and never LODs.
        int lod = dev.ignis.createpneumatictacticals.Config.lodDistance;
        GunModulesLayer.lodActive = lod > 0 && context != ItemDisplayContext.GUI
                && itemDistSq(poseStack) > lod * (float) lod;
        ((GunGeoModel) getGeoModel()).setStack(stack);
        if (context == ItemDisplayContext.GUI) {
            // auto-fitted vanilla-style 3/4 view; geckolib ignores geo.json "display"
            ItemGuiTransform.apply(poseStack,
                    getGeoModel().getBakedModel(getGeoModel().getModelResource(animatable)));
        }
        if (firstPerson) {
            float partialTick = net.minecraft.client.Minecraft.getInstance().getFrameTime();
            // low/high ready pose while sprinting / elytra flying;
            // high vs low follows the view pitch, cross-faded (ReadyModel)
            ReadyPoseTransform.apply(poseStack,
                    dev.ignis.createpneumatictacticals.client.ReadyModel.highMix(partialTick),
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
                float rotDeg = (float) (kick * 1.175 * hipShare);
                float push = (float) (kick * (0.006 + 0.024 * hipShare));
                poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(rotDeg));
                poseStack.translate(0, 0, push);
            }
        }
        super.renderByItem(stack, context, poseStack, bufferSource, packedLight, packedOverlay);
        GunHandsLayer.isFirstPersonPass = false;
        GunModulesLayer.animationsEnabled = false;
        GunModulesLayer.lodActive = false;
    }

    /** camera-space distance squared of the item being rendered */
    private static float itemDistSq(PoseStack poseStack) {
        org.joml.Matrix4f m = poseStack.last().pose();
        float dx = m.m30(), dy = m.m31(), dz = m.m32();
        return dx * dx + dy * dy + dz * dz;
    }

    /** the live gun model — used by GunAnimationDriver to pin animation resolution context */
    public static GunGeoModel activeModel() {
        return activeModel;
    }

    // --- 3D workbench bench rendering (no hands, rest pose) ---

    private static final GunHandsAwareRenderer BENCH_RENDERER = new GunHandsAwareRenderer(new GunGeoModel());

    /**
     * Renders a gun stack at rest pose in a BlockEntityRenderer context
     * (upright on the workbench). No hands layer (isFirstPersonPass false,
     * animationsEnabled false — GunModulesLayer takes its rest-pose branch
     * which renders every installed module at its loc bone), no item
     * display transforms: the caller owns the PoseStack.
     */
    public static void renderStandalone(ItemStack stack, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!(stack.getItem() instanceof GeoGunItem gunItem)) return;
        GunGeoModel model = (GunGeoModel) BENCH_RENDERER.getGeoModel();
        model.setStack(stack);
        BakedGeoModel baked = model.getBakedModel(model.getModelResource(gunItem));
        if (baked == null) return;
        // reRender (isReRender=true) skips two things the bench must not have:
        // handleAnimations (the item's live idle/equip/reload state would bake
        // itself into the staged pose) and GeoItemRenderer.preRender's
        // (0.5, 0.51, 0.5) item-centering offset. Bones are forced to rest and
        // restored afterwards — they are shared with the in-hand pass.
        boolean prevFirstPerson = GunHandsLayer.isFirstPersonPass;
        boolean prevAnimated = GunModulesLayer.animationsEnabled;
        boolean prevLod = GunModulesLayer.lodActive;
        GunHandsLayer.isFirstPersonPass = false;
        GunModulesLayer.animationsEnabled = false;
        GunModulesLayer.lodActive = false; // bench: always full detail
        java.util.Map<software.bernie.geckolib.core.animatable.model.CoreGeoBone, float[]> saved =
                GunAnimations.snapshotBones(model);
        GunAnimations.resetToRestPose(model);
        try {
            float partialTick = net.minecraft.client.Minecraft.getInstance().getFrameTime();
            ResourceLocation texture = model.getTextureResource(gunItem);
            GunTextureAtlas.Slot slot = GunTextureAtlas.acquire(texture, null);
            RenderType type;
            if (slot != null && GunTextureAtlas.retarget(baked, slot)) type = GunTextureAtlas.CUTOUT;
            else {
                GunTextureAtlas.retarget(baked, null);
                type = model.getRenderType(gunItem, texture);
            }
            VertexConsumer buffer = bufferSource.getBuffer(type);
            BENCH_RENDERER.reRender(baked, poseStack, bufferSource, gunItem, type, buffer,
                    partialTick, packedLight, packedOverlay, 1f, 1f, 1f, 1f);
            // reRender does NOT run the render layers (GeckoLib only does that
            // in defaultRender) — without this the installed modules and the
            // emissive pass never draw on the bench
            BENCH_RENDERER.applyRenderLayers(poseStack, gunItem, baked, type, bufferSource, buffer,
                    partialTick, packedLight, packedOverlay);
        } finally {
            GunAnimations.restoreBones(saved);
            GunHandsLayer.isFirstPersonPass = prevFirstPerson;
            GunModulesLayer.animationsEnabled = prevAnimated;
            GunModulesLayer.lodActive = prevLod;
        }
    }

}