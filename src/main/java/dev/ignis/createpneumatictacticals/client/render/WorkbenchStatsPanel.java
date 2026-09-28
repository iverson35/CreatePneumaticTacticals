package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity;
import dev.ignis.createpneumatictacticals.client.RecoilPreview;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.GunItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Stats plaque floating just off the workbench side that faces the player.
 * Two triggers: the crosshair on the bench's [▼] take marker with a gun
 * staged (while the plaque state is OPEN), or the bench's [i] state, which
 * keeps it up regardless of where the player looks.
 *
 * <p>Two shapes. Default: the recoil chart next to a single column (the core
 * multipliers + the Shift hint). With Shift held (hover trigger) or while the
 * state is CLOSE: no chart, and the full spec split into two columns.
 *
 * <p>Drawn inside the bench's own BER frame, so the plaque is part of the
 * world: it picks the horizontal side the camera is on and reads straight-on
 * from there. The yaw comes from the side vector, not from {@code
 * Direction.toYRot()}: +Z has to be the outward normal and +X the viewer's
 * right for every side, and the blockstate yaw convention mirrors on
 * east/west.
 *
 * <p>Layout is measured in font pixels and cached per (gun NBT, Shift): only
 * the draw runs per frame.
 */
final class WorkbenchStatsPanel {

    // --- px geometry (font pixels; PX converts to blocks) ---
    private static final float PX = 1f / 200f;
    private static final int PAD = 6;
    /** panel line height (the font's 9px line plus a little air) */
    private static final int LINE_H = 11;
    private static final int COL_GAP = 10;
    /** chart box height in px — the vertical axis spans MAX_VERTICAL_DEGREES */
    private static final int CHART_H = 100;
    /** widest the chart may get (a wider pattern shrinks instead) */
    private static final int CHART_MAX_W = 96;
    private static final int CHART_MIN_W = 20;
    private static final int CHART_GAP = 8;
    /** plaque centre height above the block's bottom (bench top face is 1.0) */
    private static final float PANEL_Y = 0.5f;
    /** how far the plaque floats off the bench face (blocks) */
    private static final float FACE_GAP = 0.04f;
    /** lift of the marks over the background, in px (z-fight guard) */
    private static final float MARK_Z = 1f;

    private static final int PANEL_BG = 0xB0101010;
    private static final int HEADER_COLOR = 0xFF90C890;
    private static final int STAT_COLOR = 0xFFD0D0D0;
    private static final int HINT_COLOR = 0xFF909090;
    private static final int AXIS_COLOR = 0x60808080;
    private static final int CAP_COLOR = 0x40C08080;
    private static final int DOT_COLOR = 0xFFE0E0E0;
    private static final int START_COLOR = 0xFF7CD87C;

