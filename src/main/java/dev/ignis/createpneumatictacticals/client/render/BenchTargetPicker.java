package dev.ignis.createpneumatictacticals.client.render;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.GunWorkbenchBlock;
import dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.Workbench3dPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side picking for the 3D workbench: which marker the crosshair is
 * over, and what a right click should do about it.
 *
 * <p>Markers float in the air above the bench, so the vanilla block ray
 * ({@code mc.hitResult}) often lands on whatever is BEHIND the marker —
 * picking cannot gate on the ray hitting the bench block. Instead the BER
 * collects every visible bench ({@link #onBenchRendered}), and at the
 * AFTER_BLOCK_ENTITIES stage the eye ray is tested against all collected
 * markers ({@link #frameEnd}). Clicks use the hover state from the last
 * frame.
 *
 * <p>Vanilla repeats the use key while held (every 4 ticks); a send
 * debounce keeps a held click from machine-gunning packets.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class BenchTargetPicker {

    /** pick window around a marker centre (blocks, measured perpendicular to
     *  the eye ray): the drawn icon's own radius (0.035..0.085) plus slack, so
     *  "point at the icon" stays workable at any distance */
    private static final double PICK_RADIUS = 0.10;
    /** perpendicular offsets this close (blocks) count as the same line of
     *  sight — the nearer icon then wins the tie */
    private static final double PICK_TIE = 0.01;
    /** interaction reach (blocks) */
    private static final double REACH = 4.5;
    /** ms between packet sends while the use key is held */
    private static final long SEND_DEBOUNCE_MS = 250;

    /** the hovered marker, its bench and the held module that would install */
    public record Hover(WorkbenchOverlay.Marker marker, BlockPos benchPos,
                       ItemStack heldModule) {}

    /** benches rendered this frame (cleared at frame end) */
    private static final List<GunWorkbenchBlockEntity> rendered = new ArrayList<>();
    /** hover as computed at the last frame end */
    private static @Nullable Hover hover = null;
    private static long lastSendMs = 0;

    private BenchTargetPicker() {}

    // ---------------------------------------------------------------
    // frame cycle
    // ---------------------------------------------------------------

    /** the BER registers its bench during its render pass */
    static void onBenchRendered(GunWorkbenchBlockEntity bench) {
        rendered.add(bench);
    }

    /** AFTER_BLOCK_ENTITIES: all benches known — pick hover, then clear */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        updateHover();
        rendered.clear();
    }

    // ---------------------------------------------------------------
    // hover
    // ---------------------------------------------------------------

    /**
     * true when this marker is the hovered one (marker highlight tint + slot
     * label). The bench has to match too: mount ids repeat across benches
     * (every bench has its own "take" and "loc_receiver"), so matching on the
     * id alone lit up the same marker on every bench in view.
     */
    public static boolean isHovered(GunWorkbenchBlockEntity bench, WorkbenchOverlay.Marker m) {
        return hover != null && hover.benchPos().equals(bench.getBlockPos())
                && hover.marker().mountId().equals(m.mountId());
    }

    public static @Nullable Hover currentHover() {
        return hover;
    }

    /** true while a marker is hovered — AimHandler suppresses aiming then */
    public static boolean wouldInteract() {
        return hover != null;
    }

    /**
     * Pick: of every visible marker within {@link #PICK_RADIUS} of the eye ray,
     * the one whose centre sits <b>closest to the ray</b> (smallest
     * perpendicular offset) wins — not the nearest along it. With several
     * markers packed together on one bench, what the crosshair is actually on
     * has to decide; distance along the ray only breaks ties between markers
     * on the same line of sight ({@link #PICK_TIE}).
     *
     * <p>Perpendicular-offset ordering is the angular (screen-space) ordering:
     * the on-screen distance from the crosshair is {@code focal * offset/along},
     * and {@code along} is the marker's distance — so this needs no projection,
     * and the {@link #PICK_RADIUS} window scales with distance exactly like the
     * drawn icon does.
     */
    private static void updateHover() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || rendered.isEmpty()) {
            hover = null;
            return;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 dir = mc.player.getViewVector(1f).normalize();
        Hover best = null;
        double bestOffset = Double.MAX_VALUE;
        double bestAlong = Double.MAX_VALUE;
        for (GunWorkbenchBlockEntity bench : rendered) {
            if (!WorkbenchOverlay.uiInRange(bench.getBlockPos())) continue;
            for (WorkbenchOverlay.Marker m : WorkbenchOverlay.markers(bench)) {
                // hidden markers are not pickable; the candidate resolved here is
                // exactly what a click would install (no second validation)
                ItemStack candidate = installCandidate(bench, m);
                if (!visible(m, candidate)) continue;
                Vec3 toMarker = m.worldPos().subtract(eye);
                double along = toMarker.dot(dir);
                if (along < 0.3 || along > REACH) continue; // behind camera / out of reach
                double offset = toMarker.subtract(dir.scale(along)).length();
                if (offset > PICK_RADIUS) continue;
                boolean better = offset < bestOffset - PICK_TIE
                        || (Math.abs(offset - bestOffset) <= PICK_TIE && along < bestAlong);
                if (better) {
                    bestOffset = offset;
                    bestAlong = along;
                    best = new Hover(m, bench.getBlockPos(), candidate);
                }
            }
        }
        hover = best;
    }

    /**
     * The module that would install at a free mount: the main hand first, then
     * the off hand, and only from a hand whose module passes the same
     * validation the server applies on INSTALL — so the [+] marker, the ghost
     * preview and the install click always name the same stack (and hand).
     */
    private static ItemStack installCandidate(GunWorkbenchBlockEntity bench, WorkbenchOverlay.Marker m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || m.occupied() || m.isTake() || m.isInfo()) return ItemStack.EMPTY;
        ItemStack main = mc.player.getMainHandItem();
        ModuleDefinition mainDef = ModuleManager.definitionOf(main);
        if (mainDef != null && WorkbenchMarkerRenderer.previewReject(bench, mainDef, m) == null) return main;
        ItemStack off = mc.player.getOffhandItem();
        ModuleDefinition offDef = ModuleManager.definitionOf(off);
        if (offDef != null && WorkbenchMarkerRenderer.previewReject(bench, offDef, m) == null) return off;
        return ItemStack.EMPTY;
    }

    /**
     * Marker visibility — the renderer skips drawing and the picker skips
     * picking, so a hidden marker can never be hovered: [+] only while the
     * held candidate would install at that mount, [-] only with an empty
     * acting (main) hand, [▼] and [i] always.
     */
    private static boolean visible(WorkbenchOverlay.Marker m, ItemStack candidate) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (m.isTake() || m.isInfo()) return true;
        if (m.occupied()) return mc.player.getMainHandItem().isEmpty();
        return !candidate.isEmpty();
    }

    /** {@link #visible} with the candidate resolved here (renderer entry point) */
    public static boolean isVisible(GunWorkbenchBlockEntity bench, WorkbenchOverlay.Marker m) {
        return visible(m, installCandidate(bench, m));
    }

    // ---------------------------------------------------------------
    // right-click dispatch (AimHandler forwards the use key here)
    // ---------------------------------------------------------------

    /**
     * The use key was pressed. Refreshes hover, then sends the matching
     * Workbench3dPacket. True when the click was consumed by the 3D
     * flow (AimHandler then cancels the vanilla click).
     */
    public static boolean sendRightClick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        long now = System.currentTimeMillis();
        if (now - lastSendMs < SEND_DEBOUNCE_MS) return true; // held: stay swallowed
        lastSendMs = now;

        if (hover != null) {
            WorkbenchOverlay.Marker m = hover.marker();
            BlockPos pos = hover.benchPos();
            if (m.isTake()) {
                if (!mc.player.getMainHandItem().isEmpty()) {
                    return false; // take needs an empty main hand
                }
                CptNetwork.CHANNEL.sendToServer(new Workbench3dPacket(
                        Workbench3dPacket.Action.TAKE, pos, false, null));
                return true;
            }
            if (m.isInfo()) {
                // [i]: cycles the bench's stats plaque. Empty acting hand, like
                // [▼] — the button is a bench setting, not an item interaction
                if (!mc.player.getMainHandItem().isEmpty()) {
                    return false;
                }
                CptNetwork.CHANNEL.sendToServer(new Workbench3dPacket(
                        Workbench3dPacket.Action.CYCLE_INFO, pos, false, null));
                return true;
            }
            if (m.occupied()) {
                // [-]: no in-place swap — the acting (main) hand must be empty
                if (!mc.player.getMainHandItem().isEmpty()) {
                    return false;
                }
                CptNetwork.CHANNEL.sendToServer(new Workbench3dPacket(
                        Workbench3dPacket.Action.REMOVE, pos, false, m.installed().id));
                return true;
            }
            // [+]: the hover's candidate — validated when the hover was picked
            ItemStack held = hover.heldModule();
            if (!held.isEmpty()) {
                boolean offhand = held == mc.player.getOffhandItem();
                CptNetwork.CHANNEL.sendToServer(new Workbench3dPacket(
                        Workbench3dPacket.Action.INSTALL, pos, offhand, m.mountId()));
                return true;
            }
            return false;
        }

        // no marker hovered: clicking the bench block itself — staging a
        // gun/receiver (either hand), or taking the staged gun with an empty
        // acting hand (same packet the [▼] marker sends)
        if (!(mc.hitResult instanceof BlockHitResult hit)) return false;
        BlockPos pos = hit.getBlockPos();
        if (!(mc.level != null
                && mc.level.getBlockState(pos).getBlock() instanceof GunWorkbenchBlock)) {
            return false;
        }
        // staging is part of the 3D UI: dead past UI_RANGE
        if (!WorkbenchOverlay.uiInRange(pos)) return false;
        ItemStack main = mc.player.getMainHandItem();
        ModuleDefinition mainDef = ModuleManager.definitionOf(main);
        if (main.getItem() instanceof GunItem
                || (mainDef != null && mainDef.type == ModuleType.RECEIVER)) {
            CptNetwork.CHANNEL.sendToServer(new Workbench3dPacket(
                    Workbench3dPacket.Action.STAGE, pos, false, null));
            return true;
        }
        ItemStack off = mc.player.getOffhandItem();
        ModuleDefinition offDef = ModuleManager.definitionOf(off);
        if (off.getItem() instanceof GunItem
                || (offDef != null && offDef.type == ModuleType.RECEIVER)) {
            CptNetwork.CHANNEL.sendToServer(new Workbench3dPacket(
                    Workbench3dPacket.Action.STAGE, pos, true, null));
            return true;
        }
        // empty acting hand on the bench = the [▼] take click: same packet,
        // same server path, same "main hand must be empty" rule. Gated on a gun
        // actually staged, so an empty bench falls through to the block's
        // harmless no-op use instead of swallowing the click.
        if (main.isEmpty()
                && mc.level.getBlockEntity(pos) instanceof GunWorkbenchBlockEntity bench
                && !bench.getGunSlot().getItem(0).isEmpty()) {
            CptNetwork.CHANNEL.sendToServer(new Workbench3dPacket(
                    Workbench3dPacket.Action.TAKE, pos, false, null));
            return true;
        }
        return false;
    }
}