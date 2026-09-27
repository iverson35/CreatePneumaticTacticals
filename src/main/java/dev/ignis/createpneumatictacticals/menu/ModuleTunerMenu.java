package dev.ignis.createpneumatictacticals.menu;

import dev.ignis.createpneumatictacticals.block.entity.ModuleTunerBlockEntity;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.ModuleRoll;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Map;

/**
 * Module tuning table menu: one display-only slot showing the module laid on
 * the block, plus two buttons. 敲击 hands one {@link ModuleRoll#STEP} to each
 * side, spread over that side's rolled attributes in random proportions; 校准
 * takes one from each the same way. Both sides always move the same total, so
 * sum(good) - sum(bad) stays exactly where crafting put it (1) and a module
 * that came out of the crafting roll stays inside the set of rolls the budget
 * can produce.
 *
 * <p>The slot neither accepts nor gives items — the block's own right-click
 * is the only way a module gets on and off the table, so there are no player
 * inventory slots either.
 */
public class ModuleTunerMenu extends AbstractContainerMenu {

    /** menu slot index of the module on the table */
    public static final int TUNER_SLOT = 0;

    /** button ids for clickMenuButton */
    public static final int BTN_KNOCK = 1;
    public static final int BTN_CALIBRATE = 2;

    private final ContainerLevelAccess access;
    private final ModuleTunerBlockEntity blockEntity;

    public ModuleTunerMenu(int id, Inventory playerInv, ModuleTunerBlockEntity be) {
        super(CptMenuTypes.MODULE_TUNER.get(), id);
        this.blockEntity = be;
        this.access = ContainerLevelAccess.create(be.getLevel(), be.getBlockPos());
        // display-only slot; coordinates mirror ModuleTunerScreen.SLOT_X/SLOT_Y
        this.addSlot(new Slot(be.getTunerContainer(), 0, 80, 17) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public boolean mayPickup(Player player) {
                return false;
            }
        });
    }

    public ModuleTunerBlockEntity getBlockEntity() {
        return blockEntity;
    }

    public Level getLevel() {
        return blockEntity.getLevel();
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (button == BTN_KNOCK) return tune(true);
        if (button == BTN_CALIBRATE) return tune(false);
        return false;
    }

    /** Server-side step: one random good and one random bad attribute move by
     *  ModuleRoll.STEP. Returns false when the side has no room left, which is
     *  what greys the button out on the client. */
    private boolean tune(boolean knock) {
        Level level = getLevel();
        if (level == null || level.isClientSide) return false;
        ItemStack stack = blockEntity.getModule();
        if (stack.isEmpty()) return false;
        ModuleDefinition def = ModuleManager.definitionOf(stack);
        if (def == null) return false;
        Map<ModuleRoll.Attr, Double> state = ModuleRoll.state(def, ModuleItem.getRolls(stack));
        Map<ModuleRoll.Attr, Double> next = knock
                ? ModuleRoll.knock(def, state, level.random)
                : ModuleRoll.calibrate(def, state, level.random);
        if (next == null) return false;
        ItemStack tuned = stack.copy();
        ModuleItem.setRolls(tuned, ModuleRoll.toTag(next));
        // fresh stack object: the slot broadcast compares by value, and the
        // client GUI re-reads the fractions straight off the synced stack
        blockEntity.setModule(tuned);
        broadcastChanges();
        return true;
    }

    /** the table holds exactly one item and hands it back through the block,
     *  so shift-clicking has nowhere to go */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) ->
                player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0, true);
    }
}
