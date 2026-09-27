package dev.ignis.createpneumatictacticals.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Module tuning table: holds the single module laid on it. The stack lives in
 * a one-slot container so the menu can show and sync it; the block's own
 * right-click is what moves it in and out. Contents ride along on the dropped
 * block item (BlockEntityTag), so breaking the table never eats the module.
 */
public class ModuleTunerBlockEntity extends BlockEntity
        implements net.minecraft.world.MenuProvider {

    public static final String TAG_ITEM = "Item";

    /** the module on the table; slot 0 is the menu's window onto it */
    private final SimpleContainer tunerContainer = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            ModuleTunerBlockEntity.this.setChanged();
        }
    };

    public ModuleTunerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MODULE_TUNER.get(), pos, state);
    }

    /**
     * The module is rendered in world on top of the table, so clients need the
     * block entity's contents outside an open menu too.
     *
     * <p>REQUIRED override: vanilla's default {@code getUpdateTag} returns an
     * EMPTY tag, which the network layer encodes as "no data" and the client
     * silently drops — the same trap the gun workbench documents. With the
     * empty tag the table only ever refreshed when a menu opened (slot sync).
     */
    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>
    getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
                             net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt) {
        // a packet always carries the table's full state, so "no tag" means an
        // empty table — never "keep whatever the client had"
        CompoundTag tag = pkt.getTag();
        this.load(tag != null ? tag : new CompoundTag());
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        this.load(tag);
    }

    /** re-sync to tracking clients on any change (the module renders in world) */
    @Override
    public void setChanged() {
        super.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.createpneumatictacticals.module_tuner");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new dev.ignis.createpneumatictacticals.menu.ModuleTunerMenu(id, inv, this);
    }

    /** the container exposed to the menu (single tuner slot) */
    public SimpleContainer getTunerContainer() {
        return tunerContainer;
    }

    public ItemStack getModule() {
        return tunerContainer.getItem(0);
    }

    public void setModule(ItemStack stack) {
        tunerContainer.setItem(0, stack);
        setChanged();
    }

    public boolean hasModule() {
        return !getModule().isEmpty();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // an absent item means an empty table: a synced packet always carries
        // the full state, so keeping a stale stack here would leave a ghost
        // module standing on the table after it was taken back
        tunerContainer.setItem(0, tag.contains(TAG_ITEM, Tag.TAG_COMPOUND)
                ? ItemStack.of(tag.getCompound(TAG_ITEM)) : ItemStack.EMPTY);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ItemStack stack = getModule();
        if (!stack.isEmpty()) tag.put(TAG_ITEM, stack.save(new CompoundTag()));
    }
}
