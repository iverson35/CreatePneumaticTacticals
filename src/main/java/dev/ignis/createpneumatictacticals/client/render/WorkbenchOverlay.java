package dev.ignis.createpneumatictacticals.client.render;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.menu.WorkbenchAssembler;
import dev.ignis.createpneumatictacticals.module.HandguardPosition;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Marker data model of the 3D workbench: where every [+] / [-] / [▼]
 * marker sits. Pure geometry — no rendering, no picking.
 *
 * <p>Bench pose recap (owned by {@link GunWorkbenchRenderer#applyBenchPose},
 * mirrored by {@link #gunToWorld}): translate to block center at bench-top
 * height, yaw across the viewer's line of sight (muzzle to their left,
 * stock to their right), then lift so the gun's lowest point (gun-space
 * minY) sits on the bench surface.
 *
 * <p>Marker kinds: MOUNT (a loc bone; free = [+], occupied = [-]) and TAKE
 * ([▼], at the gun's bounds center). mountId semantics: receiver loc bone
 * names ("loc_barrel", ...), "loc_muzzle_attachment", or handguard position
 * bones ("loc_handguard_top", ...) — exactly what
 * {@link WorkbenchAssembler} resolves server-side; TAKE for the gun.
 */
public final class WorkbenchOverlay {

    /** semantic id of the [▼] take marker */
    public static final ResourceLocation TAKE_ID = new ResourceLocation(CreatePneumaticTacticals.MODID, "take");
    /** semantic id of the muzzle-device mount (on the barrel) */
    public static final ResourceLocation MOUNT_MUZZLE_ATTACHMENT = new ResourceLocation(
            CreatePneumaticTacticals.MODID, "loc_muzzle_attachment");

    private static final ModuleType[] MOUNT_ORDER = {
            ModuleType.FEED, ModuleType.SUPPLY, ModuleType.SIGHT, ModuleType.TACTICAL_SIGHT,
            ModuleType.STOCK, ModuleType.BARREL, ModuleType.HANDGUARD, ModuleType.CHARM
    };

    /**
     * One marker. {@code gunPos} is the mount position in gun space (blocks,
     * for the preview render frame); {@code worldPos} is absolute (for
     * picking/drawing). {@code installed} names the module at an occupied
     * mount (that id is what REMOVE sends); null on [+] / [▼].
     */
    public record Marker(Vec3 worldPos, Vec3 gunPos, ResourceLocation mountId,
                         @Nullable ModuleDefinition installed) {
        public boolean occupied() {
            return installed != null;
        }
        public boolean isTake() {
            return TAKE_ID.equals(mountId);
        }
    }

    /**
     * Eye distance (blocks) at which a bench's 3D UI (markers, hover,
     * previews) still exists. Farther out the bench renders only the gun.
     */
    static final double UI_RANGE = 4.0;

    /**
     * True while the player's eye is close enough for the bench's 3D UI.
     * One distance check per bench per frame — negligible.
     */
    static boolean uiInRange(BlockPos benchPos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        return eye.distanceToSqr(benchPos.getX() + 0.5, benchPos.getY() + 0.5, benchPos.getZ() + 0.5)
                <= UI_RANGE * UI_RANGE;
    }

    private WorkbenchOverlay() {}

    /** all markers of the staged gun (empty when nothing is staged) */
    public static List<Marker> markers(GunWorkbenchBlockEntity bench) {
        List<Marker> out = new ArrayList<>();
        ItemStack gun = bench.getGunSlot().getItem(0);
        if (!(gun.getItem() instanceof GunItem)) return out;
        Map<ModuleType, ModuleDefinition> modules = GunNbt.readModules(gun);
        ModuleDefinition receiver = modules.get(ModuleType.RECEIVER);
        if (receiver == null) return out;
        BakedGeoModel model = GunWorkbenchRenderer.baked(receiver.id);
        if (model == null) return out;

        // [▼] at the gun's bounds center
        float[] bb = GunWorkbenchRenderer.bounds(gun);
        if (bb != null) {
            Vec3 gunPos = new Vec3((bb[0] + bb[3]) / 2f, (bb[1] + bb[4]) / 2f, (bb[2] + bb[5]) / 2f);
            out.add(new Marker(gunToWorld(bench, gun, gunPos), gunPos, TAKE_ID, null));
        }

        // receiver single-slot mounts
        for (ModuleType type : MOUNT_ORDER) {
            String loc = GunWorkbenchRenderer.locatorOf(type);
            if (loc == null) continue;
            CoreGeoBone bone = model.getBone(loc).orElse(null);
            if (bone == null) continue;
            Vec3 gunPos = pivot(bone);
            out.add(new Marker(gunToWorld(bench, gun, gunPos), gunPos,
                    mountId(loc), modules.get(type)));
        }

        // muzzle device port on the barrel
        ModuleDefinition barrel = modules.get(ModuleType.BARREL);
        if (barrel != null) {
            BakedGeoModel barrelModel = GunWorkbenchRenderer.baked(barrel.id);
            CoreGeoBone barrelLoc = model.getBone("loc_barrel").orElse(null);
            CoreGeoBone port = barrelModel != null
                    ? barrelModel.getBone("loc_muzzle_attachment").orElse(null) : null;
            if (barrelLoc != null && port != null) {
                Vec3 gunPos = pivot(barrelLoc).add(pivot(port));
                out.add(new Marker(gunToWorld(bench, gun, gunPos), gunPos,
                        MOUNT_MUZZLE_ATTACHMENT, modules.get(ModuleType.MUZZLE)));
            }
        }

        // handguard attachment positions
        ModuleDefinition handguard = modules.get(ModuleType.HANDGUARD);
        if (handguard != null) {
            BakedGeoModel hgModel = GunWorkbenchRenderer.baked(handguard.id);
            CoreGeoBone hgLoc = model.getBone("loc_handguard").orElse(null);
            if (hgModel != null && hgLoc != null) {
                for (HandguardPosition pos : HandguardPosition.values()) {
                    if (!handguard.attachmentPoints.contains(pos)) continue;
                    CoreGeoBone bone = hgModel.getBone(pos.locatorName()).orElse(null);
                    if (bone == null) continue;
                    Vec3 gunPos = pivot(hgLoc).add(pivot(bone));
                    out.add(new Marker(gunToWorld(bench, gun, gunPos), gunPos,
                            WorkbenchAssembler.mountBoneOf(pos),
                            GunNbt.readHandguardAttachments(gun).get(pos)));
                }
            }
        }
        return out;
    }

    static ResourceLocation mountId(String boneName) {
        return new ResourceLocation(CreatePneumaticTacticals.MODID, boneName);
    }

    /** bone pivot in gun-space blocks (px /16) */
    private static Vec3 pivot(CoreGeoBone bone) {
        return new Vec3(bone.getPivotX() / 16f, bone.getPivotY() / 16f, bone.getPivotZ() / 16f);
    }

    /**
     * gun-space -> world. Mirrors applyBenchPose exactly:
     * translate(0.5, 1, 0.5) + yaw(benchYaw()) + lift -minY along local Y
     * (the yaw keeps local Y = world Y).
     */
    static Vec3 gunToWorld(GunWorkbenchBlockEntity bench, ItemStack gun, Vec3 gunPos) {
        float[] bb = GunWorkbenchRenderer.bounds(gun);
        float minY = bb != null ? bb[1] : 0f;
        float phi = GunWorkbenchRenderer.benchYaw(bench.getBlockState());
        float cos = (float) Math.cos(phi);
        float sin = (float) Math.sin(phi);
        // pivot-relative, after the same lift + muzzle nudge as the pose
        float gx = (float) gunPos.x;
        float gy = (float) (gunPos.y - minY);
        float gz = (float) (gunPos.z - GunWorkbenchRenderer.BENCH_MUZZLE_NUDGE);
        // R_y(phi): x' = x cos + z sin, z' = -x sin + z cos
        float x = (float) (gx * cos + gz * sin);
        float z = (float) (-gx * sin + gz * cos);
        float y = gy;
        return new Vec3(
                bench.getBlockPos().getX() + 0.5 + x,
                bench.getBlockPos().getY() + GunWorkbenchRenderer.BENCH_REST_Y + y,
                bench.getBlockPos().getZ() + 0.5 + z);
    }
}