    /** 1x1 white pixel: the background and the chart marks are tinted quads */
    private static final ResourceLocation PANEL_TEX =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "textures/gui/wb_panel.png");
    private static final RenderType PANEL_TYPE = RenderType.text(PANEL_TEX);

    /** one measured line of the plaque */
    private record Line(String text, int color) {}

    /** measured plaque: sizes in px, columns as laid-out lines */
    private record Panel(int width, int height, List<Line> left, List<Line> right,
                         int chartW, int chartH, double pxPerDeg, double[] chart) {}

    // --- layout cache: one entry, the hovered bench is the only plaque drawn ---
    private static CompoundTag cachedTag;
    private static boolean cachedFull;
    private static Panel cachedPanel;

    private WorkbenchStatsPanel() {}

    static void render(GunWorkbenchBlockEntity bench, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // Trigger: the [i] state pins the plaque up (nothing to hover — that is
        // the point of the switch), OPEN leaves it to the crosshair, the old
        // trigger: this bench's [▼] take marker with a gun staged.
        GunWorkbenchBlockEntity.InfoState state = bench.getInfoState();
        if (state == GunWorkbenchBlockEntity.InfoState.OPEN) {
            BenchTargetPicker.Hover hover = BenchTargetPicker.currentHover();
            if (hover == null || !hover.marker().isTake()
                    || !hover.benchPos().equals(bench.getBlockPos())) {
                return;
            }
        }
        if (!WorkbenchOverlay.uiInRange(bench.getBlockPos())) return;
        ItemStack gun = bench.getGunSlot().getItem(0);
        if (!(gun.getItem() instanceof GunItem)) return;

        // the [i] state pins the shape too; only the hover trigger reads Shift
        boolean full = state == GunWorkbenchBlockEntity.InfoState.CLOSE
                || (state == GunWorkbenchBlockEntity.InfoState.OPEN && Screen.hasShiftDown());

        Panel panel = layout(gun, GunStats.ofGun(gun), full);

        // the side of the bench the camera is on: yaw from the side vector so
        // local +Z is that side's outward normal and local +X the viewer's
        // right (the blockstate yaw mirrors on east/west — do not use toYRot)
        Direction side = sideToward(mc.gameRenderer.getMainCamera().getPosition(), bench.getBlockPos());
        float yaw = (float) Math.toDegrees(Math.atan2(side.getStepX(), side.getStepZ()));

        poseStack.pushPose();
        try {
            poseStack.translate(0.5, PANEL_Y, 0.5);
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
            poseStack.translate(0f, 0f, 0.5f + FACE_GAP);
            // font-px frame: origin = plaque centre, +x right, +y up
            poseStack.scale(PX, PX, PX);
            draw(poseStack, buffer, panel, packedLight);
        } finally {
            poseStack.popPose();
        }
    }

    /** horizontal face of the bench the camera sits on */
    private static Direction sideToward(Vec3 cam, BlockPos pos) {
        double dx = cam.x - (pos.getX() + 0.5);
        double dz = cam.z - (pos.getZ() + 0.5);
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static Panel layout(ItemStack gun, GunStats stats, boolean full) {
        CompoundTag tag = gun.getTag();
        if (cachedPanel != null && full == cachedFull && tag != null && tag == cachedTag) return cachedPanel;
        Panel panel = build(stats, full);
        cachedTag = tag;
        cachedFull = full;
        cachedPanel = panel;
        return panel;
    }

    private static Panel build(GunStats stats, boolean full) {
        Font font = Minecraft.getInstance().font;
        List<Line> left = new ArrayList<>();
        List<Line> right = new ArrayList<>();
        if (stats.isComplete() && stats.receiver != null && stats.receiver.gunType != null) {
            left.add(new Line(Component.translatable(statKey("gun_type")).append(": ")
                    .append(Component.translatable("gun_type." + CreatePneumaticTacticals.MODID + "."
                            + stats.receiver.gunType.getSerializedName())).getString(), HEADER_COLOR));
        }

        List<Component> body = new ArrayList<>();
        if (full) {
            addFeedLine(body, stats);
            addSupplyLine(body, stats);
            body.add(fmt("reload_speed", stats.reloadSpeed));
            body.add(fmt("damage_multiplier", stats.damageMultiplier));
            body.add(fmt("fire_rate_multiplier", stats.fireRateMultiplier));
            body.add(fmt("hipfire_accuracy_multiplier", stats.hipfireAccuracyMultiplier));
            body.add(fmt("ergonomics", stats.ergonomics));
            body.add(fmt("aim_zoom", stats.aimZoom));
            body.add(fmt("tactical_aim_zoom", stats.tacticalAimZoom));
            body.add(fmt("bullet_speed", stats.bulletSpeed));
            body.add(fmt("recoil_vertical_multiplier", stats.recoilVerticalMultiplier));
            body.add(fmt("recoil_horizontal_multiplier", stats.recoilHorizontalMultiplier));
            body.add(fmt("recoil_recovery", stats.recoilRecovery));
            body.add(fmt("gravity_multiplier", stats.gravityMultiplier));
            body.add(fmt("drag_multiplier", stats.dragMultiplier));
            body.add(fmt("gas_suppression", stats.gasSuppression));
        } else {
            body.add(fmt("damage_multiplier", stats.damageMultiplier));
            body.add(fmt("fire_rate_multiplier", stats.fireRateMultiplier));
            body.add(fmt("ergonomics", stats.ergonomics));
            body.add(fmt("recoil_vertical_multiplier", stats.recoilVerticalMultiplier));
            body.add(fmt("recoil_horizontal_multiplier", stats.recoilHorizontalMultiplier));
            body.add(fmt("gravity_multiplier", stats.gravityMultiplier));
            body.add(fmt("drag_multiplier", stats.dragMultiplier));
        }

        if (full) {
            int half = (body.size() + 1) / 2;
            for (int i = 0; i < body.size(); i++) {
                (i < half ? left : right).add(new Line(body.get(i).getString(), STAT_COLOR));
            }
        } else {
            for (Component line : body) left.add(new Line(line.getString(), STAT_COLOR));
            left.add(new Line(Component.translatable(
                    "gui." + CreatePneumaticTacticals.MODID + ".stats_full_hint").getString(), HINT_COLOR));
        }

        int chartW = 0;
        int chartH = 0;
        double pxPerDeg = 0;
        double[] chart = new double[0];
        if (!full && stats.receiver != null && stats.receiver.id != null) {
            // ADS stance: same shape as hipfire, and the one a player learns
            RecoilPreview.compute(stats.receiver.id, stats.receiver.baseRecoilPitch,
                    stats.receiver.baseRecoilYaw, stats.recoilVerticalMultiplier,
                    stats.recoilHorizontalMultiplier, true);
            int n = RecoilPreview.count();
            if (n > 0) {
                // copied out of the shared buffer: the plaque owns its chart, so
                // another build (or a future caller of RecoilPreview) can never
                // repaint a cached one
                chart = new double[n * 2];
                double maxX = 0;
                for (int i = 0; i < n; i++) {
                    chart[i * 2] = RecoilPreview.x(i);
                    chart[i * 2 + 1] = RecoilPreview.y(i);
                    maxX = Math.max(maxX, Math.abs(chart[i * 2]));
                }
                // equal scale on both axes; the width cap only bites on patterns
                // wider than the box, and a gun with no drift at all still gets a
                // readable strip (its dots then sit on the centre line, the truth)
                pxPerDeg = Math.min((double) CHART_H / RecoilPreview.MAX_VERTICAL_DEGREES,
                        CHART_MAX_W / Math.max(2 * maxX, 0.5));
                chartH = (int) Math.round(RecoilPreview.MAX_VERTICAL_DEGREES * pxPerDeg);
                chartW = Math.max(CHART_MIN_W, (int) Math.round(2 * maxX * pxPerDeg));
            }
        }

        int textW = columnWidth(font, left) + (right.isEmpty() ? 0 : COL_GAP + columnWidth(font, right));
        int rows = Math.max(left.size(), right.size());
        int bodyH = Math.max(rows * LINE_H, chartH);
        return new Panel(PAD * 2 + chartW + (chartW > 0 ? CHART_GAP : 0) + textW, PAD * 2 + bodyH,
                left, right, chartW, chartH, pxPerDeg, chart);
    }

    private static int columnWidth(Font font, List<Line> lines) {
        int w = 0;
        for (Line line : lines) w = Math.max(w, font.width(line.text()));
        return w;
    }

    private static void draw(PoseStack poseStack, MultiBufferSource buffer, Panel panel, int packedLight) {
        Matrix4f mat = poseStack.last().pose();
        float left = -panel.width / 2f;
        float bottom = -panel.height / 2f;
        float top = bottom + panel.height;

        // background + chart marks share one render type and are flushed right
        // away: the font's own type may already be queued from another renderer
        // this frame, and a plaque drawn after its text would lose the depth
        // test (the marks sit behind the glyphs)
        VertexConsumer vc = buffer.getBuffer(PANEL_TYPE);
        // no depth write: the plaque is translucent, world geometry behind it
        // (water, glass) has to keep blending through
        RenderSystem.depthMask(false);
        quad(vc, mat, left, bottom, left + panel.width, top, 0f, packedLight, PANEL_BG);
        if (panel.chartW > 0) drawChart(vc, mat, panel, left + PAD, bottom + PAD, packedLight);
        if (buffer instanceof MultiBufferSource.BufferSource source) source.endBatch(PANEL_TYPE);
        RenderSystem.depthMask(true);

        Font font = Minecraft.getInstance().font;
        float textTop = top - PAD;
        float leftX = left + PAD + (panel.chartW > 0 ? panel.chartW + CHART_GAP : 0);
        drawLines(poseStack, buffer, font, panel.left, leftX, textTop, packedLight);
        if (!panel.right.isEmpty()) {
            drawLines(poseStack, buffer, font, panel.right, leftX + columnWidth(font, panel.left) + COL_GAP,
                    textTop, packedLight);
        }
    }

    /**
     * Axes and one dot per shot, in the plaque's y-up px frame. Reads the
     * plaque's own copy of the points — see {@link #build}.
     */
    private static void drawChart(VertexConsumer vc, Matrix4f mat, Panel panel, float left, float bottom,
                                  int packedLight) {
        float axisX = left + panel.chartW / 2f;
        float top = bottom + panel.chartH;
        // centre line (x = 0), baseline (0 deg pitch) and the cap line the
        // simulation stops at
        quad(vc, mat, axisX, bottom, axisX + 1, top, MARK_Z, packedLight, AXIS_COLOR);
        quad(vc, mat, left, bottom, left + panel.chartW, bottom + 1, MARK_Z, packedLight, AXIS_COLOR);
        quad(vc, mat, left, top - 1, left + panel.chartW, top, MARK_Z, packedLight, CAP_COLOR);
        for (int i = 0; i < panel.chart.length / 2; i++) {
            float px = axisX + (float) (panel.chart[i * 2] * panel.pxPerDeg);
            // the shot that crossed the cap overshoots it by its own kick: pin
            // it to the top line instead of drawing past the chart
            float py = bottom + (float) (Math.min(panel.chart[i * 2 + 1],
                    RecoilPreview.MAX_VERTICAL_DEGREES) * panel.pxPerDeg);
            if (i == 0) {
                quad(vc, mat, px - 1, py - 1, px + 2, py + 2, MARK_Z, packedLight, START_COLOR); // start
            } else {
                quad(vc, mat, px - 1, py - 1, px + 1, py + 1, MARK_Z, packedLight, DOT_COLOR);
            }
        }
    }

    /** one column of lines; {@code firstTop} is the first line's top edge */
    private static void drawLines(PoseStack poseStack, MultiBufferSource buffer, Font font,
                                  List<Line> lines, float x, float firstTop, int packedLight) {
        float y = firstTop;
        for (Line line : lines) {
            poseStack.pushPose();
            try {
                poseStack.translate(x, y, MARK_Z);
                poseStack.scale(1f, -1f, 1f); // the font lays out for a y-down frame
                font.drawInBatch(line.text(), 0f, 0f, line.color(), true,
                        poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
            } finally {
                poseStack.popPose();
            }
            y -= LINE_H;
        }
    }

    /** tinted quad of the 1x1 white texture; TL/BL/BR/TR is front-facing here */
    private static void quad(VertexConsumer vc, Matrix4f mat, float x0, float y0, float x1, float y1,
                             float z, int packedLight, int argb) {
        float a = (argb >>> 24) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        vc.vertex(mat, x0, y1, z).color(r, g, b, a).uv(0f, 0f).uv2(packedLight).endVertex();
        vc.vertex(mat, x0, y0, z).color(r, g, b, a).uv(0f, 1f).uv2(packedLight).endVertex();
        vc.vertex(mat, x1, y0, z).color(r, g, b, a).uv(1f, 1f).uv2(packedLight).endVertex();
        vc.vertex(mat, x1, y1, z).color(r, g, b, a).uv(1f, 0f).uv2(packedLight).endVertex();
    }

    /** "Feed Type: Magazine  (Capacity: 30)" — same shape as the module tooltip */
    private static void addFeedLine(List<Component> lines, GunStats stats) {
        if (stats.feed == null || stats.feed.feedType == null) return;
        MutableComponent line = Component.translatable(statKey("feed_type")).append(": ")
                .append(Component.translatable("feed_type." + CreatePneumaticTacticals.MODID
                        + "." + stats.feed.feedType.getSerializedName()));
        if (stats.feed.clipSize > 0) {
            line.append(Component.literal("  (").append(Component.translatable(statKey("clip_capacity")))
                    .append(": " + stats.feed.clipSize + ")"));
        }
        lines.add(line);
    }

    private static void addSupplyLine(List<Component> lines, GunStats stats) {
        if (stats.supply == null || stats.supply.supplyType == null) return;
        lines.add(Component.translatable(statKey("supply_type")).append(": ")
                .append(Component.translatable("supply_type." + CreatePneumaticTacticals.MODID
                        + "." + stats.supply.supplyType.getSerializedName())));
    }

    private static Component fmt(String statKey, double value) {
        return Component.translatable(statKey(statKey))
                .append(": ").append(String.format("%.2f", value));
    }

    private static String statKey(String statKey) {
        return "stat." + CreatePneumaticTacticals.MODID + "." + statKey;
    }
}
