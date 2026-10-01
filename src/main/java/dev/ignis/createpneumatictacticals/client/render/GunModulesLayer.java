package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.compat.aw.AwCompat;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.module.HandguardPosition;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.PaintManager;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
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

    /**
     * LOD for the current pass: the camera is beyond {@code Config.lodDistance}
     * from the gun being rendered (dropped guns, other players' held guns,
     * item frames — anything in world space). Cosmetic modules — sights,
     * muzzle devices, charms, handguard rail attachments — are skipped:
     * they are a few pixels at that range, while their vertex emission is
     * most of a gun render cost. GUI icons and the local first-person
     * pass never LOD. Set per pass by GunHandsAwareRenderer.
     */
    public static boolean lodActive;

    public GunModulesLayer(GeoRenderer<GeoGunItem> renderer) {
        super(renderer);
    }

    /**
     * Bench preview: the module the player would install at this mount is
     * drawn through the SAME mount tree as an installed module, so the ghost
     * inherits the locator bone's chain (rotation included) exactly. Set by
     * {@code GunWorkbenchRenderer} around the staged gun's render pass.
     */
    public static final ThreadLocal<Ghost> GHOST = new ThreadLocal<>();

    /** mount locator to preview + the held module stack */
    public record Ghost(ResourceLocation mountId, ItemStack stack) {}

    /**
     * World-space FX bone (laser lines): hidden on every non-world pass and
     * excluded from the workbench bounds — its cube stretches to the beam's
     * range, which would otherwise blow up the measured gun size.
     */
    public static final String BEAM_BONE = "laser_beam";
    /** every module model's top-level root bone (author convention); AW
     *  module skins ride this bone's animated transform so an animation
     *  driving main moves the skin with the model (docs §3.9) */
    private static final String MAIN_BONE = "main";

    /** [+] preview tint (translucent blue) */
    private static final float PREVIEW_R = 0.4f, PREVIEW_G = 0.6f, PREVIEW_B = 1f, PREVIEW_A = 0.5f;

    @Override
    public void render(PoseStack poseStack, GeoGunItem animatable, BakedGeoModel receiverModel,
                       RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                       float partialTick, int packedLight, int packedOverlay) {
        ItemStack stack = ((GunGeoModel) getRenderer().getGeoModel()).currentStack();
        if (stack == null) return;
        Map<ModuleType, ModuleDefinition> modules = GunNbt.readModules(stack);
        Ghost ghost = GHOST.get();
        if (modules.size() <= 1 && ghost == null) return; // receiver only, or nothing
        Map<HandguardPosition, ModuleDefinition> hgAttachments = GunNbt.readHandguardAttachments(stack);
        // module animation state is isolated per gun stack (GeoItem id), so
        // two guns sharing a module definition don't play each other's anims
        long animId = GeoItem.getId(stack);
        // AW skins: the gun's whole skin set is bound and ticked once per
        // pass in GunHandsAwareRenderer's receiver pass (which always runs
        // before these mounts) — a per-part tick here would evict the other
        // skinned parts from the shared manager (see AwSkins.tickGun)
        Ctx ctx = new Ctx(animatable, stack, poseStack, bufferSource, partialTick, packedLight,
                packedOverlay, modules, hgAttachments, animId);

        mount(receiverModel, "loc_feed", modules.get(ModuleType.FEED), ctx, ghost);
        mount(receiverModel, "loc_supply", modules.get(ModuleType.SUPPLY), ctx, ghost);
        if (!lodActive) mount(receiverModel, "loc_sight", modules.get(ModuleType.SIGHT), ctx, ghost);
        if (!lodActive) mount(receiverModel, "loc_sight_side", modules.get(ModuleType.TACTICAL_SIGHT), ctx, ghost);
        mount(receiverModel, "loc_stock", modules.get(ModuleType.STOCK), ctx, ghost);
        mount(receiverModel, "loc_barrel", modules.get(ModuleType.BARREL), ctx, ghost);
        mount(receiverModel, "loc_handguard", modules.get(ModuleType.HANDGUARD), ctx, ghost);
        if (!lodActive) mount(receiverModel, "loc_charm", modules.get(ModuleType.CHARM), ctx, ghost);
        // a preview at a receiver mount; deep mounts (muzzle port, handguard
        // attachment) are matched during the descent below
        if (ghost != null) {
            mount(receiverModel, ghost.mountId().getPath(), null, ctx, ghost);
        }
    }

    /** Renders one module at the parent's locator bone, then its children. */
    private void mount(BakedGeoModel parentModel, String locator, @Nullable ModuleDefinition def, Ctx ctx,
                       @Nullable Ghost ghost) {
        Ghost ghostHere = null;
        ModuleDefinition target = def;
        if (def == null && ghost != null && ghost.mountId().getPath().equals(locator)) {
            ModuleDefinition ghostDef = ModuleManager.definitionOf(ghost.stack());
            if (ghostDef != null) {
                target = ghostDef;
                ghostHere = ghost;
            }
        }
        if (target == null) return;
        if (ModuleRenderOverrides.isOverridden(ctx.stack, target.id)) return; // claimed by a custom renderer
        // player-hidden module (workbench dye tab toggle): only the module's
        // OWN body is skipped — child mounts (muzzle on a hidden barrel,
        // rail attachments on a hidden handguard) still render, so hiding
        // a piece never takes its attachments with it. Ghost previews read
        // the held item's own flag (what install will actually produce),
        // installed modules the gun's render copy.
        boolean hidden = ghostHere != null ? ModuleItem.isHidden(ghostHere.stack())
                : GunNbt.isHidden(ctx.stack, target.id);
        CoreGeoBone loc = parentModel.getBone(locator).orElse(null);
        if (loc == null) {
            // a ghost mount id may live on a child model (barrel's muzzle
            // port, handguard attachments): the descent below reaches it
            if (ghostHere == null) {
                warnOnce(locator + "@" + target.id, "module {} not rendered: parent model lacks locator bone {}", target.id, locator);
            }
            return;
        }
        ResourceLocation modelId = ModuleGunGeoModel.modelId(target.id);
        if (!GeckoLibCache.getBakedModels().containsKey(modelId)) {
            warnOnce("model:" + target.id, "module {} not rendered: no baked model {}", target.id, modelId);
            return;
        }
        BakedGeoModel model = ModuleGunGeoModel.INSTANCE.getBakedModel(modelId); // activates bones on the shared processor
        Map<CoreGeoBone, float[]> saved = null;
        // beam bone (laser lines) hides on non-world passes: a GUI icon /
        // dropped gun has no beam FX; the world pass (where the laser
        // actually points) keeps it
        CoreGeoBone beam = ModuleGunGeoModel.INSTANCE.getAnimationProcessor()
                .getBone(BEAM_BONE);
        boolean hideBeam = !animationsEnabled && beam != null;
        if (hideBeam) beam.setHidden(true);
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

        boolean phys = false;
        ctx.poseStack.pushPose();
        try {
            applyBoneChain(loc, ctx.poseStack);
            // AW skin replacement (plan §3.3): a skinned module renders its
            // Armourer's Workshop skin INSTEAD of the GeckoLib body — the
            // skin brings its own texture, so the dye atlas and the glow
            // passes are skipped for it. Ghost previews and AW-absent or
            // still-baking cases fall through to the regular model below
            // (a one-frame fallback rather than an invisible module).
            boolean ghostPassHere = ghostHere != null;
            // hidden charm WITH a skin keeps its chain: the skin replaces
            // only the pendant (drawn at the pendant bone below), the
            // chain/support cubes render normally. Hidden without a skin =
            // the whole module stays invisible.
            boolean charmSkinSwap = hidden && target.type == ModuleType.CHARM && !ghostPassHere
                    && GunNbt.getSkin(ctx.stack, target.id) != null;
            if (charmSkinSwap) {
                // hidden charm WITH a skin: the chain (and its physics) stays
                // alive, the pendant's cubes are masked out and the AW skin
                // draws at the pendant bone's animated position — the skin
                // replaces the pendant and swings with the chain
                phys = CharmPhysics.update(ctx.stack, target, model, ctx.poseStack, ctx.animId);
                CoreGeoBone pendant = model.getBone(CharmPhysics.PENDANT_BONE).orElse(null);
                // no pendant bone: the model has nothing to hang a skin from,
                // so a hidden charm stays fully hidden
                if (pendant != null) drawCharmSkin(model, target, pendant, ctx);
            } else if (!hidden) {
                CompoundTag skinTag = ghostPassHere ? null : GunNbt.getSkin(ctx.stack, target.id);
                if (skinTag == null || !renderModuleSkinInModelFrame(model, skinTag, ctx)) {
                    if (target.type == ModuleType.CHARM) {
                        // the simulation has the last word on the chain bones: it runs
                        // after the animation pass (which restores undriven bones) and
                        // before the draw (plan_v4)
                        phys = CharmPhysics.update(ctx.stack, target, model, ctx.poseStack, ctx.animId);
                    }
                    boolean ghostPass = ghostHere != null;
                    // paint source mirrors the hidden flag: ghost previews
                    // read the held item's own Paint NBT (what install will
                    // actually produce), installed modules the gun's copy
                    ResourceLocation paintId = ghostPass ? ModuleItem.getPaint(ghostHere.stack())
                            : GunNbt.getPaint(ctx.stack, target.id);
                    BodyDraw draw = bodyDraw(model, ctx.stack, target, paintId, ghostPass);
                    getRenderer().reRender(model, ctx.poseStack, ctx.bufferSource, ctx.animatable, draw.type(),
                            ctx.bufferSource.getBuffer(draw.type()), ctx.partialTick, ctx.packedLight,
                            ctx.packedOverlay,
                            ghostPass ? PREVIEW_R : 1, ghostPass ? PREVIEW_G : 1,
                            ghostPass ? PREVIEW_B : 1, ghostPass ? PREVIEW_A : 1);
                    if (ghostPass) return; // a preview is one module, no glow pass, no children
                    if (draw.atlasGlow()) {
                        // fullbright emissive pass from the glow atlas (the
                        // geo_glowing_layer recipe bound to the glow canvas)
                        getRenderer().reRender(model, ctx.poseStack, ctx.bufferSource, ctx.animatable,
                                GunTextureAtlas.GLOW, ctx.bufferSource.getBuffer(GunTextureAtlas.GLOW),
                                ctx.partialTick, GunGlowLayer.FULLBRIGHT, GunGlowLayer.NO_OVERLAY, 1, 1, 1, 1);
                    } else if (draw.legacyTexture() != null) {
                        // fullbright emissive pass for modules with a <name>_glowmask.png
                        GunGlowLayer.renderForModule(model, ctx.animatable, ctx.poseStack, ctx.bufferSource,
                                ctx.partialTick, draw.legacyTexture(), getRenderer());
                    }
                }
            }
            // child mounts (barrel -> muzzle, handguard -> attachments); their
            // locator lookup sees this module's animated bone state
            if (target.type == ModuleType.BARREL) {
                // muzzle device + anchor capture, both skipped at LOD range
                if (!lodActive) {
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
                    mount(model, "loc_muzzle_attachment", ctx.modules.get(ModuleType.MUZZLE), ctx, ghost);
                }
            } else if (target.type == ModuleType.HANDGUARD) {
                // rail attachments are invisible at LOD range
                if (!lodActive) {
                    for (Map.Entry<HandguardPosition, ModuleDefinition> e : ctx.hgAttachments.entrySet()) {
                        mount(model, e.getKey().locatorName(), e.getValue(), ctx, ghost);
                    }
                }
                // the ghost's own handguard position may be free (not in the
                // installed map), so walk it explicitly
                if (ghost != null && ghost.mountId().getPath().startsWith("loc_handguard_")) {
                    mount(model, ghost.mountId().getPath(), null, ctx, ghost);
                }
            }
        } finally {
            if (phys) CharmPhysics.restore();
            ctx.poseStack.popPose();
            if (hideBeam) beam.setHidden(false);
            if (saved != null) GunAnimations.restoreBones(saved);
        }
    }

    /** how a module's body draws: the shared runtime atlas when it fits, else the per-texture dye path */
    private record BodyDraw(RenderType type, boolean atlasGlow, @Nullable ResourceLocation legacyTexture) {}

    /**
     * Resolves a module body's render type and retargets the model's UVs to
     * match: the shared runtime atlas when the module fits it
     * (docs/gun-atlas-design.md), else the standalone per-texture path — the
     * dyed dynamic texture from {@link DyedTextures} when a companion
     * {@code <id>_dye.png} mask exists (plan_v2 配件染色).
     * The paint (涂装) id swaps the BASE texture before the dye colors
     * parameterize it; missing paint art renders the unpainted base —
     * the same lenient fallback the placeholder paths use.
     */
    private static BodyDraw bodyDraw(BakedGeoModel model, ItemStack stack, ModuleDefinition def,
                                     @Nullable ResourceLocation paintId, boolean translucent) {
        ResourceLocation texture = ModuleGunGeoModel.textureId(def.id);
        if (paintId != null) {
            ResourceLocation paintTex = PaintManager.texture(paintId, def.id);
            if (paintTex != null && Minecraft.getInstance().getResourceManager()
                    .getResource(paintTex).isPresent()) {
                texture = paintTex;
            }
        }
        int[] colors = GunNbt.getColors(stack, def.id);
        GunTextureAtlas.Slot slot = GunTextureAtlas.acquire(texture, colors);
        if (slot != null && GunTextureAtlas.retarget(model, slot)) {
            return new BodyDraw(translucent ? GunTextureAtlas.TRANSLUCENT : GunTextureAtlas.CUTOUT,
                    slot.glow(), null);
        }
        // legacy path: UVs back at the model original first (no-op when this
        // model was never rewritten)
        GunTextureAtlas.retarget(model, null);
        ResourceLocation dyed = DyedTextures.resolve(texture, colors);
        return new BodyDraw(translucent ? RenderType.entityTranslucent(dyed) : RenderType.entityCutoutNoCull(dyed),
                false, dyed);
    }

    /**
     * Hidden charm with a skin: the AW skin draws at the pendant bone's
     * animated position, then the chain and support cubes draw around it with
     * the pendant masked out — the skin is the only thing allowed back at its
     * spot. When the skin can't draw (AW absent, async bake window) the chain
     * hangs empty rather than the hidden pendant popping back in.
     */
    private void drawCharmSkin(BakedGeoModel model, ModuleDefinition def, CoreGeoBone pendant, Ctx ctx) {
        ctx.poseStack.pushPose();
        try {
            // the physics pass has already written the chain bone rotations,
            // so the pendant bone's transform (parented under the deepest
            // chain link, or directly under the body in a chainless charm)
            // carries the swing
            applyBoneChain(pendant, ctx.poseStack);
            AwCompat.renderModuleSkin(GunNbt.getSkin(ctx.stack, def.id), ctx.poseStack,
                    ctx.bufferSource, ctx.animId, ctx.partialTick, ctx.packedLight, ctx.packedOverlay,
                    animationsEnabled);
        } finally {
            ctx.poseStack.popPose();
        }
        // hidden-charm skin swap keeps the charm's own paint on the chain
        BodyDraw draw = bodyDraw(model, ctx.stack, def,
                GunNbt.getPaint(ctx.stack, def.id), false);
        pendant.setHidden(true);
        try {
            // chain + support only: same pass set the visible charm gets, so a
            // glowmasked chain keeps its emissive while the pendant is swapped
            getRenderer().reRender(model, ctx.poseStack, ctx.bufferSource, ctx.animatable, draw.type(),
                    ctx.bufferSource.getBuffer(draw.type()), ctx.partialTick, ctx.packedLight,
                    ctx.packedOverlay, 1, 1, 1, 1);
            if (draw.atlasGlow()) {
                getRenderer().reRender(model, ctx.poseStack, ctx.bufferSource, ctx.animatable,
                        GunTextureAtlas.GLOW, ctx.bufferSource.getBuffer(GunTextureAtlas.GLOW),
                        ctx.partialTick, GunGlowLayer.FULLBRIGHT, GunGlowLayer.NO_OVERLAY, 1, 1, 1, 1);
            } else if (draw.legacyTexture() != null) {
                GunGlowLayer.renderForModule(model, ctx.animatable, ctx.poseStack, ctx.bufferSource,
                        ctx.partialTick, draw.legacyTexture(), getRenderer());
            }
        } finally {
            pendant.setHidden(false);
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
     * Draws the module's AW skin inside the module's own frame: GeckoLib
     * renders a model under its top-level bones' local transforms, and the
     * author convention puts the whole module body under a top-level "main"
     * bone — so the skin has to ride that same transform, or an animation
     * driving main (recoil kick, tilt) tears the skin off the animated
     * model. At rest main transforms by nothing (prepMatrixForBone's pivot
     * terms cancel without rotation/scale), so rest-pose skins keep the
     * alignment they were authored against. Models without a top-level main
     * bone draw the skin right at the locator frame, as before.
     */
    private static boolean renderModuleSkinInModelFrame(BakedGeoModel model, CompoundTag skinTag, Ctx ctx) {
        boolean pushed = pushMainBoneFrame(model, ctx.poseStack);
        try {
            return AwCompat.renderModuleSkin(skinTag, ctx.poseStack, ctx.bufferSource, ctx.animId,
                    ctx.partialTick, ctx.packedLight, ctx.packedOverlay, animationsEnabled);
        } finally {
            if (pushed) ctx.poseStack.popPose();
        }
    }

    /**
     * Pushes the model's top-level "main" bone transform onto the stack when
     * the model has one. Returns whether a frame was pushed (the caller must
     * pop). Shared with the receiver skin pass (GunHandsAwareRenderer), which
     * draws under the same rule: the body renders inside this transform, so
     * the skin has to as well or a main-driven animation tears it off.
     */
    static boolean pushMainBoneFrame(BakedGeoModel model, PoseStack poseStack) {
        for (GeoBone b : model.topLevelBones()) {
            if (MAIN_BONE.equals(b.getName())) {
                poseStack.pushPose();
                RenderUtils.prepMatrixForBone(poseStack, b);
                return true;
            }
        }
        return false;
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
        Vector3f tip = new Vector3f(0, 0, 0);
        if (muzzleDef != null) {
            BakedGeoModel mm = null;
            try {
                mm = ModuleGunGeoModel.INSTANCE.getBakedModel(ModuleGunGeoModel.modelId(muzzleDef.id));
            } catch (Exception ignored) {}
            if (mm != null) {
                // preferred: the device marks its muzzle port with a
                // loc_muzzle bone (same convention as the barrel model).
                // Walk the device's own bone chain and capture exactly at
                // that pivot — no cube-space reconstruction involved.
                CoreGeoBone port = mm.getBone("loc_muzzle").orElse(null);
                if (port != null) {
                    poseStack.pushPose();
                    try {
                        applyBoneChain(port, poseStack);
                        MuzzleAnchor.capture(poseStack, new Vector3f(0, 0, 0),
                                mc().player.getUUID());
                    } finally {
                        poseStack.popPose();
                    }
                    return;
                }
                // fallback (no port bone): true front face from baked cube
                // vertices. Vertices are already block units (constructCube
                // divides origin/vertexSize by 16); the device extends along
                // -Z_world, so the front face is the MINIMUM z. Cube pivots
                // are parent-relative px (RenderUtils divides by 16 at draw
                // time), so sum the bone-chain pivots before adding them.
                float minZ = Float.POSITIVE_INFINITY;
                for (GeoBone bone : mm.topLevelBones()) {
                    minZ = Math.min(minZ, frontZ(bone, 0, 0, 0));
                }
                if (minZ != Float.POSITIVE_INFINITY) tip.set(0, 0, minZ);
            }
        }
        MuzzleAnchor.capture(poseStack, tip, mc().player.getUUID());
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /**
     * Minimum bone-space z over the bone's cubes and children (device front
     * face, block units). Bone pivots are parent-relative px; the running sum
     * is only converted to blocks at the leaf so integer px chains stay exact.
     */
    private static float frontZ(GeoBone bone, float px, float py, float pz) {
        float bx = px + bone.getPivotX(), by = py + bone.getPivotY(), bz = pz + bone.getPivotZ();
        float minZ = Float.POSITIVE_INFINITY;
        for (GeoCube cube : bone.getCubes()) {
            for (GeoQuad quad : cube.quads()) {
                for (GeoVertex v : quad.vertices()) {
                    // cube vertices are relative to the cube pivot: sum the
                    // px-space cube pivot too (RenderUtils /16 at draw time)
                    minZ = Math.min(minZ, (float) v.position().z + (float) cube.pivot().z / 16f);
                }
            }
        }
        for (GeoBone child : bone.getChildBones()) {
            minZ = Math.min(minZ, frontZ(child, bx, by, bz));
        }
        return minZ;
    }

    private static void driveAnimation(ModuleAnimatable ma, long instanceId, float partialTick) {
        AnimationState<ModuleAnimatable> state = new AnimationState<>(ma, 0, 0, partialTick, false);
        state.setData(DataTickets.TICK, ma.getTick(ma));
        ModuleGunGeoModel.INSTANCE.handleAnimations(ma, instanceId, state);
        // capture ONLY on the local player's first-person pass (the pose
        // they actually see); third-person, GUI and dropped passes don't
        // own the truth (see GunAnimations.captureLivePose)
        if (GunHandsLayer.isFirstPersonPass) {
            GunAnimations.captureLivePose("mod:" + ma.moduleId(), ModuleGunGeoModel.INSTANCE, instanceId);
        }
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
