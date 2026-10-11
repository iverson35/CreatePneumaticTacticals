package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.block.entity.WeaponModuleMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;

/**
 * Right-click dispatch of the machine tool, shared by BOTH halves (the model
 * overhangs both cells, so a click only tells which cell was hit — the front
 * face normal and the model-space position decide what the player aimed at).
 *
 * <p>Order of intent, all of it on the front face only:
 * <ol>
 *   <li>screen rectangle &rarr; open the recipe picker;</li>
 *   <li>door open + matching material in hand &rarr; feed one item per empty
 *       matching slot;</li>
 *   <li>door open + empty hand + finished module &rarr; take the product;</li>
 *   <li>door open &rarr; close it (and start machining when the parts are all
 *       there);</li>
 *   <li>door closed &rarr; open it.</li>
 * </ol>
 *
 * <p>The client never decides: it returns {@link InteractionResult#SUCCESS} on
 * anything that reaches this method so the click is consumed and the off-hand
 * item cannot act on the same right-click, while the server performs the state
 * change (the same split ModuleTunerBlock documents).
 */
public final class MachineInteractions {

    private MachineInteractions() {
    }

    public static InteractionResult use(BlockState state, Level level, BlockPos hitPos,
                                        Player player, InteractionHand hand, BlockHitResult hit) {
        Direction facing = MachineLayout.facing(state);
        // only the front face is interactive: the back is a wall, the sides are
        // where the machine meets its neighbours
        if (hit.getDirection() != facing) return InteractionResult.PASS;
        BlockPos master = MachineLayout.masterOf(hitPos, state);
        if (!(level.getBlockEntity(master) instanceof WeaponModuleMachineBlockEntity machine)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockState masterState = level.getBlockState(master);
        if (machine.isCrafting()) {
            // a running job locks the whole machine: no door, no clamp, no screen
            return InteractionResult.CONSUME;
        }

        if (MachineLayout.isScreenHit(masterState, master, hit)) {
            if (player instanceof ServerPlayer serverPlayer) {
                NetworkHooks.openScreen(serverPlayer, machine, master);
            }
            return InteractionResult.CONSUME;
        }

        ItemStack held = player.getItemInHand(hand);
        if (masterState.getValue(WeaponModuleMachineBlock.OPEN)) {
            if (machine.insertFromHeld(held) > 0) {
                sound(level, master, SoundEvents.BUNDLE_INSERT);
                return InteractionResult.CONSUME;
            }
            if (held.isEmpty() && machine.hasOutput()) {
                ItemStack out = machine.takeOutput();
                if (!player.getInventory().add(out)) player.drop(out, false);
                sound(level, master, SoundEvents.BUNDLE_REMOVE_ONE);
                return InteractionResult.CONSUME;
            }
            level.setBlockAndUpdate(master, masterState
                    .setValue(WeaponModuleMachineBlock.OPEN, false));
            sound(level, master, SoundEvents.IRON_DOOR_CLOSE);
            machine.tryStartCraft();
            return InteractionResult.CONSUME;
        }

        level.setBlockAndUpdate(master, masterState.setValue(WeaponModuleMachineBlock.OPEN, true));
        sound(level, master, SoundEvents.IRON_DOOR_OPEN);
        return InteractionResult.CONSUME;
    }

    private static void sound(Level level, BlockPos pos, net.minecraft.sounds.SoundEvent event) {
        level.playSound(null, pos, event, SoundSource.BLOCKS, 0.8f, 0.8f);
    }
}
