package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * Pneumatic gun assembly bench. Right-click opens the assembly GUI; the
 * block entity persistently holds the staged gun and module items. Breaking
 * the block drops the gun (staged modules are baked into its NBT — dropping
 * both would duplicate them), or the orphaned staging when no gun is staged.
 */
public class GunWorkbenchBlock extends Block implements EntityBlock {

    public GunWorkbenchBlock(Properties properties) {
        super(properties);
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GunWorkbenchBlockEntity(pos, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof GunWorkbenchBlockEntity bench) {
            net.minecraft.world.item.ItemStack gun = bench.getGunSlot().getItem(0);
            if (!gun.isEmpty()) {
                // staged modules are baked into the gun's NBT — dropping both
                // would duplicate them
                net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), gun);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof GunWorkbenchBlockEntity bench) {
            NetworkHooks.openScreen((ServerPlayer) player, bench, pos);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}