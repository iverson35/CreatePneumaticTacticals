package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import dev.ignis.createpneumatictacticals.item.GunItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

import java.util.Map;

/**
 * Renders the staged gun upright on the bench and owns the geometry truth
 * (marker positions, gun-space bounds) for the whole 3D workbench UI.
 *
 * <p>Gun geo convention: +Z_model = muzzle direction, +Y_model = up on the
 * gun, pivots in px (/16 = blocks). Upright pose = rotate -90° about
 * X_world so the muzzle points up: a geo (x,y,z) maps to world
 * (x, z, -y). The gun's back face (min Z in gun space) is planted on the
 * bench surface.
 *
 * <p>One recursive "assemble walk" is shared by bounds computation and (in
 * {@link WorkbenchOverlay}) marker placement: receiver model first, then
 * each installed module at its loc bone, recursing into child mounts
 * (barrel -> muzzle device, handguard -> position attachments). Bounds and
 * markers therefore can never disagree with the rendered result.
 */
public class GunWorkbenchRenderer implements BlockEntityRenderer<GunWorkbenchBlockEntity> {

    /** cached bounds per gun stack (identity + module fingerprint) */
    /**
     * The install preview for this bench: hovered free mount + held module,
     * only when the same validation the server applies would accept it. The
     * ghost is drawn by GunModulesLayer through the installed modules' own
     * mount tree, so it inherits the locator chain (rotated rails included).
     */
    private static GunModulesLayer.Ghost previewGhost(GunWorkbenchBlockEntity bench) {
        BenchTargetPicker.Hover hover = BenchTargetPicker.currentHover();
        if (hover == null || !hover.benchPos().equals(bench.getBlockPos())) return null;
        WorkbenchOverlay.Marker m = hover.marker();
        if (m.occupied() || m.isTake()) return null;
        ItemStack held = hover.heldModule();
        dev.ignis.createpneumatictacticals.module.ModuleDefinition def =
                dev.ignis.createpneumatictacticals.module.ModuleManager.definitionOf(held);
        if (def == null) return null;
        if (WorkbenchMarkerRenderer.previewReject(bench, def, m) != null) return null;
        return new GunModulesLayer.Ghost(m.mountId(), held);
    }

    /**
     * Height (blocks) the staged gun's lowest point rests at: the workbench
     * model's tabletop top face (16 px = 1 block) plus 1 px of vise
     * clearance. {@link WorkbenchOverlay#gunToWorld} mirrors this and
     * {@link #BENCH_MUZZLE_NUDGE}.
     */
    static final float BENCH_REST_Y = 1f + 1f / 16f;

    /** How far the gun slides toward its muzzle (gun-space -Z) on the bench. */
    static final float BENCH_MUZZLE_NUDGE = 1f / 16f;

    private static final java.util.WeakHashMap<CompoundTag, float[]> BOUNDS_CACHE =
            new java.util.WeakHashMap<>();

