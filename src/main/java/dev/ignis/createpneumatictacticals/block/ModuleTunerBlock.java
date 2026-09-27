package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.ModuleTunerBlockEntity;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Module tuning table (配件调整台). Right-click with a module: lay it on the
 * table. Shift + right-click with an empty hand: take it back. Right-click a
 * table that already holds a module: open the tuning GUI. Breaking keeps the
 * module on the dropped block (shulker-style, via getDrops).
 *
 * <p>Only the server side touches the container: the block entity's slot is
 * not synced to clients outside an open menu, so the client never decides
 * whether there is a module to take.
 */
public class ModuleTunerBlock extends Block implements EntityBlock {

    public ModuleTunerBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ModuleTunerBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        // the client always swings: every branch below is a server decision,
        // and PASS here would let the off-hand item act on the same click
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof ModuleTunerBlockEntity tuner)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);

        // take back: shift + empty hand
        if (player.isShiftKeyDown() && held.isEmpty()) {
            if (!tuner.hasModule()) return InteractionResult.PASS;
            ItemStack module = tuner.getModule().copy();
            tuner.setModule(ItemStack.EMPTY);
            if (!player.getInventory().add(module)) player.drop(module, false);
            sound(level, pos, SoundEvents.BUNDLE_REMOVE_ONE);
            return InteractionResult.CONSUME;
        }

        // lay down: empty table, module in hand
        if (!tuner.hasModule() && held.getItem() instanceof ModuleItem) {
            tuner.setModule(held.split(1));
            sound(level, pos, SoundEvents.BUNDLE_INSERT);
            return InteractionResult.CONSUME;
        }

        // tune: a module is already on the table
        if (tuner.hasModule()) {
            if (player instanceof ServerPlayer serverPlayer) {
                NetworkHooks.openScreen(serverPlayer, tuner, pos);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private static void sound(Level level, BlockPos pos, net.minecraft.sounds.SoundEvent event) {
        level.playSound(null, pos, event, SoundSource.BLOCKS, 0.8f, 0.8f);
    }

    @Override
    @Deprecated
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder drops) {
        BlockEntity be = drops.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof ModuleTunerBlockEntity tuner && tuner.hasModule()) {
            ItemStack drop = new ItemStack(this);
            be.saveToItem(drop);
            return List.of(drop);
        }
        return List.of(new ItemStack(this));
    }
}
