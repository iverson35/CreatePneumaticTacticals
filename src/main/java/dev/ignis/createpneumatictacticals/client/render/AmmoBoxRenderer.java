package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ignis.createpneumatictacticals.block.AmmoBoxBlock;
import dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity;
import dev.ignis.createpneumatictacticals.item.PodItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.Matrix4f;

/**
 * Content plate on the placed ammo box: both narrow body faces (the model's
 * north/south sides, 6x6 px each — see models/block/ammo_box.json) show the
 * icon of the content sealed inside the pods, with the box's round count under
 * it. The pod ITEM is deliberately not shown — every pod looks the same, the
 * content is what the player recognises.
 *
 * <p>Drawn as a plain atlas quad rather than through {@code ItemRenderer}: the
 * plate has to stay inside the face, and item display transforms (handheld
 * scale, fixed rotation) would push it out. Both elements sit inside a 4x4 px
 * window in the middle of the 6x6 px face.
 *
 * <p>The pose mirrors the blockstate's model rotation: {@code
 * Axis.YP.rotationDegrees(facing.toYRot())} about the block centre reproduces
 * {@link AmmoBoxBlock}'s collision-shape rotation (verified numerically against
 * its {@code rotateCube}), so the plates land on the facing and its opposite.
 */
public class AmmoBoxRenderer implements BlockEntityRenderer<AmmoBoxBlockEntity> {

    // --- body cube of the model, in px (models/block/ammo_box.json) ---
    /** the face is 6x6 px: model x 5..11 (5+11 = 16, so it is centred), y 0..6 */
    private static final float FACE = 6f;
    private static final float FACE_LEFT_PX = 5f;
    private static final float FACE_BOTTOM_PX = 0f;
    private static final float NORTH_Z_PX = 2f;
    private static final float SOUTH_Z_PX = 14f;

    // --- plate layout, in face-local px (origin = face bottom-left, y up) ---
    /** content window: the 4x4 px middle of the face, centred (1 px clear on
     *  every side of the 6x6 px face) — icon over count, both inside it */
    private static final float WINDOW_PX = 4f;
    private static final float WINDOW_LEFT_PX = (FACE - WINDOW_PX) / 2f;
    private static final float WINDOW_BOTTOM_PX = (FACE - WINDOW_PX) / 2f;
    /** icon: top half of the window, square (the sprite is 16x16 — a wider
     *  quad would squash it) */
    private static final float ICON_PX = WINDOW_PX / 2f;
    private static final float ICON_BOTTOM_PX = WINDOW_BOTTOM_PX + WINDOW_PX / 2f;
    /**
     * The font draws in a y-DOWN frame: {@code drawInBatch(x, y)} anchors the
     * top of the line at y and the glyphs grow towards +y (BakedGlyph.render
     * puts the sprite's v0 row at y + up). This frame is y-up, so drawCount
     * mirrors y — which also rights the quads' winding (a y-mirror flips it,
     * and the font's native winding is the front-facing one). Without the
     * mirror the number renders upside-down AND back-facing: visible only from
     * inside the box, where the block's own face is culled. The line top sits
     * on the window's midline, so the digits fill its lower half.
     */
    private static final float TEXT_TOP_PX = ICON_BOTTOM_PX;
    /** the number stays 2 px tall (the font's digits are 7 px at scale 1) */
    private static final float TEXT_MAX_PX = 2f;
    private static final float GLYPH_CAP_PX = 7f;
    /** ... and never wider than the window (long counts shrink instead) */
    private static final float TEXT_MAX_WIDTH_PX = WINDOW_PX;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    /** lift the plate off the face so it cannot z-fight with the model quad */
    private static final float SURFACE_OFFSET = 0.005f;

