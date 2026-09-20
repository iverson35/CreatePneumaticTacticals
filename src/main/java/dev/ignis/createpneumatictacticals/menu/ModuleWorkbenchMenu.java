package dev.ignis.createpneumatictacticals.menu;

import dev.ignis.createpneumatictacticals.block.entity.ModuleWorkbenchBlockEntity;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.recipe.ModRecipes;
import dev.ignis.createpneumatictacticals.recipe.ModuleCraftingRecipe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessory workbench menu. Two modes share one GUI:
 *  - Crafting: GUI lists recipes from the server's RecipeManager (module id
 *    result set); the screen sends the selected recipe id via
 *    {@code CptNetwork} and the server-side handler calls {@link #craft}.
 *  - Dyeing: place a module in the single dye slot, choose region + color;
 *    confirm (BTN_DYE_CONFIRM via clickMenuButton) consumes one dye and
 *    writes the Colors NBT on the module stack.
 */
public class ModuleWorkbenchMenu extends AbstractContainerMenu {

    /** GUI slot indexes (dye slot first, then player inventory). */
    public static final int DYE_SLOT = 0;
    public static final int PLAYER_INV_START = 1;   // main inventory 27
    public static final int HOTBAR_START = 28;
    public static final int SLOT_COUNT = 37;

    /** button ids for clickMenuButton */
    public static final int BTN_DYE_CONFIRM = 1;

    private final ContainerLevelAccess access;
    private final ModuleWorkbenchBlockEntity blockEntity;

    public ModuleWorkbenchMenu(int id, net.minecraft.world.entity.player.Inventory playerInv,
                               ModuleWorkbenchBlockEntity be) {
        super(CptMenuTypes.MODULE_WORKBENCH.get(), id);
        this.blockEntity = be;
        this.access = ContainerLevelAccess.create(be.getLevel(), be.getBlockPos());

        // dye slot (dyeing tab top-left): module items only
        this.addSlot(new Slot(be.getDyeContainer(), 0, 16, 32) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof ModuleItem;
            }
        });

        // BE region/chosenColor are plain fields with NO network sync —
        // the client GUI showed a stale selection forever (e.g. region stays
        // highlighted after a confirm that reset it server-side, and every
        // later confirm failed silently with "bad selection"). DataSlots
        // sync these two ints with the menu's own change detection.
        this.addDataSlot(new net.minecraft.world.inventory.DataSlot() {
            @Override
            public int get() {
                return blockEntity.getRegion();
            }

            @Override
            public void set(int value) {
                // client receives the authoritative selection
                blockEntity.setRegion(value);
            }
        });
        this.addDataSlot(new net.minecraft.world.inventory.DataSlot() {
            @Override
            public int get() {
                return blockEntity.getChosenColor();
            }

            @Override
            public void set(int value) {
                blockEntity.setChosenColor(value);
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, 9 + row * 9 + col, 8 + col * 18, 123 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 181));
        }
    }

    public ModuleWorkbenchBlockEntity getBlockEntity() {
        return blockEntity;
    }

    public Level getLevel() {
        return blockEntity.getLevel();
    }

    /** All module-crafting recipes, for GUI browsing. */
    public List<ModuleCraftingRecipe> allRecipes(Level level) {
        return new ArrayList<>(level.getRecipeManager().getAllRecipesFor(ModRecipes.MODULE_CRAFTING.get()));
    }

    /** True if the player's inventory can satisfy the recipe ingredients. Creative players always can. */
    public boolean canCraft(Player player, ModuleCraftingRecipe recipe) {
        return player.isCreative() || recipe.matchesInventory(playerInventoryContainer(player));
    }

    private static SimpleContainer playerInventoryContainer(Player player) {
        Inventory inv = player.getInventory();
        SimpleContainer c = new SimpleContainer(inv.items.size());
        for (int i = 0; i < inv.items.size(); i++) {
            c.setItem(i, inv.items.get(i));
        }
        return c;
    }

    private static void consumeIngredients(Player player, ModuleCraftingRecipe recipe) {
        Inventory inv = player.getInventory();
        boolean[] consumed = new boolean[inv.items.size()];
        for (var ing : recipe.getIngredientList()) {
            for (int i = 0; i < inv.items.size(); i++) {
                if (consumed[i]) continue;
                ItemStack stack = inv.items.get(i);
                if (!stack.isEmpty() && ing.test(stack)) {
                    stack.shrink(1);
                    consumed[i] = true;
                    break;
                }
            }
        }
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (button == BTN_DYE_CONFIRM) {
            return handleDyeConfirm(player);
        }
        return false;
    }

    /**
     * Server-validated craft: consumes ingredients from the player's inventory
     * and gives one module item.
     */
    public boolean craft(net.minecraft.server.level.ServerPlayer player, ModuleCraftingRecipe recipe) {
        if (!player.isCreative()) {
            SimpleContainer inv = playerInventoryContainer(player);
            if (!recipe.matchesInventory(inv)) return false;
            consumeIngredients(player, recipe);
        }
        ItemStack result = recipe.resultStack();
        if (!player.getInventory().add(result)) {
            player.drop(result, false);
        }
        player.containerMenu.broadcastChanges();
        return true;
    }

    private boolean handleDyeConfirm(Player player) {
        Level level = getLevel();
        var log = com.mojang.logging.LogUtils.getLogger();
        if (level == null || level.isClientSide) { log.info("dye confirm: bad level"); return false; }
        ItemStack module = blockEntity.getDyeModule();
        if (!blockEntity.hasModule()) { log.info("dye confirm: no module (dyeModule={})", module); return false; }
        int region = blockEntity.getRegion();
        int color = blockEntity.getChosenColor();
        if (region < 0 || region > 2 || color < 0) { log.info("dye confirm: bad selection region={} color={}", region, color); return false; }
        DyeItem dye = DyePalette.forIndex(color);
        if (dye == null) { log.info("dye confirm: no dye for index {}", color); return false; }
        boolean creative = player.isCreative();
        if (!creative && !player.getInventory().items.stream().anyMatch(s -> !s.isEmpty() && s.getItem() == dye)) {
            log.info("dye confirm: dye {} not in inventory", dye);
            return false;
        }
        log.info("dye confirm: OK region={} color={} module={}", region, color, module);

        // consume one dye from inventory (creative: free)
        if (!creative) {
            for (ItemStack stack : player.getInventory().items) {
                if (!stack.isEmpty() && stack.getItem() == dye) {
                    stack.shrink(1);
                    break;
                }
            }
        }
        // write color into module NBT: CompoundTag "Colors" -> moduleId -> int[3]
        ResourceLocation moduleId = ModuleItem.getModuleId(module);
        if (moduleId == null) return false;
        CompoundTag root = module.getOrCreateTag();
        CompoundTag colors = root.getCompound(ModuleItem.TAG_COLORS);
        int[] arr = colors.getIntArray(moduleId.toString());
        if (arr.length < 3) arr = new int[]{-1, -1, -1}; // undyed slots: -1 = keep original
        arr[region] = DyePalette.argbOf(color);
        colors.putIntArray(moduleId.toString(), arr);
        com.mojang.logging.LogUtils.getLogger().info(
                "dye confirm post: module tag = {}", root);
        blockEntity.setRegion(-1);
        blockEntity.setChosenColor(-1);
        blockEntity.setDyeModule(module.copy()); // persist NBT change
        broadcastChanges();
        return true;
    }

    /** Server-side "clear all dyes": rewrite the module's Colors NBT to all -1. */
    public void clearDye(Player player) {
        Level level = getLevel();
        if (level == null || level.isClientSide) return;
        ItemStack module = blockEntity.getDyeModule();
        if (!blockEntity.hasModule()) return;
        ResourceLocation moduleId = ModuleItem.getModuleId(module);
        if (moduleId == null) return;
        CompoundTag root = module.getTag();
        if (root != null && root.contains(ModuleItem.TAG_COLORS, CompoundTag.TAG_COMPOUND)) {
            root.getCompound(ModuleItem.TAG_COLORS).putIntArray(moduleId.toString(), new int[]{-1, -1, -1});
            blockEntity.setDyeModule(module.copy());
            broadcastChanges();
        }
    }
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index == DYE_SLOT) {
            if (!this.moveItemStackTo(stack, PLAYER_INV_START, SLOT_COUNT, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // shift-click a module into the empty dye slot (single).
            // IMPORTANT: go through the container, NOT Slot.set — Slot.set
            // assigns the field directly, SimpleContainer.setChanged never
            // fires, syncFromContainer never runs, and the BE's dyeModule
            // field desyncs from the slot. Dye NBT then lands on a stack
            // object the player can never take out (mergeable with undyed).
            if (stack.getItem() instanceof ModuleItem && !this.slots.get(DYE_SLOT).hasItem()) {
                ItemStack single = stack.copy();
                single.setCount(1);
                this.blockEntity.getDyeContainer().setItem(0, single);
                stack.shrink(1);
                this.broadcastChanges();
                return original;
            }
            if (index < HOTBAR_START) {
                if (!this.moveItemStackTo(stack, HOTBAR_START, SLOT_COUNT, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stack, PLAYER_INV_START, HOTBAR_START, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) ->
                player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0, true);
    }
}