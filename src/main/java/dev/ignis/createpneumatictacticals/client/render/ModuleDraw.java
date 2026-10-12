package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

import java.util.Map;

/**
 * Shared drawing of a standalone module stack (not on a gun): the tuning table
 * lays one out, the machine tool machines one. Both paths need the same three
 * protections, so they live here instead of in each renderer:
 * <ul>
 *   <li>the baked model is SHARED with the on-gun pass (same geo path = same
 *       GeckoLib cache entry): draw at rest, then restore — restoring, not
 *       re-resetting, keeps the same-frame world render of the gun intact;</li>
 *   <li>that same model may have its UVs rewritten to atlas slot coordinates
 *       by a gun render while this pass binds the standalone texture
 *       ({@link GunTextureAtlas#retarget});</li>
 *   <li>the module's own dye/paint must be resolved per stack
 *       ({@link ModuleGeoModel#setStack}).</li>
 * </ul>
 */
public final class ModuleDraw {

    /**
     * Every module model's world-space FX bone (laser lines, author
     * convention). Only a firing gun has a beam to point somewhere, so every
     * display pass hides it: the item pass (icon, hand, frame, dropped) and
     * {@link #draw} (pegboard hang, bench, machine). {@code ModelBounds} also
     * skips it — its cube stretches to the beam's range, which would otherwise
     * blow up any measured module size.
     */
    public static final String BEAM_BONE = "laser_beam";

    private static final ModuleItemRenderer RENDERER = new ModuleItemRenderer();

    private ModuleDraw() {
    }

    /** the shared per-stack model (its texture/animation resources come from the stack) */
    public static ModuleGeoModel geoModel() {
        return (ModuleGeoModel) RENDERER.getGeoModel();
    }

    /** draw the module's model at rest in the caller's pose (the caller places and orients it) */
    public static void draw(ItemStack stack, PoseStack poseStack, MultiBufferSource buffer,
                            float partialTick, int packedLight, int packedOverlay) {
        draw(stack, poseStack, buffer, partialTick, packedLight, packedOverlay, false);
    }

    /**
     * {@code ghost} draws the module translucent in the shared preview colour
     * ({@link GhostPreview}) instead of its own textures.
     */
    public static void draw(ItemStack stack, PoseStack poseStack, MultiBufferSource buffer,
                            float partialTick, int packedLight, int packedOverlay, boolean ghost) {
        if (!(stack.getItem() instanceof ModuleItem moduleItem)) return;
        ModuleGeoModel model = geoModel();
        model.setStack(stack);
        BakedGeoModel baked = model.getBakedModel(model.getModelResource(moduleItem));
        Map<CoreGeoBone, float[]> saved = GunAnimations.snapshotBones(model);
        GunAnimations.resetToRestPose(model);
        // displayed modules are never firing: hide the laser FX bone (the
        // snapshot above only covers bone transforms, so undo it by hand)
        CoreGeoBone beam = model.getAnimationProcessor().getBone(BEAM_BONE);
        boolean beamWasHidden = beam != null && beam.isHidden();
        if (beam != null) beam.setHidden(true);
        try {
            ResourceLocation texture = model.getTextureResource(moduleItem);
            GunTextureAtlas.retarget(baked, null);
            RenderType type = ghost ? RenderType.entityTranslucent(texture)
                    : RenderType.entityCutoutNoCull(texture);
            // reRender (isReRender = true) skips handleAnimations and the item
            // centring translate, exactly like the bench's staged gun
            RENDERER.reRender(baked, poseStack, buffer, moduleItem, type, buffer.getBuffer(type),
                    partialTick, packedLight, packedOverlay,
                    ghost ? GhostPreview.R : 1f, ghost ? GhostPreview.G : 1f,
                    ghost ? GhostPreview.B : 1f, ghost ? GhostPreview.A : 1f);
        } finally {
            if (beam != null) beam.setHidden(beamWasHidden);
            GunAnimations.restoreBones(saved);
        }
    }

    /**
     * Draw {@code stack} exactly the way its inventory icon is drawn: the item
     * pass owns the fitted 3/4 view, hides the laser beam bone (world-space FX,
     * not part of the silhouette) and stands bottom-only attachments upright, so
     * callers that need the icon's look — the machine's monitor — get all of it
     * without duplicating any of it.
     *
     * <p>The pose must be at vanilla's item space: the pass assumes the outer
     * {@code translate(-0.5, -0.5, -0.5)} {@code ItemRenderer} applies around
     * {@code renderByItem} (see {@link ItemGuiTransform}), so a pose carrying
     * exactly that translate ends up with the icon centred on its origin.
     */
    public static void drawIcon(ItemStack stack, PoseStack poseStack, MultiBufferSource buffer,
                                int packedLight, int packedOverlay) {
        RENDERER.renderByItem(stack, ItemDisplayContext.GUI, poseStack, buffer, packedLight, packedOverlay);
    }

    /**
     * Z-180 model flip {@code (x, y, z) -> (-x, -y, z)} applied to an AABB.
     * Bottom-only parts are modelled rolled 180° and drawn upright by
     * {@code ModuleItemRenderer#renderByItem}, so every caller that measures the
     * model itself — rather than going through the item pass — has to measure
     * the flipped pose.
     */
    public static float[] flipZ(float[] bounds) {
        return new float[]{-bounds[3], -bounds[4], bounds[2], -bounds[0], -bounds[1], bounds[5]};
    }
}
