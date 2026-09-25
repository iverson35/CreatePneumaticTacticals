package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Ammo box item. In-inventory right-click bypasses the stack it acts on:
 * with a pod in hand it inserts the HELD stack into the box item; empty
 * hand pulls one group (or one round sneaking) out of the box item. The
 * content lives in the standard {@code BlockEntityTag} payload, so placing
 * the box restores it through the vanilla path.
 */
public class AmmoBoxBlockItem extends BlockItem {

    public AmmoBoxBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack box = player.getItemInHand(hand);
        ItemStack held = hand == InteractionHand.MAIN_HAND
                ? player.getOffhandItem() : player.getMainHandItem();
        if (!held.isEmpty()) {
            int before = held.getCount();
            if (AmmoBoxBlockEntity.insert(box, held) > 0) {
                level.playSound(null, player.blockPosition(),
                        net.minecraft.sounds.SoundEvents.BUNDLE_INSERT,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 0.8f);
                if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    serverPlayer.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(held.getItem()),
                            before - held.getCount());
                }
                return InteractionResultHolder.sidedSuccess(box, level.isClientSide);
            }
            return InteractionResultHolder.pass(box);
        }
        int want = player.isShiftKeyDown() ? 1
                : Math.max(1, Math.min(AmmoBoxBlockEntity.boxTemplate(box).getMaxStackSize(), 16));
        java.util.List<ItemStack> out = AmmoBoxBlockEntity.take(box, want);
        if (out.isEmpty()) return InteractionResultHolder.pass(box);
        for (ItemStack stack : out) {
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }
        level.playSound(null, player.blockPosition(),
                net.minecraft.sounds.SoundEvents.BUNDLE_REMOVE_ONE,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 0.8f);
        return InteractionResultHolder.sidedSuccess(box, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = BlockItem.getBlockEntityData(stack);
        int rounds = AmmoBoxBlockEntity.roundsIn(stack);
        if (rounds > 0) {
            ItemStack template = AmmoBoxBlockEntity.boxTemplate(stack);
            tooltip.add(Component.translatable(
                    "tooltip.createpneumatictacticals.ammo_box_content",
                    template.getHoverName().getString(), rounds,
                    AmmoBoxBlockEntity.CAPACITY));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
