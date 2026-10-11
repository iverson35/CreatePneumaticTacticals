package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.ModBlockEntities;
import dev.ignis.createpneumatictacticals.block.entity.WeaponModuleMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Weapon module machine tool (武器配件机床) — master half of a two-cell machine.
 * The model is 32 px long along model z (see {@link MachineLayout}): this block
 * owns model z -8..+8 (the screen panel, the drill and the front of the clamp)
 * while {@link WeaponModuleMachineExtensionBlock} fills model z -24..-8 (the
 * sliding door and the back of the clamp). One collision box cannot cover an
 * overhanging half cell, so the second cell is a real block: that is what makes
 * the whole machine clickable.
 *
 * <p>Nothing of the machine is drawn by the block model: the geo is rendered by
 * its block entity renderer (GeckoLib item pipeline), the block state is
 * {@link RenderShape#INVISIBLE} and the blockstate json carries a model with no
 * elements — only its particle texture, so breaking the machine still puffs the
 * right dust.
 */
public class WeaponModuleMachineBlock extends HorizontalDirectionalBlock
        implements EntityBlock, com.simibubi.create.content.kinetics.base.IRotate {

    /** sliding door state (the door itself is part of the model, not the collision box) */
    public static final BooleanProperty OPEN = BooleanProperty.create("open");

    public WeaponModuleMachineBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPEN);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WeaponModuleMachineBlockEntity(pos, state);
    }

    /** the master is the block entity holder: it ticks the job on both sides */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (type != ModBlockEntities.WEAPON_MODULE_MACHINE.get()) return null;
        return (BlockEntityTicker<T>) (lvl, pos, bstate, be) -> {
            if (be instanceof WeaponModuleMachineBlockEntity machine) {
                machine.tick();
            }
        };
    }

    /**
     * The machine is driven by a Create shaft on its length axis — its own
     * right end (see {@link MachineLayout#shaftFace}); a shaft on the left end
     * works too, it is the same axle. The right face, toward the extension
     * cell, must answer {@code true} as well: that is the internal link the
     * extension's kinetic block relays through, so a shaft outside the machine
     * reaches this block entity.
     */
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
        return MachineLayout.machineShape(state, true);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos master = context.getClickedPos();
        if (!context.getLevel().getBlockState(MachineLayout.slavePos(master, facing))
                .canBeReplaced(context)) {
            return null; // both cells must be free: the machine is one unit
        }
        return this.defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            net.minecraft.world.entity.LivingEntity placer,
                            net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        Direction facing = MachineLayout.facing(state);
        BlockPos slave = MachineLayout.slavePos(pos, facing);
        // the payload (recipe / materials / output / door) rides on the item and
        // is restored by BlockItem.updateCustomBlockEntityTag before this call
        level.setBlock(slave, ModBlocks.WEAPON_MODULE_MACHINE_TOOL_EXTENSION.get()
                .defaultBlockState().setValue(FACING, facing), 3);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        return MachineInteractions.use(state, level, pos, player, hand, hit);
    }

    @Override
    @Deprecated
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos slave = MachineLayout.slavePos(pos, MachineLayout.facing(state));
            if (level.getBlockState(slave).is(ModBlocks.WEAPON_MODULE_MACHINE_TOOL_EXTENSION.get())) {
                level.removeBlock(slave, false);
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * The machine always drops itself as ONE item, contents (recipe, materials,
     * output) included — shulker-style, like the module tuner. No tool
     * requirement on the block properties, so a wrong tool cannot eat the
     * machine's contents.
     */
    @Override
    @Deprecated
    public java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state,
                                                                      net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        net.minecraft.world.item.ItemStack drop = new net.minecraft.world.item.ItemStack(this);
        if (params.getOptionalParameter(
                net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
                instanceof WeaponModuleMachineBlockEntity machine) {
            machine.saveToItem(drop);
        }
        return java.util.List.of(drop);
    }

    /**
     * Non-player removal (explosions, pistons, {@code /setblock}): the other
     * half goes with it — mirror of
     * {@link WeaponModuleMachineExtensionBlock#onRemove}, so neither removing
     * the master nor removing the extension can leave half a machine standing.
     * The drop is the removing code's business ({@link #getDrops} carries the
     * contents).
     */
    @Override
    @Deprecated
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            BlockPos slave = MachineLayout.slavePos(pos, MachineLayout.facing(state));
            if (level.getBlockState(slave).is(ModBlocks.WEAPON_MODULE_MACHINE_TOOL_EXTENSION.get())) {
                level.removeBlock(slave, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
