package dev.ignis.createpneumatictacticals.compat.aw;

import com.mojang.blaze3d.vertex.PoseStack;
import moe.plushie.armourers_workshop.compatibility.client.AbstractBufferSource;
import moe.plushie.armourers_workshop.compatibility.client.AbstractPoseStack;
import moe.plushie.armourers_workshop.core.client.animation.AnimationManager;
import moe.plushie.armourers_workshop.core.client.bake.BakedSkin;
import moe.plushie.armourers_workshop.core.client.other.SkinRenderTesselator;
import moe.plushie.armourers_workshop.core.data.ticket.Tickets;
import moe.plushie.armourers_workshop.core.skin.SkinDescriptor;
import moe.plushie.armourers_workshop.core.utils.TickUtils;
import moe.plushie.armourers_workshop.init.ModItems;
import moe.plushie.armourers_workshop.compatibility.extensions.com.mojang.blaze3d.systems.RenderSystem.ModelView;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The AW-touching half of the item-skin integration — the ONLY class loaded
 * when {@link AwCompat#loaded()} is true; see the guard note there.
 *
 * <p>Render chain mirrors AW's own manual renders (ExtendedItemRenderer /
 * ClientWardrobeHandler item path): wrap the vanilla PoseStack and buffers,
 * bake via the INVENTORY ticket, and scale the block-unit pose into AW's
 * voxel space. Skin descriptor decode goes through AW's component codec via
 * a throwaway carrier stack, so the descriptor Compound is never parsed by
 * CPT (see plan §3.2).
 *
 * <p>Coordinate bridge: GunModulesLayer hands us the pose in GeckoLib block
 * units (+Y up, +X right, muzzle toward −Z — the same conventions as AW's
 * GUI item box). AW's own item renders bridge block units to voxels with
 * {@code scale(-1/16, -1/16, 1/16)}; the X/Y mirror lands AW's authoring
 * space (Blockbench Java-Block-Model-style, +Y down) on ours. If skins come
 * out mirrored or upside down in testing, the ONLY knobs are
 * {@link #VOXEL} and {@link #YAW_DEGREES}.
 */
final class AwSkins {

    /** block units -> AW voxel units, with AW's authoring-space mirror
     *  (net effect: X stays right, Y flips down->up, Z flips). This is
     *  exactly AW's own item-render bridge (ExtendedItemRenderer
     *  renderSkinInBox), so skins land with their authored facing —
     *  no extra yaw. */
    private static final float VOXEL = 1 / 16f;

    /** isolated per-gun animation managers: GeckoItem id -> manager. Never
     *  AnimationManager.of(player) — that broadcasts to every AW skin the
     *  player wears. Access-ordered LRU: gun ids are per-stack unique and
     *  never recycled within a session, so eviction keeps the table
     *  bounded (a gun's animations restart if it re-enters after 32
     *  others — a non-issue for held guns). */
    private static final int MAX_GUNS = 32;
    private static final Map<Long, AnimationManager> MANAGERS = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, AnimationManager> eldest) {
            return size() > MAX_GUNS;
        }
    };


    private AwSkins() {}

    // --- descriptor decode (AW codec, never hand-parsed by CPT) ---

    /**
     * Decodes the verbatim descriptor Compound via AW's own component path
     * (1.20.1 {@code AbstractDataComponentType.tj16}: tag "ArmourersWorkshop"
     * -> SkinDescriptor codec, cached through ItemStackStorage). Null when
     * the tag doesn't hold a usable descriptor.
     */
    private static SkinDescriptor decode(CompoundTag descriptorTag) {
        ItemStack carrier = new ItemStack(ModItems.SKIN.get());
        carrier.getOrCreateTag().put(AwCompat.TAG_AW_SKIN, descriptorTag);
        SkinDescriptor descriptor = SkinDescriptor.of(carrier);
        return descriptor.isEmpty() ? null : descriptor;
    }

    // --- render ---

    /**
     * Draws the skin at the caller's pose. False when the descriptor is
     * unusable or the bake isn't ready yet (async window) — the caller falls
     * back to the GeckoLib module model for that frame.
     */
    static boolean render(CompoundTag descriptorTag, PoseStack poseStack, MultiBufferSource bufferSource,
                          long gunId, float partialTick, int packedLight, int packedOverlay) {
        SkinDescriptor descriptor = decode(descriptorTag);
        if (descriptor == null) return false;
        // INVENTORY ticket = same lifetime class as a held item skin; the
        // bakery returns null while the async bake is in flight.
        SkinRenderTesselator tesselator = SkinRenderTesselator.create(descriptor, Tickets.INVENTORY);
        if (tesselator == null) return false;
        BakedSkin bakedSkin = tesselator.getSkin();
        if (bakedSkin == null) return false;

        var awPose = AbstractPoseStack.wrap(poseStack);
        var awBuffers = AbstractBufferSource.wrap(bufferSource);
        var mannequin = tesselator.getMannequin();

        awPose.pushPose();
        try {
            // bridge: caller's block units -> AW voxel space (see VOXEL doc)
            awPose.scale(-VOXEL, -VOXEL, VOXEL);

            tesselator.setPoseStack(awPose);
            tesselator.setBufferSource(awBuffers);
            tesselator.setLightmap(packedLight);
            tesselator.setOverlay(packedOverlay);
            tesselator.setPartialTicks(partialTick);
            tesselator.setAnimationTicks(TickUtils.animationTicks());
            tesselator.setModelViewStack(ModelView.getExtendedModelViewStack(ModelView.class));
            tesselator.setColorScheme(descriptor.getPaintScheme());
            tesselator.setItemSource(moe.plushie.armourers_workshop.core.client.other.SkinItemSource.create(
                    descriptor.sharedItemStack()));
            tesselator.setUseItemTransforms(false);
            tesselator.setOutlineColor(0);

            AnimationManager manager = MANAGERS.get(gunId);
            if (manager != null) {
                contextBind(manager, descriptor, bakedSkin);
                tesselator.setAnimationManager(manager);
            }
            tesselator.draw();
        } finally {
            awPose.popPose();
        }
        return true;
    }

    // --- animation bridge (per-gun, isolated) ---

    /** keeps the manager's skin table in step with what this gun carries */
    private static void contextBind(AnimationManager manager, SkinDescriptor descriptor, BakedSkin bakedSkin) {
        Map<SkinDescriptor, BakedSkin> skins = Map.of(descriptor, bakedSkin);
        manager.load(skins);
        manager.active(skins);
    }


    /**
     * Per-frame sample advance (call before render, once per gun). Creates
     * the gun's isolated manager on first touch — this is the only
     * creation point, so a gun whose skin never renders (AW absent) never
     * allocates one, and every other entry point (render/play/stop) can
     * rely on the manager already existing.
     */
    static void tick(CompoundTag descriptorTag, long gunId, float partialTick) {
        AnimationManager manager = MANAGERS.get(gunId);
        SkinDescriptor descriptor = decode(descriptorTag);
        if (descriptor == null) return;
        SkinRenderTesselator tesselator = SkinRenderTesselator.create(descriptor, Tickets.INVENTORY);
        if (tesselator == null) return;
        if (manager == null) manager = managerFor(gunId); // bounded LRU (MANAGERS)
        contextBind(manager, descriptor, tesselator.getSkin());
        manager.tick(tesselator.getMannequin(), TickUtils.animationTicks());
    }

    /** broadcast: plays the named animation on every skin of this gun that
     *  defines it (play() no-ops on skins lacking the controller). */
    static void onGunAnimation(long gunId, String name, double speed) {
        AnimationManager manager = MANAGERS.get(gunId);
        if (manager == null) return;
        var tag = new CompoundTag();
        tag.putFloat("speed", (float) speed);
        manager.play(name, TickUtils.animationTicks(), tag);
    }

    static void interrupt(long gunId) {
        AnimationManager manager = MANAGERS.get(gunId);
        if (manager != null) manager.stop(""); // empty name stops all + clears replay queue
    }

    static void release(long gunId) {
        AnimationManager manager = MANAGERS.remove(gunId);
        if (manager != null) manager.stop("");
    }

    /** gets (or lazily creates) the gun's isolated manager */
    static AnimationManager managerFor(long gunId) {
        return MANAGERS.computeIfAbsent(gunId, id -> new AnimationManager((Object) null));
    }
}