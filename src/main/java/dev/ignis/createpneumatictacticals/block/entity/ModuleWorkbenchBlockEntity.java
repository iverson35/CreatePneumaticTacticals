package dev.ignis.createpneumatictacticals.block.entity;

import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the module placed for dyeing plus the pending color selection.
 * Region index 0-2 (dye region), -1 = none chosen; chosenColor -1 = none.
 * The color is written into the module NBT on confirm from the menu; the
 * server tick detects manual slot swaps and resets the pending selection.
 */
public class ModuleWorkbenchBlockEntity extends BlockEntity
        implements net.minecraft.world.MenuProvider {

    public static final String TAG_DYE_ITEM = "DyeItem";
    public static final String TAG_REGION = "Region";
    public static final String TAG_COLOR = "Color";

    private ItemStack dyeModule = ItemStack.EMPTY;
    private int region = -1;
    private int chosenColor = -1;

    /** One-slot container view backing the menu dye slot. */
    private final SimpleContainer dyeContainer = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            syncFromContainer();
        }
    };

    public ModuleWorkbenchBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MODULE_WORKBENCH.get(), pos, state);
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable("block.createpneumatictacticals.module_workbench");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id,
            net.minecraft.world.entity.player.Inventory inv, net.minecraft.world.entity.player.Player player) {
        return new dev.ignis.createpneumatictacticals.menu.ModuleWorkbenchMenu(id, inv, this);
    }

    /** The container exposed to the menu (single dye slot). */
    public SimpleContainer getDyeContainer() {
        return dyeContainer;
    }

    public ItemStack getDyeModule() {
        return dyeModule;
    }

    public void setDyeModule(ItemStack stack) {
        dyeModule = stack;
        dyeContainer.setItem(0, stack);
        setChanged();
    }

    /** Menu slot changed (player put/took an item): mirror into BE state. */
    void syncFromContainer() {
        ItemStack slotStack = dyeContainer.getItem(0);
        if (!ItemStack.matches(slotStack, dyeModule)) {
            dyeModule = slotStack;
            if (slotStack.isEmpty()) {
                region = -1;
                chosenColor = -1;
            }
            setChanged();
        }
    }

    public int getRegion() {
        return region;
    }

    public void setRegion(int region) {
        this.region = region;
        setChanged();
    }

    public int getChosenColor() {
        return chosenColor;
    }

    public void setChosenColor(int chosenColor) {
        this.chosenColor = chosenColor;
        setChanged();
    }
    public boolean hasModule() {
        return !dyeModule.isEmpty() && dyeModule.getItem() instanceof ModuleItem
                && ModuleItem.getModuleId(dyeModule) != null;
    }

    /** Server tick: if the stored module left the slot manually, reset selection. */
    public static void serverTick(Level level, ModuleWorkbenchBlockEntity be) {
        if (be.dyeModule.isEmpty() && (be.region != -1 || be.chosenColor != -1)) {
            be.region = -1;
            be.chosenColor = -1;
            be.setChanged();
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        dyeModule = tag.contains(TAG_DYE_ITEM) ? ItemStack.of(tag.getCompound(TAG_DYE_ITEM)) : ItemStack.EMPTY;
        dyeContainer.setItem(0, dyeModule);
        region = tag.contains(TAG_REGION) ? tag.getInt(TAG_REGION) : -1;
        chosenColor = tag.contains(TAG_COLOR) ? tag.getInt(TAG_COLOR) : -1;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!dyeModule.isEmpty()) {
            tag.put(TAG_DYE_ITEM, dyeModule.save(new CompoundTag()));
        }
        if (region != -1) tag.putInt(TAG_REGION, region);
        if (chosenColor != -1) tag.putInt(TAG_COLOR, chosenColor);
    }
}