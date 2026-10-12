package dev.ignis.createpneumatictacticals.client.render;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.PegboardGrid;
import dev.ignis.createpneumatictacticals.block.PegboardHang;
import dev.ignis.createpneumatictacticals.block.WeaponPegboardBlock;
import dev.ignis.createpneumatictacticals.block.entity.WeaponPegboardBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side pegboard preview: the hole the crosshair is over and the item
 * that would hang on it, recomputed once per frame and read by
 * {@link WeaponPegboardRenderer} during the block-entity pass (the same
 * "measure a frame, draw with last frame's result" split the bench uses, see
 * {@code BenchTargetPicker}).
 *
 * <p>Nothing is previewed while sneaking: that gesture takes an item off the
 * board. Nor over an occupied hole — a second item there is refused, so the
 * preview would promise something the click will not do.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class PegboardPreview {

    /** the hole the crosshair is over, and what would hang there */
    public record Target(BlockPos pos, int x, int y, boolean vertical, int sign, ItemStack stack) {
    }

    @Nullable
    private static Target target;

    @Nullable
    public static Target current() {
        return target;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        update();
    }

    private static void update() {
        Minecraft mc = Minecraft.getInstance();
        target = null;
        if (mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit)) return;
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof WeaponPegboardBlock)) return;
        Direction facing = state.getValue(WeaponPegboardBlock.FACING);
        if (hit.getDirection() != facing) return;
        if (mc.player.isShiftKeyDown()) return;
        ItemStack held = mc.player.getMainHandItem();
        if (!WeaponPegboardBlock.isHangable(held)) return;
        int[] anchor = PegboardGrid.anchorOf(facing, hit);
        if (!(mc.level.getBlockEntity(pos) instanceof WeaponPegboardBlockEntity board)
                || board.occupied(anchor[0], anchor[1])) {
            return;
        }
        PegboardHang.Orient orient = PegboardHang.orient(mc.player.getViewVector(1f), facing);
        target = new Target(pos, anchor[0], anchor[1], orient.vertical(), orient.sign(), held);
    }

    private PegboardPreview() {
    }
}
