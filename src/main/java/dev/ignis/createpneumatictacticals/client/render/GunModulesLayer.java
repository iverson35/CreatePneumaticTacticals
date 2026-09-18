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
    /** ModuleAnimatable manager id, mirrors GunAnimationDriver.triggerModule */
    private static final long INSTANCE_ID = 0;

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
        Ctx ctx = new Ctx(animatable, stack, poseStack, bufferSource, partialTick, packedLight,
                packedOverlay, modules, hgAttachments);

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
        driveAnimation(ModuleAnimatable.of(def.id), ctx.partialTick);

        ctx.poseStack.pushPose();
        applyBoneChain(loc, ctx.poseStack);
        RenderType type = RenderType.entityCutoutNoCull(ModuleGunGeoModel.textureId(def.id));
        getRenderer().reRender(model, ctx.poseStack, ctx.bufferSource, ctx.animatable, type,
                ctx.bufferSource.getBuffer(type), ctx.partialTick, ctx.packedLight, ctx.packedOverlay, 1, 1, 1, 1);
        // child mounts (barrel -> muzzle, handguard -> attachments); their
        // locator lookup sees this module's animated bone state
        if (def.type == ModuleType.BARREL) {
            mount(model, "loc_muzzle_attachment", ctx.modules.get(ModuleType.MUZZLE), ctx);
        } else if (def.type == ModuleType.HANDGUARD && !ctx.hgAttachments.isEmpty()) {
            for (Map.Entry<HandguardPosition, ModuleDefinition> e : ctx.hgAttachments.entrySet()) {
                mount(model, e.getKey().locatorName(), e.getValue(), ctx);
            }
        }
        ctx.poseStack.popPose();
    }

    /** walks root→locator applying each bone's animated local transform */
    private static void applyBoneChain(CoreGeoBone locator, PoseStack poseStack) {
        List<CoreGeoBone> chain = new ArrayList<>();
        for (CoreGeoBone b = locator; b != null; b = b.getParent()) {
            chain.add(0, b);
        }
        for (CoreGeoBone b : chain) {
            RenderUtils.prepMatrixForBone(poseStack, b);
        }
    }

    private static void driveAnimation(ModuleAnimatable ma, float partialTick) {
        AnimationState<ModuleAnimatable> state = new AnimationState<>(ma, 0, 0, partialTick, false);
        state.setData(DataTickets.TICK, ma.getTick(ma));
        ModuleGunGeoModel.INSTANCE.handleAnimations(ma, INSTANCE_ID, state);
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
                       Map<HandguardPosition, ModuleDefinition> hgAttachments) {}
}
