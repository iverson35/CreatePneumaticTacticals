package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.menu.WorkbenchAssembler;
import dev.ignis.createpneumatictacticals.module.HandguardPosition;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Draws the workbench markers as camera-facing quads ([+] free mounts,
 * [-] occupied mounts translucent, [▼] take) plus the translucent blue
 * module preview at the hovered [+] mount.
 *
 * <p>Only the markers {@link BenchTargetPicker#isVisible} admits are drawn,
 * so the icons on screen are exactly the markers the crosshair can pick.
 * The hovered one is fully opaque and gets its slot name underneath — the
 * label is what tells the player which mount they are about to touch.
 *
 * <p>All drawing happens in a fresh world-relative PoseStack (the BER
 * frame), AFTER {@link GunWorkbenchRenderer} rendered the gun — markers
 * must not inherit the bench pose.
 */
final class WorkbenchMarkerRenderer {

    /** marker world size at reference distance; clamped near/far */
    private static final float SIZE = 0.055f;
    private static final float SIZE_MIN = 0.035f;
    private static final float SIZE_MAX = 0.085f;
    private static final float REF_DIST = 1.6f;
    /** preview tint (translucent blue) */
    private static final float PREVIEW_R = 0.4f, PREVIEW_G = 0.6f, PREVIEW_B = 1f, PREVIEW_A = 0.5f;

    private static final ResourceLocation TEX_PLUS = tex("wb_plus");
    private static final ResourceLocation TEX_MINUS = tex("wb_minus");
    private static final ResourceLocation TEX_TAKE = tex("wb_take");

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(CreatePneumaticTacticals.MODID, "textures/gui/" + name + ".png");
    }

    /** label text height relative to the marker's half-size (9px font line) */
    private static final float LABEL_SCALE = 0.055f;
    /** gap between the icon's bottom edge and the label, × half-size */
    private static final float LABEL_GAP = 0.1f;
    /** label colour: white, like the marker quads themselves */
    private static final int LABEL_COLOR = 0xFFFFFFFF;

    private WorkbenchMarkerRenderer() {}


    static void render(GunWorkbenchBlockEntity bench, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        BenchTargetPicker.onBenchRendered(bench);
        // past UI_RANGE the bench keeps its gun but loses every marker/preview
        if (!WorkbenchOverlay.uiInRange(bench.getBlockPos())) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        // the BER pose stack origin is the BLOCK corner (the dispatcher
        // already translated): marker world positions must be relativized
        Vec3 origin = Vec3.atLowerCornerOf(bench.getBlockPos());
        for (WorkbenchOverlay.Marker m : WorkbenchOverlay.markers(bench)) {
            // hidden markers are neither drawn nor pickable (the picker applies
            // the same rule, so what is on screen is what can be hovered)
            if (!BenchTargetPicker.isVisible(bench, m)) continue;
            drawBillboard(poseStack, buffer, m, cam, origin,
                    BenchTargetPicker.isHovered(m), packedLight);
        }
    }

    // ---------------------------------------------------------------
    // marker billboards
    // ---------------------------------------------------------------

    private static void drawBillboard(PoseStack poseStack, MultiBufferSource buffer,
                                      WorkbenchOverlay.Marker m, Vec3 cam, Vec3 origin,
                                      boolean hovered, int packedLight) {
        Vec3 pos = m.worldPos().subtract(origin); // block-relative
        Vec3 camRel = cam.subtract(origin); // camera in the same frame
        Vec3 toMarker = pos.subtract(camRel);
        double dist = toMarker.length();
        float size = clamp((float) (dist / REF_DIST) * SIZE, SIZE_MIN, SIZE_MAX);
        // nudge toward the camera so markers never z-fight the gun/bench
        Vec3 n = dist > 1e-4 ? toMarker.normalize() : new Vec3(0, 1, 0);
        Vec3 rel = pos.add(n.scale(0.03));
        // not looked at: heavily faded (the installed [-] the faintest) so the
        // bench reads as a physical object, not a wall of icons; only the
        // hovered marker is fully opaque
        float alpha = hovered ? 1f : (m.occupied() && !m.isTake() ? 0.18f : 0.28f);

        poseStack.pushPose();
        try {
            poseStack.translate(rel.x, rel.y, rel.z);
            // cylindrical billboard: spin around Y so the quad's +Z normal
            // faces the camera horizontally (visible from every side)
            float yaw = (float) Math.atan2(camRel.x - rel.x, camRel.z - rel.z);
            poseStack.mulPose(com.mojang.math.Axis.YP.rotation(yaw));
            Matrix4f mat = poseStack.last().pose();
            quad(vc(buffer, m), mat, size, alpha, packedLight);
        } finally {
            poseStack.popPose();
        }
        if (hovered) drawLabel(poseStack, buffer, m, rel, size, packedLight);
    }

    /**
     * Slot name under the hovered marker, in its own camera-facing frame (the
     * icon's cylindrical billboard would tilt the text away from the eye when
     * looking down at the bench).
     *
     * <p>The transform is the vanilla name-tag one: the font draws y-down in
     * screen space, and the camera orientation plus the double flip both right
     * the glyphs and keep the quads' winding front-facing — the text render
     * types cull back faces, so a single-axis flip would draw nothing.
     */
    private static void drawLabel(PoseStack poseStack, MultiBufferSource buffer,
                                  WorkbenchOverlay.Marker m, Vec3 rel, float size, int packedLight) {
        Component label = label(m);
        if (label == null) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        String text = label.getString();
        float scale = size * LABEL_SCALE;
        poseStack.pushPose();
        try {
            poseStack.translate(rel.x, rel.y, rel.z);
            poseStack.mulPose(mc.gameRenderer.getMainCamera().rotation());
            // in the camera frame +y is up on screen: step down past the icon
            poseStack.translate(0f, -size - size * LABEL_GAP, 0f);
            poseStack.scale(-scale, -scale, scale);
            font.drawInBatch(text, -font.width(text) / 2f, 0f, LABEL_COLOR, true,
                    poseStack.last().pose(), buffer, Font.DisplayMode.SEE_THROUGH, 0, packedLight);
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * The slot a marker belongs to: the module type its mount accepts ("后托"
     * for the stock mount), or the handguard position for a handguard
     * attachment point ("护木(上)"). Null for [▼] — the take marker is the gun
     * itself, not a slot.
     */
    private static @Nullable Component label(WorkbenchOverlay.Marker m) {
        if (m.isTake()) return null;
        HandguardPosition pos = WorkbenchAssembler.handguardPosFromMount(m.mountId());
        if (pos != null) {
            return Component.translatable(typeKey(ModuleType.HANDGUARD))
                    .append(Component.translatable("hg_pos." + CreatePneumaticTacticals.MODID
                            + "." + pos.getSerializedName()));
        }
        ModuleType type = WorkbenchAssembler.mountTypeOf(m.mountId());
        return type == null ? null : Component.translatable(typeKey(type));
    }

    private static String typeKey(ModuleType type) {
        return "module_type." + CreatePneumaticTacticals.MODID + "." + type.getSerializedName();
    }

    /**
     * Marker vertex consumer: RenderType.textSeeThrough (Forge, memoized per
     * texture) — translucent, NO depth test (the [-] marker sits inside the
     * module mesh, [▼] inside the gun body) and no overlay element (an unset
     * overlay coord samples a garbage pixel and tints the quad black).
     * The text shader ignores the lightmap, so markers stay legible in the
     * dark — deliberate for UI.
     */
    private static VertexConsumer vc(MultiBufferSource buffer, WorkbenchOverlay.Marker m) {
        ResourceLocation tex = m.isTake() ? TEX_TAKE : m.occupied() ? TEX_MINUS : TEX_PLUS;
        return buffer.getBuffer(RenderType.textSeeThrough(tex));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * One billboard quad. QUADS mode takes exactly FOUR vertices (the buffer
     * triangulates); six would emit a half quad plus a stray connector quad
     * spanning to the next marker. CCW seen from +Z (the spun front side);
     * uv origin top-left.
     */
    private static void quad(VertexConsumer vc, Matrix4f mat, float s, float alpha, int packedLight) {
        v(vc, mat, -s, +s, 0f, 0f, alpha, packedLight);
        v(vc, mat, -s, -s, 0f, 1f, alpha, packedLight);
        v(vc, mat, +s, -s, 1f, 1f, alpha, packedLight);
        v(vc, mat, +s, +s, 1f, 0f, alpha, packedLight);
    }

    /** POSITION_COLOR_TEX_LIGHTMAP: position, colour, uv, light — nothing else */
    private static void v(VertexConsumer vc, Matrix4f mat, float x, float y,
                          float u, float vv, float alpha, int packedLight) {
        vc.vertex(mat, x, y, 0f)
                .color(1f, 1f, 1f, alpha)
                .uv(u, vv)
                .uv2(packedLight)
                .endVertex();
    }

    // ---------------------------------------------------------------
    // translucent preview at the hovered free mount
    // ---------------------------------------------------------------

    // (the [+] ghost itself is drawn by GunModulesLayer through the mounted
    // modules' own bone chains — see GunModulesLayer.GHOST)

    /** same validation the server applies on INSTALL; null = would fit */
    static String previewReject(GunWorkbenchBlockEntity bench, ModuleDefinition def,
                                        WorkbenchOverlay.Marker m) {
        ItemStack gun = bench.getGunSlot().getItem(0);
        if (!(gun.getItem() instanceof GunItem)) return "no_gun";
        // handguard attachments only: validate for the mount's position
        if (def.type == dev.ignis.createpneumatictacticals.module.ModuleType.HANDGUARD_ATTACHMENT) {
            HandguardPosition pos = WorkbenchAssembler.handguardPosFromMount(m.mountId());
            if (pos == null) return "no_mount_point";
            return GunNbt.validateHandguardAttachment(GunNbt.readModules(gun),
                    GunNbt.readHandguardAttachments(gun).values(), pos, def);
        }
        // single-slot mount: bone type must match the held module type
        ModuleType expected = WorkbenchAssembler.mountTypeOf(m.mountId());
        if (expected == null || expected != def.type) return "wrong_type";
        return GunNbt.validate(GunNbt.readModules(gun),
                GunNbt.readHandguardAttachments(gun).values(), def);
    }


}