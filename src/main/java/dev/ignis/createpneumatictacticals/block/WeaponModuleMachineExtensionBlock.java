package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Extension half of the machine tool: the cell the master's 32 px model
 * overhangs into (see {@link MachineLayout}). It has no item of its own — it
 * carries only the master's facing, is placed and removed together with the
 * master, and drops nothing on its own.
 *
 * <p>It does hold a bare kinetic block entity, and answers {@code IRotate} for
 * the machine's length axis: that is the relay Create's rotation propagation
 * needs, because the propagator walks block entities and the machine is two
 * cells long (see {@link #newBlockEntity}). No state lives here — the machine's
 * inventory, recipe and job are all on the master.
 *
 * <p>Like the master it is {@link RenderShape#INVISIBLE}: the half is real
 * geometry only for collision and ray tracing.
 */
public class WeaponModuleMachineExtensionBlock extends HorizontalDirectionalBlock
        implements EntityBlock, com.simibubi.create.content.kinetics.base.IRotate {

    public WeaponModuleMachineExtensionBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /**
     * A bare {@code KineticBlockEntity}: the extension holds no state, it is a
     * kinetic block purely so Create's rotation propagation can hop from a
     * shaft attached to the machine's right end, through this cell, into the
     * master's block entity one cell further in (the propagator only walks
     * block entities). Its stress impact is unregistered, i.e. zero — the
     * master is the consumer.
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new com.simibubi.create.content.kinetics.base.KineticBlockEntity(
                ModBlockEntities.WEAPON_MODULE_MACHINE_EXTENSION.get(), pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (type != ModBlockEntities.WEAPON_MODULE_MACHINE_EXTENSION.get()) return null;
        return (BlockEntityTicker<T>) (lvl, pos, bstate, be) ->
                ((com.simibubi.create.content.kinetics.base.KineticBlockEntity) be).tick();
    }

    /** both faces of the machine's length axis: the shaft outside, the master inside */
    @Override
    public boolean hasShaftTowards(net.minecraft.world.level.LevelReader world, BlockPos pos,
                                   BlockState state, Direction face) {
        return face.getAxis() == MachineLayout.shaftAxis(MachineLayout.facing(state));
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return MachineLayout.shaftAxis(MachineLayout.facing(state));
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

    /** a signal on this half starts the machine too: see {@link WeaponModuleMachineBlock#redstoneStart} */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block fromBlock,
                                BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, fromBlock, fromPos, isMoving);
        WeaponModuleMachineBlock.redstoneStart(level, pos, state);
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
