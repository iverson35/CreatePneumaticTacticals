package dev.ignis.createpneumatictacticals.menu;

import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.item.ModItems;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Pneumatic gun assembly bench.
 *
 * Layout: slot 0 = gun; slots 1..13 = module slots, ordered
 * receiver, feed, supply, barrel, muzzle, handguard, handguard_attachment×4,
 * sight, tactical_sight, stock. All assembly state lives on the gun ItemStack
 * (GunNbt.writeModules); module slots are physical staging that mirrors the
 * gun NBT. Modules are written on placement, removed on pickup. A receiver
 * dropped into an empty bench auto-creates a bare gun. Dependent slots
 * (e.g. muzzle without barrel) are inactive on both sides via Slot.isActive.
 */
public class GunWorkbenchMenu extends AbstractContainerMenu {

    public static final int SLOT_GUN = 0;
    public static final int MODULE_COUNT = 13;
    public static final int SLOT_LAST_MODULE = SLOT_GUN + MODULE_COUNT;
    public static final int INV_START = SLOT_LAST_MODULE + 1;
    public static final int SLOT_COUNT = INV_START + 36;

    /** module slot i (0-based) -> ModuleType */
    private static final ModuleType[] SLOT_TYPES = {
            ModuleType.RECEIVER, ModuleType.FEED, ModuleType.SUPPLY, ModuleType.BARREL,
            ModuleType.MUZZLE, ModuleType.HANDGUARD,
            ModuleType.HANDGUARD_ATTACHMENT, ModuleType.HANDGUARD_ATTACHMENT,
            ModuleType.HANDGUARD_ATTACHMENT, ModuleType.HANDGUARD_ATTACHMENT,
            ModuleType.SIGHT, ModuleType.TACTICAL_SIGHT, ModuleType.STOCK
    };

    /** screen-space x/y per module slot (matches GunWorkbenchScreen layout) */
    private static final int[][] MODULE_SLOT_POS = {
            {56, 16}, {78, 16}, {100, 16}, {122, 16},
            {56, 50}, {78, 50}, {100, 50}, {122, 50}, {144, 50}, {166, 50},
            {56, 84}, {78, 84}, {100, 84}
    };

    private final ContainerLevelAccess access;
    private final SimpleContainer container = new SimpleContainer(SLOT_COUNT);
    private final Player player;
    /** true once the current gun's NBT modules have been materialized into the module slots */
    private boolean modulesLoaded = false;

    /** true while a gun is present; guards auto-creation so the gun can be taken out */
    private boolean hadGun = false;

    public GunWorkbenchMenu(int id, Inventory inventory, FriendlyByteBuf data) {
        this(id, inventory, posFor(inventory, data));
    }

