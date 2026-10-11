package dev.ignis.createpneumatictacticals.menu;

import dev.ignis.createpneumatictacticals.block.entity.WeaponModuleMachineBlockEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/**
 * Menu of the machine tool. It carries NO slots: the machine's stations are
 * fed by hand (right-click) and by automation (the block entity's container /
 * {@code ITEM_HANDLER}), and the GUI is only a recipe picker.
 *
 * <p>The menu still exists, because it is what tells the server that this
 * player is looking at THIS machine: {@code SetMachineRecipePacket} is only
 * honoured while the sender has this menu open (plus an 8 block reach check).
 */
public class WeaponModuleMachineMenu extends AbstractContainerMenu {

    private final ContainerLevelAccess access;
    private final WeaponModuleMachineBlockEntity blockEntity;

    public WeaponModuleMachineMenu(int id, Inventory playerInventory, WeaponModuleMachineBlockEntity be) {
        super(CptMenuTypes.WEAPON_MODULE_MACHINE.get(), id);
        this.blockEntity = be;
        this.access = ContainerLevelAccess.create(be.getLevel(), be.getBlockPos());
    }

    public WeaponModuleMachineBlockEntity getBlockEntity() {
        return blockEntity;
    }

    /** nothing of this machine lives in the player's inventory */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) -> player.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0, true);
    }
}
