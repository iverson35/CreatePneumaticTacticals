package dev.ignis.createpneumatictacticals.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Extension half of the machine tool: the cell the master's 32 px model
 * overhangs into (see {@link MachineLayout}). It has no item and no block
 * entity — it carries only the master's facing, is placed and removed together
 * with the master, and drops nothing on its own.
 *
 * <p>Like the master it is {@link RenderShape#INVISIBLE}: the half is real
 * geometry only for collision and ray tracing.
 */
public class WeaponModuleMachineExtensionBlock extends HorizontalDirectionalBlock {

    public WeaponModuleMachineExtensionBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /** the machine's two cells stay solid: only the outline covers both (see MachineLayout#machineShape) */
    @Override
    @Deprecated
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return Shapes.block();
    }

    /** outline + ray trace span the whole machine, whichever half is under the crosshair */
    @Override
    @Deprecated
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return MachineLayout.machineShape(state, false);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        return MachineInteractions.use(state, level, pos, player, hand, hit);
    }

    /** breaking either half takes the whole machine: the master drops, this half never does */
    @Override
    @Deprecated
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos master = MachineLayout.masterPos(pos, MachineLayout.facing(state));
            if (level.getBlockState(master).is(ModBlocks.WEAPON_MODULE_MACHINE_TOOL.get())) {
                level.destroyBlock(master, true);
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * Non-player removal (explosions, pistons, {@code /setblock}): take the
     * master with it, contents dropped as its item, so no half machine is ever
     * left standing.
     */
    @Override
    @Deprecated
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            BlockPos master = MachineLayout.masterPos(pos, MachineLayout.facing(state));
            BlockState masterState = level.getBlockState(master);
            if (masterState.is(ModBlocks.WEAPON_MODULE_MACHINE_TOOL.get())) {
                Block.dropResources(masterState, level, master, null,
                        null, net.minecraft.world.item.ItemStack.EMPTY);
                level.removeBlock(master, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    @Deprecated
    public java.util.List<net.minecraft.world.item.ItemStack> getDrops(
            BlockState state,
            net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        return java.util.List.of();
    }
}