    public GunWorkbenchMenu(int id, Inventory inventory, ContainerLevelAccess access) {
        super(ModMenus.GUN_WORKBENCH.get(), id);
        this.access = access;
        this.player = inventory.player;
        this.container.addListener(c -> this.slotsChanged(this.container));

        this.addSlot(new GunSlot(this.container, SLOT_GUN, 18, 30));
        for (int i = 0; i < MODULE_COUNT; i++) {
            this.addSlot(new ModuleSlot(this.container, SLOT_GUN + 1 + i,
                    MODULE_SLOT_POS[i][0], MODULE_SLOT_POS[i][1], SLOT_TYPES[i]));
        }
        // player inventory
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new InvSlot(inventory, row * 9 + col, 8 + col * 18, 118 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new InvSlot(inventory, 27 + col, 8 + col * 18, 172));
        }
    }

    private static ContainerLevelAccess posFor(Inventory inventory, FriendlyByteBuf data) {
        return ContainerLevelAccess.create(inventory.player.level(), data.readBlockPos());
    }

    public ItemStack getGunStack() {
        return this.container.getItem(SLOT_GUN);
    }

    // --- module resolution ---

    /** Resolves the ModuleDefinition of a module item stack, or null. */
    @Nullable
    public static ModuleDefinition definitionOf(ItemStack stack) {
        if (stack.getItem() instanceof ModuleItem module) {
            ResourceLocation id = module.getModuleId(stack);
            return id == null ? null : ModuleManager.get(id);
        }
        return null;
    }

    /** Dependency of a slot type is currently satisfied (slot-based, mirrors NBT after sync). */
    private boolean depsSatisfied(ModuleType type) {
        return switch (type) {
            case RECEIVER -> true;
            case FEED, SUPPLY, BARREL, HANDGUARD, SIGHT, TACTICAL_SIGHT, STOCK ->
                    !this.container.getItem(SLOT_GUN + 1).isEmpty();
            case MUZZLE -> !this.container.getItem(SLOT_GUN + 4).isEmpty();
            case HANDGUARD_ATTACHMENT -> !this.container.getItem(SLOT_GUN + 6).isEmpty();
        };
    }

    /** Full legality check for placing a candidate module into a module slot. */
    private boolean isValidModule(ModuleType type, ItemStack stack) {
        return rejectReason(type, stack) == null;
    }

    /**
     * Why a module can't go into a slot, as a lang key suffix
     * (gui.createpneumatictacticals.reject.<reason>); null if it can.
     */
    @Nullable
    public String rejectReason(ModuleType type, ItemStack stack) {
        if (!this.depsSatisfied(type)) return "missing_dependency";
        ModuleDefinition def = definitionOf(stack);
        if (def == null || def.type != type) return "wrong_type";
        return GunNbt.validate(GunNbt.readModules(this.container.getItem(SLOT_GUN)), def);
    }

    // --- click handling: keep gun NBT in sync with module slots ---

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        super.clicked(slotId, button, clickType, player);
        if (!player.level().isClientSide) {
            this.syncGun(player);
        }
        this.broadcastChanges();
    }

    /**
     * Server-side: keep the gun NBT equal to the module slot contents.
     * - empty gun + valid receiver staged  -> auto-create bare gun
     * - gun removed -> staged modules are CONSUMED (they now live in the gun
     *   NBT; ejecting them would duplicate every module)
     * - module removed from a slot -> removed from gun NBT on next rebuild
     */
    private void syncGun(Player player) {
        ItemStack gun = this.container.getItem(SLOT_GUN);
        boolean hasGun = gun.getItem() instanceof GunItem;
        this.hadGun |= hasGun;
        if (!hasGun) {
            if (gun.isEmpty() && !this.hadGun) {
                ItemStack receiver = this.container.getItem(SLOT_GUN + 1);
                ModuleDefinition receiverDef = receiver.isEmpty() ? null : definitionOf(receiver);
                if (receiverDef != null && receiverDef.type == ModuleType.RECEIVER) {
                    ItemStack newGun = ModItems.GUN.get().getDefaultInstance();
                    Map<ModuleType, ModuleDefinition> initial = new EnumMap<>(ModuleType.class);
                    initial.put(ModuleType.RECEIVER, receiverDef);
                    GunNbt.writeModules(newGun, initial);
                    if (receiverDef.fireModes != null && !receiverDef.fireModes.isEmpty()) {
                        GunNbt.setFireMode(newGun, receiverDef.fireModes.get(0));
                    }
                    GunNbt.setAmmoCount(newGun, 0);
                    this.container.setItem(SLOT_GUN, newGun);
                    this.modulesLoaded = true;
                    this.hadGun = true;
                }
            }
            if (this.container.getItem(SLOT_GUN).isEmpty()) {
                this.modulesLoaded = false;
                this.consumeModules();
                return;
            }
        }
        // materialize NBT modules into slots ONCE when a gun is placed;
        // afterwards slot edits rebuild the NBT (removing a module from its
        // slot must NOT re-materialize it from the still-stale NBT)
        if (!this.modulesLoaded) {
            Map<ModuleType, ModuleDefinition> nbt = GunNbt.readModules(gun);
            for (int i = 0; i < MODULE_COUNT; i++) {
                int idx = SLOT_GUN + 1 + i;
                ModuleType type = SLOT_TYPES[i];
                ItemStack slotStack = this.container.getItem(idx);
                ModuleDefinition slotDef = slotStack.isEmpty() ? null : definitionOf(slotStack);
                if (slotDef == null || slotDef.type != type) {
                    if (!slotStack.isEmpty()) this.eject(idx, player);
                    ModuleDefinition nbtDef = nbt.get(type);
                    if (nbtDef != null) {
                        this.container.setItem(idx, ModuleItem.of(nbtDef.id));
                    }
                }
            }
            this.modulesLoaded = true;
        }

        // eject modules whose dependency was removed (e.g. receiver taken out)
        for (int i = 0; i < MODULE_COUNT; i++) {
            int idx = SLOT_GUN + 1 + i;
            if (!this.container.getItem(idx).isEmpty() && !this.depsSatisfied(SLOT_TYPES[i])) {
                this.eject(idx, player);
            }
        }

        // rebuild NBT from slots (handguard attachments dedupe to the last filled slot)
        Map<ModuleType, ModuleDefinition> installed = new EnumMap<>(ModuleType.class);
        for (int i = 0; i < MODULE_COUNT; i++) {
            ItemStack slotStack = this.container.getItem(SLOT_GUN + 1 + i);
            if (slotStack.isEmpty()) continue;
            ModuleDefinition def = definitionOf(slotStack);
            if (def != null && def.type == SLOT_TYPES[i]) {
                installed.put(SLOT_TYPES[i], def);
            }
        }
        GunNbt.writeModules(gun, installed);
        this.container.setChanged();
    }

    private void eject(int slotIndex, Player player) {
        ItemStack stack = this.container.getItem(slotIndex);
        if (stack.isEmpty()) return;
        this.container.setItem(slotIndex, ItemStack.EMPTY);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private void ejectModules(Player player) {
        for (int i = 0; i < MODULE_COUNT; i++) {
            this.eject(SLOT_GUN + 1 + i, player);
        }
    }
    /** Drops the staged modules: they are baked into the gun NBT it left with. */
    private void consumeModules() {
        for (int i = 0; i < MODULE_COUNT; i++) {
            this.container.setItem(SLOT_GUN + 1 + i, ItemStack.EMPTY);
        }
    }

    @Override
    public void removed(Player player) {
        if (!player.level().isClientSide) {
            ItemStack gun = this.container.getItem(SLOT_GUN);
            if (!gun.isEmpty()) {
                // gun still staged: hand it over, its modules are in its NBT
                if (!player.getInventory().add(gun)) {
                    player.drop(gun, false);
                }
                this.container.setItem(SLOT_GUN, ItemStack.EMPTY);
                this.consumeModules();
            } else {
                // defensive: no gun was ever assembled, return staged leftovers
                this.ejectModules(player);
            }
        }
        super.removed(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = quickMoveStackInner(player, index);
        // shift-click bypasses clicked(); rebuild gun NBT after any quick move
        // (also consumes staged modules when the gun is shift-taken)
        if (!player.level().isClientSide) {
            this.syncGun(player);
        }
        this.broadcastChanges();
        return result;
    }

    private ItemStack quickMoveStackInner(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index == SLOT_GUN) {
            if (!this.moveItemStackTo(stack, INV_START, SLOT_COUNT, true)) return ItemStack.EMPTY;
        } else if (index <= SLOT_LAST_MODULE) {
            if (!this.moveItemStackTo(stack, INV_START, SLOT_COUNT, true)) return ItemStack.EMPTY;
        } else {
            if (stack.getItem() instanceof GunItem) {
                if (!this.moveItemStackTo(stack, SLOT_GUN, SLOT_GUN + 1, false)) return ItemStack.EMPTY;
            } else {
                ModuleDefinition def = definitionOf(stack);
                boolean placed = false;
                if (def != null) {
                    for (int i = 0; i < MODULE_COUNT; i++) {
                        if (SLOT_TYPES[i] != def.type) continue;
                        int target = SLOT_GUN + 1 + i;
                        if (!this.isValidModule(SLOT_TYPES[i], stack)) continue;
                        int before = stack.getCount();
                        this.moveItemStackTo(stack, target, target + 1, false);
                        if (stack.getCount() < before) {
                            placed = true;
                            break;
                        }
                    }
                }
                if (!placed && !this.moveItemStackTo(stack, INV_START, SLOT_COUNT, true)) {
                    return ItemStack.EMPTY;
                }
            }
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return stack.getCount() == original.getCount() ? ItemStack.EMPTY : original;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.access.evaluate((level, pos) ->
                player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0, true);
    }

    // --- slots ---

    /** The assembled gun. */
    private static class GunSlot extends Slot {
        GunSlot(SimpleContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof GunItem;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** A module slot; inactive (hidden) while its dependency is not installed. */
    public class ModuleSlot extends Slot {
        public final ModuleType type;

        ModuleSlot(SimpleContainer container, int index, int x, int y, ModuleType type) {
            super(container, index, x, y);
            this.type = type;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return GunWorkbenchMenu.this.isValidModule(this.type, stack);
        }

        @Override
        public boolean isActive() {
            return GunWorkbenchMenu.this.depsSatisfied(this.type);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    /** plain player-inventory slot */
    private static class InvSlot extends Slot {
        InvSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }
    }
}