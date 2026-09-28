package dev.ignis.createpneumatictacticals.client.render;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.GunWorkbenchBlock;
import dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.menu.WorkbenchAssembler;
import dev.ignis.createpneumatictacticals.module.HandguardPosition;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * <p>Marker kinds: MOUNT (a loc bone; free = [+], occupied = [-]), TAKE
 * ([▼], on the table itself) and INFO ([i], beside it — the stats plaque
 * switch). mountId semantics: receiver loc bone names ("loc_barrel", ...),
 * "loc_muzzle_attachment", or handguard position bones ("loc_handguard_top",
 * ...) — exactly what {@link WorkbenchAssembler} resolves server-side; TAKE
 * and INFO for the bench's own buttons.
 */
public final class WorkbenchOverlay {

    /** semantic id of the [▼] take marker */
    public static final ResourceLocation TAKE_ID = new ResourceLocation(CreatePneumaticTacticals.MODID, "take");
    /** semantic id of the [i] stats-plaque marker */
    public static final ResourceLocation INFO_ID = new ResourceLocation(CreatePneumaticTacticals.MODID, "info");
    /** semantic id of the muzzle-device mount (on the barrel) */
    public static final ResourceLocation MOUNT_MUZZLE_ATTACHMENT = new ResourceLocation(
            CreatePneumaticTacticals.MODID, "loc_muzzle_attachment");

    private static final ModuleType[] MOUNT_ORDER = {
            ModuleType.FEED, ModuleType.SUPPLY, ModuleType.SIGHT, ModuleType.TACTICAL_SIGHT,
            ModuleType.STOCK, ModuleType.BARREL, ModuleType.HANDGUARD, ModuleType.CHARM
    };

    /**
     * One marker. {@code gunPos} is the mount position in gun space (blocks,
     * px / 16, for the preview render frame) — meaningless for the [▼] take
     * button, which is pinned to the bench model instead of the gun.
     * {@code worldPos} is absolute (for picking/drawing). {@code installed}
     * names the module at an occupied mount (that id is what REMOVE sends);
     * null on [+] / [▼].
     */
    public record Marker(Vec3 worldPos, Vec3 gunPos, ResourceLocation mountId,
                         @Nullable ModuleDefinition installed) {
        public boolean occupied() {
            return installed != null;
        }
        public boolean isTake() {
            return TAKE_ID.equals(mountId);
        }
        /** the [i] button: cycles the stats plaque's always-on state */
        public boolean isInfo() {
            return INFO_ID.equals(mountId);
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

        // [▼] at a fixed spot on the table itself (not on the gun): model-space
        // +z 6/16, centre x, 18/16 above the block bottom, turned with the
        // blockstate so the button follows the bench's facing
        out.add(new Marker(takeButtonWorld(bench), Vec3.ZERO, TAKE_ID, null));
        // [i] on the same front edge, one block along it — the plaque switch
        out.add(new Marker(infoButtonWorld(bench), Vec3.ZERO, INFO_ID, null));

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
     * Button offsets from the block centre in model space (blocks): both ride
     * the table's front edge, model-space +z {@link #BUTTON_OFFSET_Z} — the
     * side the player stands on. [▼] is centred on the table; [i] sits half a
     * block along it on the model -x side (the stock end for a north-facing
     * bench), still over the tabletop (which spans ±12 px = ±0.75 blocks, so
     * ±0.5 keeps 4 px of table under the icon).
     */
    private static final double BUTTON_OFFSET_Z = -6.0 / 16.0;
    private static final double INFO_OFFSET_X = -8.0 / 16.0;
    /** button height above the block's bottom face (blocks) */
    private static final double BUTTON_HEIGHT = 18.0 / 16.0;

    /** [▼]: centred on the front edge */
    private static Vec3 takeButtonWorld(GunWorkbenchBlockEntity bench) {
        return buttonWorld(bench, 0.0, BUTTON_OFFSET_Z);
    }

    /** [i]: one block along the front edge from [▼] */
    private static Vec3 infoButtonWorld(GunWorkbenchBlockEntity bench) {
        return buttonWorld(bench, INFO_OFFSET_X, BUTTON_OFFSET_Z);
    }

    /**
     * World position of a marker button: fixed on the bench model, not on
     * the staged gun — model space (centre + (ox, oz),
     * {@link #BUTTON_HEIGHT} above the block bottom), turned by the
     * blockstate's y-rotation. Mirrors {@code GunWorkbenchBlock#rotateCube}:
     * north is as authored and every facing step turns the offset 90°, so a
     * button keeps the same place on the table however the bench is placed
     * (model +z ends up pointing away from the block's front face).
     */
    private static Vec3 buttonWorld(GunWorkbenchBlockEntity bench, double ox, double oz) {
        Direction facing = bench.getBlockState().getValue(GunWorkbenchBlock.FACING);
        double dx = switch (facing) {
            case EAST -> -oz;
            case WEST -> oz;
            case SOUTH -> -ox;
            default -> ox; // north
        };
        double dz = switch (facing) {
            case NORTH -> oz;
            case SOUTH -> -oz;
            case EAST -> ox;
            default -> -ox; // west
        };
        BlockPos pos = bench.getBlockPos();
        return new Vec3(pos.getX() + 0.5 + dx, pos.getY() + BUTTON_HEIGHT, pos.getZ() + 0.5 + dz);
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