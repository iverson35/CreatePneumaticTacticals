package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.ignis.createpneumatictacticals.Config;
import dev.ignis.createpneumatictacticals.block.PegboardGrid;
import dev.ignis.createpneumatictacticals.block.PegboardHang;
import dev.ignis.createpneumatictacticals.block.WeaponPegboardBlock;
import dev.ignis.createpneumatictacticals.block.entity.WeaponPegboardBlockEntity;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;

/**
 * Draws what hangs on a pegboard, and the translucent blue ghost of what a
 * click would hang there.
 *
 * <p>Posing rule (mirrors {@link PegboardHang}): the item's bounding box is
 * measured in gun space, its centre on the two plate-parallel axes is placed
 * on the hole, and its back — the end along the axis that points out of the
 * plate — rests 0.5 px in front of the plate's front face. The gun keeps its
 * own model convention, so the muzzle points along the board (horizontal) or
 * up/down (vertical, sights facing away from the plate).
 *
 * <p>{@code Config.pegboardRenderDistance} is this renderer's view distance,
 * so 0 disables the contents AND the preview (both are drawn here).
 */
public final class WeaponPegboardRenderer implements BlockEntityRenderer<WeaponPegboardBlockEntity> {

    /** the item's back floats this far off the plate's front face (px) */
    private static final double GAP_PX = 0.5;

    public WeaponPegboardRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(WeaponPegboardBlockEntity board, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BlockState state = board.getBlockState();
        if (!(state.getBlock() instanceof WeaponPegboardBlock)) return;
        Direction facing = state.getValue(WeaponPegboardBlock.FACING);
        BlockPos pos = board.getBlockPos();
        // the plate is solid, so the light stored AT it is 0 — sample the air
        // in front, where the items actually hang (the bench does the same)
        int light = LevelRenderer.getLightColor(board.getLevel(), pos.relative(facing));
        for (WeaponPegboardBlockEntity.Hang hang : board.hangs()) {
            drawPosed(facing, hang.x(), hang.y(), new PegboardHang.Orient(hang.vertical(), hang.sign()),
                    hang.stack(), partialTick, poseStack, buffer, light, packedOverlay, false);
        }
        PegboardPreview.Target ghost = PegboardPreview.current();
        if (ghost != null && ghost.pos().equals(pos)) {
            drawPosed(facing, ghost.x(), ghost.y(),
                    new PegboardHang.Orient(ghost.vertical(), ghost.sign()),
                    ghost.stack(), partialTick, poseStack, buffer, light, packedOverlay, true);
        }
    }

    private static void drawPosed(Direction facing, int holeX, int holeY, PegboardHang.Orient orient,
                                  ItemStack stack, float partialTick, PoseStack poseStack,
                                  MultiBufferSource buffer, int light, int overlay, boolean ghost) {
        float[] bounds = boundsOf(stack);
        if (bounds == null) return;
        // bottom-only handguard attachments are modelled upside down to match
        // their 180°-rolled loc bone; the standalone item flips them upright,
        // and the placement bounds must follow that flip (see ModuleTunerRenderer)
        boolean flip = stack.getItem() instanceof ModuleItem && ModuleItemRenderer.isBottomOnly(stack);
        if (flip) bounds = ModuleDraw.flipZ(bounds);

        double[] anchorPx = PegboardGrid.toWorld(holeX, holeY, 0, facing);
        // BLOCK-LOCAL: the block-entity dispatcher already translated by
        // pos - camera, so only the hole offset inside this block is left to
        // apply. Adding the block position here would draw every hang a whole
        // board-position away from the plate (the bug that hid all of them).
        Vec3 anchor = new Vec3(anchorPx[0] / 16.0, anchorPx[1] / 16.0, anchorPx[2] / 16.0);
        Vector3f out = PegboardHang.normal(facing);
        Vec3 dest = anchor.add(out.x() * (GAP_PX / 16.0), out.y() * (GAP_PX / 16.0), out.z() * (GAP_PX / 16.0));
        Vector3f ref = PegboardHang.refPoint(bounds, facing, orient);

        poseStack.pushPose();
        try {
            poseStack.translate(dest.x, dest.y, dest.z);
            poseStack.mulPose(PegboardHang.rotation(facing, orient));
            poseStack.translate(-ref.x(), -ref.y(), -ref.z());
            if (flip) poseStack.mulPose(Axis.ZP.rotationDegrees(180f));
            if (ghost) {
                GhostPreview.drawStack(stack, poseStack, buffer, light, overlay);
            } else if (stack.getItem() instanceof GunItem) {
                GunHandsAwareRenderer.renderStandalone(stack, poseStack, buffer, light, overlay);
            } else {
                ModuleDraw.draw(stack, poseStack, buffer, partialTick, light, overlay);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** gun-space bounds of a gun (modules included) or of a module item */
    @Nullable
    private static float[] boundsOf(ItemStack stack) {
        if (stack.getItem() instanceof GunItem) return GunWorkbenchRenderer.bounds(stack);
        if (stack.getItem() instanceof ModuleItem module && module.isModule(stack)) {
            ModuleGeoModel model = ModuleDraw.geoModel();
            model.setStack(stack);
            BakedGeoModel baked = model.getBakedModel(model.getModelResource(module));
            return ModelBounds.of(baked);
        }
        return null;
    }

    @Override
    public int getViewDistance() {
        return Config.pegboardRenderDistance;
    }
}
