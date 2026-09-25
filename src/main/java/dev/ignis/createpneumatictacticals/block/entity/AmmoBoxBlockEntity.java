package dev.ignis.createpneumatictacticals.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ammo box: a 512-round single-type pod magazine with no GUI.
 *
 * <p>Storage is a 32-slot {@link NonNullList} of pod stacks (cap 16 each);
 * every non-empty slot must be the same pod (item id + full NBT — enforced
 * by {@link #canPlaceItem}). The item form carries the same data under
 * {@code BlockEntityTag} (shulker-style); placement restores it via the
 * vanilla {@code BlockItem.updateCustomBlockEntityTag} path.
 *
 * <p>The full {@link WorldlyContainer} (all six faces) is exposed so
 * hoppers/pipes can push and pull. Right-click bypasses the GUI-less block:
 * insert the held stack / pull one group / Shift-pull a single loose round.
 */
public class AmmoBoxBlockEntity extends BlockEntity implements WorldlyContainer {

    /** total pod capacity of the box, in rounds */
    public static final int CAPACITY = 512;
    /** slots x pod stack cap (16) = 512 */
    public static final int SLOTS = 32;

    private static final String TAG_ITEMS = "Items";
    private static final int[] ALL_SLOTS;

    static {
        ALL_SLOTS = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) ALL_SLOTS[i] = i;
    }

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    public AmmoBoxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AMMO_BOX.get(), pos, state);
    }

    /** first non-empty slot's pod template (item + NBT), or EMPTY when the box is empty */
    public ItemStack template() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }

    /** total rounds currently stored */
    public int rounds() {
        int n = 0;
        for (ItemStack stack : this.items) n += stack.getCount();
        return n;
    }

    /** true when {@code stack} may enter: a pod, and the same pod as the current content */
    public static boolean accepts(ItemStack box, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!isPod(stack)) return false;
        ItemStack template = boxTemplate(box);
        return template.isEmpty() || ItemStack.isSameItemSameTags(template, stack);
    }

    /**
     * Insert {@code stack} into {@code box} (an in-inventory box ITEM, searched
     * for the template) — merges into existing pods first, then fills empty
     * slots. Mutates {@code stack}; returns the inserted count.
     */
    public static int insert(ItemStack box, ItemStack stack) {
        if (!accepts(box, stack)) return 0;
        ListTag list = box.getOrCreateTagElement(net.minecraft.world.item.BlockItem.BLOCK_ENTITY_TAG).getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        return insertInto(list, stack);
    }

    /**
     * Take rounds out of {@code box} into the world: exactly {@code amount}
     * rounds as pod stacks (split at the pod cap), or nothing when the box
     * holds fewer. Mutates the box tag.
     */
    public static java.util.List<ItemStack> take(ItemStack box, int amount) {
        ListTag list = boxTag(box).getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        if (roundsOf(list) < amount) return java.util.List.of();
        return takeFrom(list, amount, box);
    }

    /**
     * Drain up to {@code amount} rounds from {@code box} for reloads: takes
     * whatever is there (may be less than asked). Returns pod stacks.
     */
    public static java.util.List<ItemStack> drain(ItemStack box, int amount) {
        ListTag list = boxTag(box).getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        int have = roundsOf(list);
        if (have <= 0) return java.util.List.of();
        return takeFrom(list, Math.min(amount, have), box);
    }

    /** rounds stored in an in-inventory box item */
    public static int roundsIn(ItemStack box) {
        if (!isBox(box)) return 0;
        return roundsOf(boxTag(box).getList(TAG_ITEMS, Tag.TAG_COMPOUND));
    }

    /** pod template of an in-inventory box item, or EMPTY */
    public static ItemStack boxTemplate(ItemStack box) {
        if (!isBox(box)) return ItemStack.EMPTY;
        ListTag list = boxTag(box).getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.of(list.getCompound(i));
            if (!stack.isEmpty()) return stack;
        }
        return ItemStack.EMPTY;
    }

    /**
     * The projectile TYPE id (e.g. {@code create:potato}) the box feeds, or
     * null when empty/unresolvable. Cartridge guns only draw pressurized
     * pods; the pod item on the template already encodes that.
     */
    @org.jetbrains.annotations.Nullable
    public static String ammoTypeId(net.minecraft.core.RegistryAccess access, ItemStack box) {
        ItemStack template = boxTemplate(box);
        if (template.isEmpty()) return null;
        net.minecraft.world.item.Item content =
                dev.ignis.createpneumatictacticals.item.PodItem.contentItem(template);
        if (content == null) return null;
        return com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType
                .getTypeForItem(access, content)
                .flatMap(ref -> ref.unwrapKey())
                .map(key -> key.location().toString()).orElse(null);
    }

    /** right-click interaction: insert held stack / pull one group / Shift-pull one round */
    public static InteractionResult interact(Player player, InteractionHand hand, AmmoBoxBlockEntity box) {
        ItemStack held = player.getItemInHand(hand);
        ItemStack dummy = dummyOf(box);
        if (!held.isEmpty()) {
            if (!isPod(held)) return InteractionResult.PASS;
            int before = held.getCount();
            ListTag list = boxTag(dummy).getList(TAG_ITEMS, Tag.TAG_COMPOUND);
            int moved = insertInto(list, held);
            if (moved <= 0) return InteractionResult.PASS;
            writeBack(box, dummy);
            sound(player, box, SoundEvents.BUNDLE_INSERT);
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                serverPlayer.awardStat(Stats.ITEM_USED.get(held.getItem()), before - held.getCount());
            }
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }
        int want = player.isShiftKeyDown() ? 1
                : box.template().getMaxStackSize();
        java.util.List<ItemStack> out = take(dummy, want);
        if (out.isEmpty()) return InteractionResult.PASS;
        writeBack(box, dummy);
        for (ItemStack stack : out) {
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }
        sound(player, box, SoundEvents.BUNDLE_REMOVE_ONE);
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    private static void sound(Player player, AmmoBoxBlockEntity box, net.minecraft.sounds.SoundEvent event) {
        player.level().playSound(null, box.getBlockPos(), event, SoundSource.BLOCKS, 0.8f, 0.8f);
    }

    // --- in-inventory tag plumbing (mirrors the BE slot list) ---

    private static CompoundTag boxTag(ItemStack box) {
        return box.getOrCreateTagElement(net.minecraft.world.item.BlockItem.BLOCK_ENTITY_TAG);
    }

    private static int roundsOf(ListTag list) {
        int n = 0;
        for (int i = 0; i < list.size(); i++) n += ItemStack.of(list.getCompound(i)).getCount();
        return n;
    }

    /** merge first, then empty slots; returns the inserted count */
    private static int insertInto(ListTag list, ItemStack stack) {
        int moved = 0;
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            for (int i = 0; i < SLOTS && !stack.isEmpty(); i++) {
                ItemStack slot = i < list.size() ? ItemStack.of(list.getCompound(i)) : ItemStack.EMPTY;
                boolean emptySlot = slot.isEmpty();
                if ((pass == 0) != emptySlot) continue;
                if (!emptySlot && !ItemStack.isSameItemSameTags(slot, stack)) continue;
                int room = Math.min(stack.getMaxStackSize(), 16) - slot.getCount();
                if (room <= 0) continue;
                int take = Math.min(room, stack.getCount());
                slot = emptySlot ? stack.copyWithCount(take) : slot.copyWithCount(slot.getCount() + take);
                if (i < list.size()) list.set(i, slot.save(new CompoundTag()));
                else list.add(slot.save(new CompoundTag()));
                stack.shrink(take);
                moved += take;
            }
        }
        return moved;
    }

    private static java.util.List<ItemStack> takeFrom(ListTag list, int amount, ItemStack box) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        int cap = Math.max(1, Math.min(boxTemplate(box).getMaxStackSize(), 16));
        for (int i = list.size() - 1; i >= 0 && amount > 0; i--) {
            ItemStack slot = ItemStack.of(list.getCompound(i));
            if (slot.isEmpty()) continue;
            int take = Math.min(amount, slot.getCount());
            int left = slot.getCount() - take;
            if (left > 0) list.set(i, slot.copyWithCount(left).save(new CompoundTag()));
            else if (i == list.size() - 1) list.remove(i);
            else list.set(i, ItemStack.EMPTY.save(new CompoundTag()));
            amount -= take;
            while (take > 0) {
                int cut = Math.min(take, cap);
                out.add(slot.copyWithCount(cut));
                take -= cut;
            }
        }
        box.getOrCreateTagElement(net.minecraft.world.item.BlockItem.BLOCK_ENTITY_TAG).put(TAG_ITEMS, list);
        return out;
    }

    private static ItemStack dummyOf(AmmoBoxBlockEntity box) {
        ItemStack dummy = new ItemStack(
                dev.ignis.createpneumatictacticals.block.ModBlocks.AMMO_BOX.get().asItem());
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (ItemStack stack : box.items) {
            if (!stack.isEmpty()) list.add(stack.save(new CompoundTag()));
        }
        tag.put(TAG_ITEMS, list);
        dummy.addTagElement(net.minecraft.world.item.BlockItem.BLOCK_ENTITY_TAG, tag);
        return dummy;
    }

    private static void writeBack(AmmoBoxBlockEntity box, ItemStack dummy) {
        ListTag list = dummy.getOrCreateTagElement(net.minecraft.world.item.BlockItem.BLOCK_ENTITY_TAG)
                .getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(list.size(), SLOTS); i++) items.set(i, ItemStack.of(list.getCompound(i)));
        box.items = items;
        box.setChanged();
    }

    private static boolean isBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem()
                instanceof dev.ignis.createpneumatictacticals.block.AmmoBoxBlockItem;
    }

    private static boolean isPod(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem()
                instanceof dev.ignis.createpneumatictacticals.item.PodItem;
    }

    // --- Container (hopper surface; the GUI-less right-click reuses the same list) ---

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : this.items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack out = ContainerHelper.removeItem(this.items, slot, amount);
        if (!out.isEmpty()) this.setChanged();
        return out;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(this.items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.items.set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        this.setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return 16;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        this.items.clear();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (!isPod(stack) || stack.getCount() + rounds() > CAPACITY) return false;
        ItemStack template = template();
        return template.isEmpty() || ItemStack.isSameItemSameTags(template, stack);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return ALL_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @org.jetbrains.annotations.Nullable Direction dir) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction dir) {
        return true;
    }

    // --- persistence ---

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, this.items);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, this.items);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) this.load(tag);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        this.load(tag);
    }
}