    public GunWorkbenchRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(GunWorkbenchBlockEntity bench, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        ItemStack gun = bench.getGunSlot().getItem(0);
        if (!(gun.getItem() instanceof GunItem)) return;
        // The bench is a solid, light-blocking block, so the light stored AT
        // its own position is 0 — vanilla BEs (chests/signs) are non-occluding
        // and never hit this. Sample the air above the bench instead: the gun
        // and its markers live up there.
        packedLight = net.minecraft.client.renderer.LevelRenderer.getLightColor(
                bench.getLevel(), bench.getBlockPos().above());

        poseStack.pushPose();
        try {
            applyBenchPose(poseStack, gun, bench.getBlockState());
            // BER render path of the gun item: NONE context so no vanilla
            // display transform interferes; GunHandsAwareRenderer branches on
            // context and NONE falls through clean
            GunModulesLayer.Ghost ghost = previewGhost(bench);
            if (ghost != null) GunModulesLayer.GHOST.set(ghost);
            try {
                dev.ignis.createpneumatictacticals.client.render.GunHandsAwareRenderer
                        .renderStandalone(gun, poseStack, buffer, packedLight, packedOverlay);
            } finally {
                GunModulesLayer.GHOST.remove();
            }
        } finally {
            poseStack.popPose();
        }

        // markers + hover preview in a fresh world frame (no bench pose)
        poseStack.pushPose();
        try {
            WorkbenchMarkerRenderer.render(bench, poseStack, buffer, packedLight);
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Bench pose: center on the block, lift the bench top surface, rotate
     * the gun upright (muzzle up) and plant its back face on the surface.
     */
    static void applyBenchPose(PoseStack poseStack, ItemStack gun, BlockState state) {
        float[] bb = bounds(gun);
        float minY = bb != null ? bb[1] : 0f;
        poseStack.translate(0.5, BENCH_REST_Y, 0.5);
        // lying flat, long axis across the bench front: the gun's local -Z is
        // the muzzle, so benchYaw(state) puts the muzzle to the left of
        // whoever faces the bench. Local +Y (rail/sights) stays up.
        poseStack.mulPose(com.mojang.math.Axis.YP.rotation(benchYaw(state)));
        // plant the lowest gun-space point (grip/mag bottom) on the bench,
        // then slide it toward the muzzle (gun-space -Z, already yawed)
        poseStack.translate(0, -minY, -BENCH_MUZZLE_NUDGE);
    }

    /**
     * Fixed gun yaw from the bench's facing: muzzle to the LEFT of a viewer
     * standing in front of the bench (facing its front face), stock to their
     * right — independent of where the player actually stands. Mirrored by
     * {@link WorkbenchOverlay#gunToWorld}, so markers stay glued.
     *
     * <p>Viewer look = -facing; viewer's left = up x look = (lz, 0, -lx);
     * the gun's local muzzle is (0,0,-1) and
     * R_y(phi)*(0,0,-1) = (-sin phi, 0, -cos phi), so sin phi = -lz,
     * cos phi = lx.
     */
    static float benchYaw(BlockState state) {
        net.minecraft.core.Direction facing =
                state.hasProperty(dev.ignis.createpneumatictacticals.block.GunWorkbenchBlock.FACING)
                        ? state.getValue(dev.ignis.createpneumatictacticals.block.GunWorkbenchBlock.FACING)
                        : net.minecraft.core.Direction.NORTH;
        float lx = -facing.getStepX();
        float lz = -facing.getStepZ();
        return (float) Math.atan2(-lz, lx);
    }

    // ---------------------------------------------------------------
    // geometry truth: gun-space bounds of receiver + all installed modules
    // ---------------------------------------------------------------

    /** module fingerprint: ids + attachment ids, order-insensitive */
    private static String moduleFingerprint(ItemStack gun) {
        StringBuilder sb = new StringBuilder();
        GunNbt.readModules(gun).values().stream().map(m -> m.id.toString()).sorted()
                .forEach(s -> sb.append(s).append(';'));
        sb.append('|');
        GunNbt.readHandguardAttachments(gun).values().stream().map(m -> m.id.toString()).sorted()
                .forEach(s -> sb.append(s).append(';'));
        return sb.toString();
    }

    /** cache key: identity NBT + module fingerprint */
    private static CompoundTag cacheKey(ItemStack gun) {
        CompoundTag key = new CompoundTag();
        CompoundTag root = gun.getOrCreateTag();
        if (root.contains("Modules")) key.put("Modules", root.getCompound("Modules"));
        return key;
    }

    /**
     * Gun-space bounding box [minX,minY,minZ,maxX,maxY,maxZ] (blocks), or
     * null when nothing is measurable. Cached per module configuration.
     */
    public static float[] bounds(ItemStack gun) {
        if (!(gun.getItem() instanceof GunItem)) return null;
        CompoundTag key = cacheKey(gun);
        if (BOUNDS_CACHE.containsKey(key)) return BOUNDS_CACHE.get(key);
        float[] bb = computeBounds(gun);
        BOUNDS_CACHE.put(key, bb);
        return bb;
    }

    private static float[] computeBounds(ItemStack gun) {
        Map<ModuleType, ModuleDefinition> modules = GunNbt.readModules(gun);
        ModuleDefinition receiver = modules.get(ModuleType.RECEIVER);
        if (receiver == null) return null;
        BakedGeoModel model = baked(receiver.id);
        if (model == null) return null;
        Bounds b = new Bounds();
        walkModules(gun, model, 0, 0, 0, b);
        return b.valid ? new float[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ} : null;
    }

    /** receiver loc bone for a single-slot module type (null = child mount) */
    static String locatorOf(ModuleType type) {
        return switch (type) {
            case FEED -> "loc_feed";
            case SUPPLY -> "loc_supply";
            case SIGHT -> "loc_sight";
            case TACTICAL_SIGHT -> "loc_sight_side";
            case STOCK -> "loc_stock";
            case BARREL -> "loc_barrel";
            case HANDGUARD -> "loc_handguard";
            default -> null; // MUZZLE mounts on the barrel; HG_ATTACHMENT on the handguard
        };
    }

    /**
     * Recursive assemble walk: union receiver cubes at origin, then every
     * installed module at its mount position (blocks, gun space), recursing
     * into barrel->muzzle and handguard->attachment chains.
     */
    static void walkModules(ItemStack gun, BakedGeoModel receiverModel,
                            float ox, float oy, float oz, Bounds b) {
        Map<ModuleType, ModuleDefinition> modules = GunNbt.readModules(gun);
        Map<dev.ignis.createpneumatictacticals.module.HandguardPosition, ModuleDefinition> atts =
                GunNbt.readHandguardAttachments(gun);
        accumulateCubes(receiverModel, ox, oy, oz, b);

        for (Map.Entry<ModuleType, ModuleDefinition> e : modules.entrySet()) {
            if (e.getKey() == ModuleType.RECEIVER) continue;
            String loc = locatorOf(e.getKey());
            if (loc == null) continue;
            CoreGeoBone bone = receiverModel.getBone(loc).orElse(null);
            if (bone == null) continue;
            float mx = ox + bone.getPivotX() / 16f;
            float my = oy + bone.getPivotY() / 16f;
            float mz = oz + bone.getPivotZ() / 16f;
            BakedGeoModel m = baked(e.getValue().id);
            if (m == null) continue;
            accumulateCubes(m, mx, my, mz, b);

            // child mounts
            if (e.getKey() == ModuleType.BARREL) {
                ModuleDefinition muzzle = modules.get(ModuleType.MUZZLE);
                if (muzzle != null) {
                    CoreGeoBone port = m.getBone("loc_muzzle_attachment").orElse(null);
                    if (port != null) {
                        BakedGeoModel mm = baked(muzzle.id);
                        if (mm != null) {
                            accumulateCubes(mm,
                                    mx + port.getPivotX() / 16f,
                                    my + port.getPivotY() / 16f,
                                    mz + port.getPivotZ() / 16f, b);
                        }
                    }
                }
            } else if (e.getKey() == ModuleType.HANDGUARD) {
                for (Map.Entry<dev.ignis.createpneumatictacticals.module.HandguardPosition,
                        ModuleDefinition> att : atts.entrySet()) {
                    CoreGeoBone pos = m.getBone(att.getKey().locatorName()).orElse(null);
                    if (pos == null) continue;
                    BakedGeoModel am = baked(att.getValue().id);
                    if (am == null) continue;
                    accumulateCubes(am,
                            mx + pos.getPivotX() / 16f,
                            my + pos.getPivotY() / 16f,
                            mz + pos.getPivotZ() / 16f, b);
                }
            }
        }
    }

    /** union all cubes of the model (bones summed in px -> blocks) */
    private static void accumulateCubes(BakedGeoModel model, float ox, float oy, float oz, Bounds b) {
        for (software.bernie.geckolib.cache.object.GeoBone bone : model.topLevelBones()) {
            accumulateBone(bone, ox, oy, oz, b);
        }
    }

    private static void accumulateBone(software.bernie.geckolib.cache.object.GeoBone bone,
                                       float px, float py, float pz, Bounds b) {
        // the laser beam is an FX box reaching the beam's range (16+ blocks):
        // GunModulesLayer hides it on every non-world pass, so the measured
        // bounds must skip it too or the gun reads 16 blocks long
        if (GunModulesLayer.BEAM_BONE.equals(bone.getName())) return;
        float bx = px + bone.getPivotX() / 16f;
        float by = py + bone.getPivotY() / 16f;
        float bz = pz + bone.getPivotZ() / 16f;
        for (GeoCube cube : bone.getCubes()) {
            for (GeoQuad quad : cube.quads()) {
                for (GeoVertex v : quad.vertices()) {
                    Vector3f p = v.position();
                    // cube vertices are relative to the cube pivot (px);
                    // RenderUtils divides by 16 at draw time — do it here
                    float cx = bx + p.x + (float) cube.pivot().x / 16f;
                    float cy = by + p.y + (float) cube.pivot().y / 16f;
                    float cz = bz + p.z + (float) cube.pivot().z / 16f;
                    b.add(cx, cy, cz);
                }
            }
        }
        for (software.bernie.geckolib.cache.object.GeoBone child : bone.getChildBones()) {
            accumulateBone(child, bx, by, bz, b);
        }
    }

    static final class Bounds {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        boolean valid;
        void add(float x, float y, float z) {
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            valid = true;
        }
        float[] array() {
            return valid ? new float[]{minX, minY, minZ, maxX, maxY, maxZ} : null;
        }
    }

    /** baked model for a module id (geo/gun/<id>.geo.json), null when absent */
    static BakedGeoModel baked(ResourceLocation moduleId) {
        ResourceLocation modelId = ModuleGunGeoModel.modelId(moduleId);
        if (!software.bernie.geckolib.cache.GeckoLibCache.getBakedModels().containsKey(modelId)) {
            return null;
        }
        try {
            return ModuleGunGeoModel.INSTANCE.getBakedModel(modelId);
        } catch (Exception e) {
            return null;
        }
    }
}