package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Pneumatic gun assembly bench — fully 3D, no GUI. Right-click with a gun
 * or a receiver stages it (the client sends Workbench3dPacket; a receiver
 * auto-creates the bare gun server-side). The staged gun lies on the
 * bench (muzzle to the left of whoever faces its front) with [+]/[-]/[▼]
 * markers. The block entity persistently holds the
 * staged gun; breaking the block drops it (modules are baked into its NBT —
 * dropping both would duplicate them).
 */
public class GunWorkbenchBlock extends net.minecraft.world.level.block.HorizontalDirectionalBlock
        implements EntityBlock {

    public GunWorkbenchBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(
            net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        // front faces the placer (furnace convention); the staged gun is laid
        // out relative to this facing
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
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

    /**
     * No-op on the server: staging is driven by Workbench3dPacket (the
     * client sends it after picking a marker hitbox). Consuming here keeps
     * vanilla from treating the click as item-use (bow draw, etc).
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return level.isClientSide ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
    }
}