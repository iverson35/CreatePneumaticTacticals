package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import dev.ignis.createpneumatictacticals.module.HandguardPosition;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the installed modules onto the assembled gun (plan_v3 挂载树).
 * Each module model is drawn at its mount locator bone: the root-to-locator
 * bone chain is applied with the ANIMATED bone state (this layer runs after
 * the receiver's animation pass), so animating a locator — e.g. a recoil
 * keyframe on {@code loc_barrel} — moves the mounted module and everything
 * under it. Equivalent to bone parenting without mutating the shared
 * BakedGeoModels. Recurses one level: the barrel carries the muzzle, the
 * handguard carries position-bound attachments.
 */
public final class GunModulesLayer extends GeoRenderLayer<GeoGunItem> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> WARNED = new HashSet<>();

    /**
     * Set by GunHandsAwareRenderer around each render pass: module animations
     * only play while the gun is in a hand (first/third person). Inventory
     * icons, dropped guns etc. still render the modules but hold the rest
     * pose (plan: 物品栏图标无动画).
     */
    public static boolean animationsEnabled = false;

    public GunModulesLayer(GeoRenderer<GeoGunItem> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, GeoGunItem animatable, BakedGeoModel receiverModel,
                       RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                       float partialTick, int packedLight, int packedOverlay) {
        ItemStack stack = ((GunGeoModel) getRenderer().getGeoModel()).currentStack();
        if (stack == null) return;
        Map<ModuleType, ModuleDefinition> modules = GunNbt.readModules(stack);
        if (modules.size() <= 1) return; // receiver only, or nothing
        Map<HandguardPosition, ModuleDefinition> hgAttachments = GunNbt.readHandguardAttachments(stack);
        // module animation state is isolated per gun stack (GeoItem id), so
        // two guns sharing a module definition don't play each other's anims
        Ctx ctx = new Ctx(animatable, stack, poseStack, bufferSource, partialTick, packedLight,
                packedOverlay, modules, hgAttachments, software.bernie.geckolib.animatable.GeoItem.getId(stack));

        mount(receiverModel, "loc_feed", modules.get(ModuleType.FEED), ctx);
        mount(receiverModel, "loc_supply", modules.get(ModuleType.SUPPLY), ctx);
        mount(receiverModel, "loc_sight", modules.get(ModuleType.SIGHT), ctx);
        mount(receiverModel, "loc_sight_side", modules.get(ModuleType.TACTICAL_SIGHT), ctx);
        mount(receiverModel, "loc_stock", modules.get(ModuleType.STOCK), ctx);
        mount(receiverModel, "loc_barrel", modules.get(ModuleType.BARREL), ctx);
        mount(receiverModel, "loc_handguard", modules.get(ModuleType.HANDGUARD), ctx);
    }

    /** Renders one module at the parent's locator bone, then its children. */
    private void mount(BakedGeoModel parentModel, String locator, @Nullable ModuleDefinition def, Ctx ctx) {
        if (def == null) return;
        if (ModuleRenderOverrides.isOverridden(ctx.stack, def.id)) return; // claimed by a custom renderer
        CoreGeoBone loc = parentModel.getBone(locator).orElse(null);
        if (loc == null) {
            warnOnce(locator + "@" + def.id, "module {} not rendered: parent model lacks locator bone {}", def.id, locator);
            return;
        }
        ResourceLocation modelId = ModuleGunGeoModel.modelId(def.id);
        if (!GeckoLibCache.getBakedModels().containsKey(modelId)) {
            warnOnce("model:" + def.id, "module {} not rendered: no baked model {}", def.id, modelId);
            return;
        }
        BakedGeoModel model = ModuleGunGeoModel.INSTANCE.getBakedModel(modelId); // activates bones on the shared processor
        java.util.Map<CoreGeoBone, float[]> saved = null;
        if (animationsEnabled) {
            driveAnimation(ModuleAnimatable.of(def.id), ctx.animId, ctx.partialTick);
        } else {
            // GUI icon / dropped gun: draw at rest, but restore afterwards.
            // Bones are shared with the world passes and GeckoLib skips
            // re-applying animation on same-tick frames (isReRender), so a
            // leftover reset leaks the rest pose into the world render —
            // the magazine visibly flickers between rest and animated.
            saved = GunAnimations.snapshotBones(ModuleGunGeoModel.INSTANCE);
            GunAnimations.resetToRestPose(ModuleGunGeoModel.INSTANCE);
        }

        ctx.poseStack.pushPose();
        try {
            applyBoneChain(loc, ctx.poseStack);
            RenderType type = RenderType.entityCutoutNoCull(ModuleGunGeoModel.textureId(def.id));
            getRenderer().reRender(model, ctx.poseStack, ctx.bufferSource, ctx.animatable, type,
                    ctx.bufferSource.getBuffer(type), ctx.partialTick, ctx.packedLight, ctx.packedOverlay, 1, 1, 1, 1);
            // child mounts (barrel -> muzzle, handguard -> attachments); their
            // locator lookup sees this module's animated bone state
            if (def.type == ModuleType.BARREL) {
                // capture the muzzle tip for MuzzleSmoke: push, walk the
                // barrel model's root->loc_muzzle_attachment chain (the
                // recursive mount below pops its own pose, so it cannot
                // leave that space for us), sample, then pop. Works bare
                // (muzzle module absent -> tip at the locator) or with a
                // device (front face computed from its cubes).
                ctx.poseStack.pushPose();
                try {
                    CoreGeoBone muzzleLoc = model.getBone("loc_muzzle_attachment").orElse(null);
                    if (muzzleLoc != null) {
                        applyBoneChain(muzzleLoc, ctx.poseStack);
                        captureMuzzleAnchor(ctx, ctx.poseStack, ctx.modules.get(ModuleType.MUZZLE));
                    }
                } finally {
                    ctx.poseStack.popPose();
                }
                mount(model, "loc_muzzle_attachment", ctx.modules.get(ModuleType.MUZZLE), ctx);
            } else if (def.type == ModuleType.HANDGUARD && !ctx.hgAttachments.isEmpty()) {
                for (Map.Entry<HandguardPosition, ModuleDefinition> e : ctx.hgAttachments.entrySet()) {
                    mount(model, e.getKey().locatorName(), e.getValue(), ctx);
                }
            }
        } finally {
            ctx.poseStack.popPose();
            if (saved != null) GunAnimations.restoreBones(saved);
        }
    }

    /**
     * Walks root→locator applying each bone's animated local transform, then
     * lands on the locator pivot. prepMatrixForBone ends with
     * translateAwayFromPivotPoint (net identity for a static bone), so without
     * the final translateToPivotPoint the module would render at the parent
     * model origin instead of the mount point. Net transform:
     * T(pos)·T(pivot)·R·S — module origin at the pivot, inheriting rotation.
     */
    private static void applyBoneChain(CoreGeoBone locator, PoseStack poseStack) {
        List<CoreGeoBone> chain = new ArrayList<>();
        for (CoreGeoBone b = locator; b != null; b = b.getParent()) {
            chain.add(0, b);
        }
        for (CoreGeoBone b : chain) {
            RenderUtils.prepMatrixForBone(poseStack, b);
        }
        RenderUtils.translateToPivotPoint(poseStack, locator);
    }

    /**
     * Muzzle smoke anchor: sample the muzzle mount's render transform into
     * world/view space. With a muzzle device installed, the anchor sits at
     * its front face: muzzle modules extend along module +Z (module origin
     * at loc_muzzle_attachment, barrel points toward -Z_world, and the
     * suppressor cube spans z [-3.5, 0] with its face at z=0), so the front
     * is max(pivot.z + size.z/2) over the device's cubes — GeoCube pivots
     * and sizes are already in block units (baked /16 by GeckoLib). Bare
     * barrel falls back to the mount locator itself (its pivot already sits
     * at the barrel tip).
     */
    private static void captureMuzzleAnchor(Ctx ctx, PoseStack poseStack, @Nullable ModuleDefinition muzzleDef) {
        if (!animationsEnabled) return; // GUI/dropped: no world truth
        if (mc().player == null) return;
        if (ctx.stack != mc().player.getMainHandItem()) return;
        org.joml.Vector3f tip = new org.joml.Vector3f(0, 0, 0);
        if (muzzleDef != null) {
            float maxZ = Float.NEGATIVE_INFINITY;
            software.bernie.geckolib.cache.object.BakedGeoModel mm = null;
            try {
                mm = ModuleGunGeoModel.INSTANCE.getBakedModel(ModuleGunGeoModel.modelId(muzzleDef.id));
            } catch (Exception ignored) {}
            if (mm != null) {
                for (software.bernie.geckolib.cache.object.GeoBone bone : mm.topLevelBones()) {
                    for (software.bernie.geckolib.cache.object.GeoCube cube : bone.getCubes()) {
                        maxZ = Math.max(maxZ, (float) (cube.pivot().z + cube.size().z / 2));
                    }
                }
            }
            if (maxZ != Float.NEGATIVE_INFINITY) tip.set(0, 0, maxZ);
        }
        MuzzleAnchor.capture(poseStack, tip, mc().player.getUUID());
    }

    private static net.minecraft.client.Minecraft mc() {
        return net.minecraft.client.Minecraft.getInstance();
    }

    private static void driveAnimation(ModuleAnimatable ma, long instanceId, float partialTick) {
        AnimationState<ModuleAnimatable> state = new AnimationState<>(ma, 0, 0, partialTick, false);
        state.setData(DataTickets.TICK, ma.getTick(ma));
        ModuleGunGeoModel.INSTANCE.handleAnimations(ma, instanceId, state);
    }

    private static void warnOnce(String key, String msg, Object... args) {
        if (WARNED.add(key)) {
            LOGGER.warn(msg, args);
        }
    }

    /** per-frame render context */
    private record Ctx(GeoGunItem animatable, ItemStack stack, PoseStack poseStack,
                       MultiBufferSource bufferSource, float partialTick,
                       int packedLight, int packedOverlay,
                       Map<ModuleType, ModuleDefinition> modules,
                       Map<HandguardPosition, ModuleDefinition> hgAttachments,
                       long animId) {}
}
