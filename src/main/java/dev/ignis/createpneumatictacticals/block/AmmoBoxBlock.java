package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Ammo box: a UI-less 512-round single-type pod magazine. Items move by hand
 * (right-click: insert held stack / pull one group / Shift-pull one round)
 * or by automation — the block entity answers the Container interface, so
 * hoppers above drain it and hoppers beside/below feed it. Breaking keeps
 * the content on the dropped item (shulker-style, via getDrops).
 */
public class AmmoBoxBlock extends BaseEntityBlock {

    public AmmoBoxBlock(Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AmmoBoxBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof AmmoBoxBlockEntity box) {
            return AmmoBoxBlockEntity.interact(player, hand, box);
        }
        return InteractionResult.PASS;
    }

    @Override
    @Deprecated
    public java.util.List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder drops) {
        BlockEntity be = drops.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY);
        if (be instanceof AmmoBoxBlockEntity box && !box.isEmpty()) {
            ItemStack drop = new ItemStack(this);
            be.saveToItem(drop);
            return java.util.List.of(drop);
        }
        return java.util.List.of(new ItemStack(this));
    }
}