    public AmmoBoxRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(AmmoBoxBlockEntity box, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        ItemStack template = box.template();
        int rounds = box.rounds();
        if (template.isEmpty() || rounds <= 0) return;
        Item content = PodItem.contentItem(template);
        if (content == null) return;
        // crosshair readout: only the box the player is actually aiming at
        // shows its content, so a wall of boxes stays clean
        if (!isTargeted(box)) return;
        TextureAtlasSprite icon = Minecraft.getInstance().getItemRenderer()
                .getModel(new ItemStack(content), box.getLevel(), null, 0)
                .getParticleIcon();
        Direction facing = box.getBlockState().getValue(AmmoBoxBlock.FACING);
        String count = String.valueOf(rounds);

        poseStack.pushPose();
        try {
            poseStack.translate(0.5, 0.5, 0.5);
            // the blockstate's model rotation, in pose terms (north as authored)
            poseStack.mulPose(Axis.YP.rotationDegrees(facing.toYRot()));
            drawPlate(poseStack, buffer, icon, count, packedLight, NORTH_Z_PX, true);
            drawPlate(poseStack, buffer, icon, count, packedLight, SOUTH_Z_PX, false);
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Whether the player's crosshair is on this box. Vanilla only raycasts to
     * the player's reach, so a hit result implies "in reach and selected";
     * every other box in the world draws its model without the plate.
     */
    private static boolean isTargeted(AmmoBoxBlockEntity box) {
        HitResult hit = Minecraft.getInstance().hitResult;
        return hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(box.getBlockPos());
    }

    /**
     * One narrow face. After the block rotation the model frame is centred on
     * the block, so the face plane sits at {@code (faceZ - 8) / 16}.
     */
    private static void drawPlate(PoseStack poseStack, MultiBufferSource buffer, TextureAtlasSprite icon,
                                  String count, int packedLight, float faceZ, boolean north) {
        poseStack.pushPose();
        try {
            poseStack.translate(0f, 0f, (faceZ - 8f) / 16f);
            // the font's and the quad's fronts are local +Z: the north face has
            // to be turned around to face its own outward normal
            if (north) poseStack.mulPose(Axis.YP.rotationDegrees(180f));
            poseStack.translate(0f, 0f, SURFACE_OFFSET);
            // face-local px frame: origin at the face's bottom-left, +x = the
            // viewer's right (mirrored on the north face by the turn above)
            poseStack.translate((FACE_LEFT_PX - 8f) / 16f, (FACE_BOTTOM_PX - 8f) / 16f, 0f);
            poseStack.scale(1f / 16f, 1f / 16f, 1f / 16f);
            drawIcon(poseStack, buffer, icon, packedLight);
            drawCount(poseStack, buffer, count, packedLight);
        } finally {
            poseStack.popPose();
        }
    }

    private static void drawIcon(PoseStack poseStack, MultiBufferSource buffer, TextureAtlasSprite icon,
                                 int packedLight) {
        VertexConsumer vc = buffer.getBuffer(RenderType.text(icon.atlasLocation()));
        Matrix4f pose = poseStack.last().pose();
        float half = ICON_PX / 2f;
        float mid = FACE / 2f;
        float x0 = mid - half, x1 = mid + half;
        float y0 = ICON_BOTTOM_PX, y1 = y0 + ICON_PX;
        // TL -> BL -> BR -> TR with u0/v0 at the top-left: front-facing in this
        // y-up frame. The reverse order is back-facing and gets culled. (The
        // font's own order matches this visually but is authored for y-down —
        // see TEXT_TOP_PX, which is why drawCount mirrors y.)
        vc.vertex(pose, x0, y1, 0f).color(255, 255, 255, 255).uv(icon.getU0(), icon.getV0()).uv2(packedLight).endVertex();
        vc.vertex(pose, x0, y0, 0f).color(255, 255, 255, 255).uv(icon.getU0(), icon.getV1()).uv2(packedLight).endVertex();
        vc.vertex(pose, x1, y0, 0f).color(255, 255, 255, 255).uv(icon.getU1(), icon.getV1()).uv2(packedLight).endVertex();
        vc.vertex(pose, x1, y1, 0f).color(255, 255, 255, 255).uv(icon.getU1(), icon.getV0()).uv2(packedLight).endVertex();
    }

    private static void drawCount(PoseStack poseStack, MultiBufferSource buffer, String count, int packedLight) {
        Font font = Minecraft.getInstance().font;
        int width = font.width(count);
        float scale = Math.min(TEXT_MAX_PX / GLYPH_CAP_PX, TEXT_MAX_WIDTH_PX / Math.max(1, width));
        poseStack.pushPose();
        try {
            poseStack.translate(FACE / 2f, TEXT_TOP_PX, 0f);
            // mirror y: the font lays out for a y-down frame (see TEXT_TOP_PX)
            poseStack.scale(scale, -scale, scale);
            font.drawInBatch(count, -width / 2f, 0f, TEXT_COLOR, true,
                    poseStack.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, packedLight);
        } finally {
            poseStack.popPose();
        }
    }
}
