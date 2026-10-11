package dev.ignis.createpneumatictacticals.block.entity;

import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.block.MachineLayout;
import dev.ignis.createpneumatictacticals.block.WeaponModuleMachineBlock;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.recipe.ModRecipes;
import dev.ignis.createpneumatictacticals.recipe.ModuleCraftingRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

/**
 * State of the weapon module machine tool: selected recipe, the fixed-size
 * material station, the job's progress and the produced module.
 *
 * <h2>Slots</h2>
 * Nine material slots (one per recipe ingredient, one item each — an
 * {@link Ingredient} has no count concept, so a recipe's "total items" is its
 * ingredient list size) plus one output slot. Slot <em>i</em> always means
 * "recipe ingredient i", so slot numbers stay meaningful when the recipe
 * changes: material that the new recipe cannot use is ejected instead of
 * lingering. Because {@link #getMaxStackSize()} is 1, the Forge
 * {@code ITEM_HANDLER} wrapper (and therefore hoppers and Create logistics)
 * feeds exactly one item per slot per operation — a hopper fills every station
 * one item at a time.
 *
 * <h2>Kinetics</h2>
 * The machine is a Create kinetic consumer: a shaft on the machine's length
 * axis (its right end, see {@link MachineLayout#shaftFace}) drives it. The
 * block is an {@code IRotate} whose shaft faces are the two ends of that axis,
 * and this entity is a {@link KineticBlockEntity} whose stress impact is
 * registered as {@code SU per RPM} — so the SU it eats scales with the speed it
 * runs at, exactly like Create's own machines.
 *
 * <h2>Progress</h2>
 * A job is a fixed budget of work ticks ({@link #TICKS_PER_MATERIAL} per
 * ingredient, i.e. the time the machine took before it was kinetic) and each
 * tick adds {@link #craftRate()} — {@code |RPM| / 32}, so one work tick per
 * tick at 32 RPM. No speed, no progress: the job freezes where it is and
 * resumes when the shaft turns again, which is also what happens when the
 * network is overstressed ({@code getSpeed()} reports 0). The counter and the
 * budget are synced; the client advances its own copy with the same rate from
 * the synced RPM, so the render is frame-smooth without a packet per tick.
 */
public class WeaponModuleMachineBlockEntity extends com.simibubi.create.content.kinetics.base.KineticBlockEntity
        implements WorldlyContainer, net.minecraft.world.MenuProvider {

    public static final int MATERIAL_SLOTS = 9;
    public static final int OUTPUT_SLOT = MATERIAL_SLOTS;
    public static final int SLOTS = MATERIAL_SLOTS + 1;

    /** one material item = 60 work ticks of machining at 1x speed (a 4 ingredient recipe takes 12 s at 32 RPM) */
    public static final int TICKS_PER_MATERIAL = 60;

    /** the RPM that means "1x": one work tick per tick, Create's conventional baseline speed */
    public static final float BASELINE_RPM = 32f;

    private static final String TAG_RECIPE = "Recipe";
    private static final String TAG_CRAFT_TICKS = "CraftTicks";
    private static final String TAG_CRAFT_TOTAL = "CraftTotal";

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int[] ALL_SLOTS;

    static {
        ALL_SLOTS = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) ALL_SLOTS[i] = i;
    }

    @Nullable
    private ResourceLocation recipeId;
    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    /** work ticks done in the running job, and the job's total budget; {@code craftTotal == 0} = idle */
    private float craftTicks = 0f;
    private int craftTotal = 0;

    // --- client-side door interpolation (never saved, never synced: which way
    // the door slides is a render detail of the OPEN block state) ---
    public float doorAnimValue;
    public float doorAnimFrom;
    public boolean doorAnimTarget;
    public double doorAnimStart = Double.NEGATIVE_INFINITY;

    // --- client-side mechanism position in px (never saved, never synced: the
    // cutting path is a function of the synced progress, and only the run-in /
    // settle easing needs to remember where it was — see the renderer) ---
    public float mechFixZ;
    public float mechBaseX;
    public float mechBaseY;
    public float mechFromFixZ;
    public float mechFromBaseX;
    public float mechFromBaseY;
    public boolean mechRunning;
    public double mechEaseStart = Double.NEGATIVE_INFINITY;

    public WeaponModuleMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WEAPON_MODULE_MACHINE.get(), pos, state);
    }

    /** a bare kinetic block: no Create behaviours (no brackets, no cogs, no belts) */
    @Override
    public void addBehaviours(java.util.List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
    }

    /** the machine's model is two cells long: draw from the neighbouring cells too */
    @Override
    protected net.minecraft.world.phys.AABB createRenderBoundingBox() {
        return new net.minecraft.world.phys.AABB(worldPosition).inflate(1.0);
    }

    // --- recipe ---

    @Nullable
    public ResourceLocation getRecipeId() {
        return recipeId;
    }

    public boolean hasRecipe() {
        return recipeId != null;
    }

    /** the selected recipe, resolved from the (client- and server-synced) recipe manager */
    @Nullable
    public ModuleCraftingRecipe currentRecipe() {
        if (recipeId == null || level == null) return null;
        return level.getRecipeManager().byKey(recipeId)
                .filter(r -> r instanceof ModuleCraftingRecipe
                        && r.getType() == ModRecipes.MODULE_CRAFTING.get())
                .map(r -> (ModuleCraftingRecipe) r)
                .orElse(null);
    }

    /**
     * Select the recipe to build. Server side only; refused while a job runs
     * (the stations are in use) and for recipes with more ingredients than
     * there are stations — silently truncating those would eat materials.
     * Material the new recipe cannot use is ejected, the rest stays.
     */
    public boolean setRecipe(@Nullable ModuleCraftingRecipe recipe) {
        if (isCrafting()) return false;
        if (recipe != null && recipe.getIngredientList().size() > MATERIAL_SLOTS) {
            LOGGER.warn("Machine tool: recipe {} needs {} stations, only {} exist — refusing",
                    recipe.getId(), recipe.getIngredientList().size(), MATERIAL_SLOTS);
            return false;
        }
        ResourceLocation newId = recipe == null ? null : recipe.getId();
        if (java.util.Objects.equals(newId, recipeId)) return true;   // same recipe: nothing to change
        ejectOutput();
        this.recipeId = newId;
        ejectUnneeded(recipe);
        setChanged();
        return true;
    }

    /** stations the recipe does not use (or uses for a different item) give their item back */
    private void ejectUnneeded(@Nullable ModuleCraftingRecipe recipe) {
        List<Ingredient> ingredients = recipe == null ? List.of() : recipe.getIngredientList();
        for (int i = 0; i < MATERIAL_SLOTS; i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;
            boolean usable = i < ingredients.size() && ingredients.get(i).test(stack);
            if (!usable) {
                items.set(i, ItemStack.EMPTY);
                eject(stack);
            }
        }
    }

    /**
     * Give a finished part still waiting in the output slot back to the world.
     * The clamp holds one thing at a time: a product left in place while a new
     * job runs would be drawn as the new recipe's part (the clamp renders the
     * current recipe), and taking it out would consume the new job's materials
     * on the spot. So both a recipe change and the closing door eject it.
     */
    private void ejectOutput() {
        if (!hasOutput()) return;
        ItemStack out = items.get(OUTPUT_SLOT);
        items.set(OUTPUT_SLOT, ItemStack.EMPTY);
        eject(out);
    }

    /** drop an item in front of the machine, just above the machine's own height */
    private void eject(ItemStack stack) {
        if (level == null || stack.isEmpty()) return;
        Direction facing = MachineLayout.facing(getBlockState());
        net.minecraft.world.Containers.dropItemStack(level,
                worldPosition.getX() + 0.5 + 0.7 * facing.getStepX(),
                worldPosition.getY() + 1.1,
                worldPosition.getZ() + 0.5 + 0.7 * facing.getStepZ(),
                stack);
    }

    // --- material stations ---

    public boolean isCrafting() {
        return craftTotal > 0;
    }

    /**
     * Work ticks per tick at the current speed: 1 at {@link #BASELINE_RPM}
     * (32 RPM), 0 when the machine is not turning — which includes an
     * overstressed network, where {@code getSpeed()} reports 0 as well.
     */
    public float craftRate() {
        return Math.abs(getSpeed()) / BASELINE_RPM;
    }

    /**
     * 0..1 machining progress; 0 while idle. The server's counter is the
     * authority; the client runs its own copy at the same rate (see the class
     * comment), and {@code partialTick} of it is added so the render stays
     * smooth between the 20 Hz ticks.
     */
    public float craftProgress(float partialTick) {
        if (craftTotal <= 0) return 0f;
        float ticks = craftTicks + partialTick * craftRate();
        return net.minecraft.util.Mth.clamp(ticks / craftTotal, 0f, 1f);
    }

    public boolean doorOpen() {
        return getBlockState().getValue(WeaponModuleMachineBlock.OPEN);
    }

    /**
     * Feed the held stack into the stations: one item per empty station whose
     * ingredient matches, in recipe order. Server side only.
     *
     * @return how many items left the hand
     */
    public int insertFromHeld(ItemStack held) {
        ModuleCraftingRecipe recipe = currentRecipe();
        if (recipe == null || isCrafting() || held.isEmpty()) return 0;
        List<Ingredient> ingredients = recipe.getIngredientList();
        int moved = 0;
        for (int i = 0; i < ingredients.size() && !held.isEmpty(); i++) {
            if (!items.get(i).isEmpty()) continue;
            if (!ingredients.get(i).test(held)) continue;
            items.set(i, held.split(1));
            moved++;
        }
        if (moved > 0) setChanged();
        return moved;
    }

    /** every station of the selected recipe holds its item */
    public boolean materialsComplete() {
        ModuleCraftingRecipe recipe = currentRecipe();
        if (recipe == null) return false;
        List<Ingredient> ingredients = recipe.getIngredientList();
        for (int i = 0; i < ingredients.size(); i++) {
            if (items.get(i).isEmpty()) return false;
        }
        return true;
    }

    /**
     * How much of the recipe's raw material is loaded into the clamp, 0..1 — the
     * renderer grows the workpiece's raw block with it while the door is open.
     */
    public float materialsFraction() {
        ModuleCraftingRecipe recipe = currentRecipe();
        if (recipe == null) return 0f;
        int total = recipe.getIngredientList().size();
        if (total <= 0) return 0f;
        int filled = 0;
        for (int i = 0; i < total && i < MATERIAL_SLOTS; i++) {
            if (!items.get(i).isEmpty()) filled++;
        }
        return Math.min(1f, filled / (float) total);
    }

    public boolean hasOutput() {
        return !items.get(OUTPUT_SLOT).isEmpty();
    }

    /** take the finished module out (the product, with its manufacturing rolls) */
    public ItemStack takeOutput() {
        ItemStack out = removeItem(OUTPUT_SLOT, items.get(OUTPUT_SLOT).getCount());
        setChanged();
        return out;
    }

    // --- machining ---

    /**
     * Ask the machine to start: everything the recipe needs must be in place.
     * Called when the door closes and when a redstone signal reaches either half
     * (an automated line can feed the stations and then pulse redstone instead
     * of having to work the door by hand). A product still waiting in the output
     * slot is given back first — the clamp must never hold a finished part and a
     * fresh workpiece at once, and its slot has to be free for this job's part.
     */
    public void tryStartCraft() {
        if (isCrafting() || level == null) return;
        ModuleCraftingRecipe recipe = currentRecipe();
        if (recipe == null || !materialsComplete()) return;
        ejectOutput();
        craftTicks = 0f;
        craftTotal = Math.max(1, recipe.getIngredientList().size() * TICKS_PER_MATERIAL);
        setChanged();
    }

    /**
     * The machine's own tick: Create's kinetic plumbing (network, effects,
     * behaviours) plus the job. A job advances only while the machine is being
     * rotated — no speed, no progress — and the counter is never reset by a
     * stop, so the job resumes exactly where it froze. Both sides run this:
     * the client's counter mirrors the server's from the synced RPM.
     */
    @Override
    public void tick() {
        super.tick();
        if (craftTotal <= 0) return;
        if (craftTicks < craftTotal) {
            float rate = craftRate();
            if (rate <= 0f) return;
            craftTicks = Math.min(craftTotal, craftTicks + rate);
        }
        // the client's counter is a mirror of the server's: only the server finishes
        if (!level.isClientSide && craftTicks >= craftTotal) finishCraft();
    }

    /**
     * Consume the stationed materials and put the product into the output slot.
     * A recipe that disappeared (datapack reload) cancels the job and keeps the
     * materials. The output slot is free by construction: a job only starts on a
     * closed door, and {@link #onDoorClosed()} hands any waiting product back.
     */
    private void finishCraft() {
        ModuleCraftingRecipe recipe = currentRecipe();
        if (recipe == null) {
            craftTicks = 0f;
            craftTotal = 0;
            setChanged();
            return;
        }
        ItemStack result = recipe.resultStack();
        // manufacturing roll: the finished module carries randomized stats,
        // the same distribution the module workbench hands out
        if (level != null) ModuleItem.rollManufactured(result, recipe.getModuleId(), level.random);
        List<Ingredient> ingredients = recipe.getIngredientList();
        for (int i = 0; i < ingredients.size(); i++) items.set(i, ItemStack.EMPTY);
        items.set(OUTPUT_SLOT, result);
        craftTicks = 0f;
        craftTotal = 0;
        setChanged();
    }

    // --- Container / WorldlyContainer (hopper + Forge ITEM_HANDLER surface) ---

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

    /** one item per station: a hopper therefore feeds slot by slot, never stacking */
    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        this.items.clear();
    }

    /** stations accept exactly their recipe's ingredient, and nothing accepts while machining */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot >= MATERIAL_SLOTS || stack.isEmpty()) return false;
        if (isCrafting() || !items.get(slot).isEmpty()) return false;
        ModuleCraftingRecipe recipe = currentRecipe();
        if (recipe == null) return false;
        List<Ingredient> ingredients = recipe.getIngredientList();
        return slot < ingredients.size() && ingredients.get(slot).test(stack);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return ALL_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack,
                                          @Nullable Direction direction) {
        return canPlaceItem(slot, stack);
    }

    /** only the product leaves through automation, and only once it exists */
    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return slot == OUTPUT_SLOT;
    }

    // --- Forge item-handler capability (Create chutes/funnels talk to this,
    // vanilla hoppers use the Container path above) ---

    @SuppressWarnings("unchecked")
    private net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler>[] handlers =
            (net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler>[])
                    (net.minecraftforge.common.util.LazyOptional<?>[]) net.minecraftforge.items.wrapper.SidedInvWrapper.create(
                            this, Direction.values());

    @Override
    public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
            net.minecraftforge.common.capabilities.Capability<T> cap, @Nullable Direction side) {
        if (!this.remove && cap == net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER
                && side != null) {
            for (Direction dir : Direction.values()) {
                if (dir == side) {
                    return handlers[dir.ordinal()].cast();
                }
            }
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        for (net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler> handler : handlers) {
            handler.invalidate();
        }
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        handlers = (net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler>[])
                (net.minecraftforge.common.util.LazyOptional<?>[]) net.minecraftforge.items.wrapper.SidedInvWrapper.create(
                        this, Direction.values());
    }

    // --- menu ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.createpneumatictacticals.weapon_module_machine_tool");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new dev.ignis.createpneumatictacticals.menu.WeaponModuleMachineMenu(id, inv, this);
    }

    // --- persistence / sync ---

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        if (recipeId != null) tag.putString(TAG_RECIPE, recipeId.toString());
        ContainerHelper.saveAllItems(tag, this.items);
        tag.putFloat(TAG_CRAFT_TICKS, this.craftTicks);
        tag.putInt(TAG_CRAFT_TOTAL, this.craftTotal);
    }

    /**
     * Shulker-style drop payload. The door is a <em>block state</em>, not block
     * entity data, so it needs the vanilla {@code BlockStateTag} half of the
     * item tag too: {@code BlockItem.place} replays that through
     * {@code updateBlockStateFromTag} before the block entity is loaded, which
     * is also what makes the restored machine snap its door animation instead
     * of animating from a pose the player never saw.
     */
    @Override
    public void saveToItem(ItemStack stack) {
        super.saveToItem(stack);
        BlockState state = getBlockState();
        if (state.hasProperty(WeaponModuleMachineBlock.OPEN)) {
            stack.getOrCreateTagElement(net.minecraft.world.item.BlockItem.BLOCK_STATE_TAG)
                    .putString(WeaponModuleMachineBlock.OPEN.getName(),
                            String.valueOf(state.getValue(WeaponModuleMachineBlock.OPEN)));
        }
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        // a synced tag always carries the machine's full state: a missing entry
        // means "empty", never "keep what the client had"
        this.recipeId = tag.contains(TAG_RECIPE, CompoundTag.TAG_STRING)
                ? ResourceLocation.tryParse(tag.getString(TAG_RECIPE)) : null;
        this.items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, this.items);
        this.craftTicks = tag.getFloat(TAG_CRAFT_TICKS);
        this.craftTotal = tag.getInt(TAG_CRAFT_TOTAL);
        // the door and the mechanism are NOT touched here. Create funnels both
        // chunk loads and incremental syncs through readClient, so a packet
        // landing the tick the job ends would zero the mechanism mid-motion and
        // snap it home instead of letting the renderer ease it there. Neither
        // needs a reset: a fresh block entity already starts at rest, and the
        // door snaps to its block state on its own first frame (see
        // WeaponModuleMachineRenderer#doorProgress).
    }

    /**
     * The machine renders in world and its recipe/progress live outside the
     * menu, so every state change is pushed to tracking clients (Create's
     * {@code SyncedBlockEntity#sendData}). The job counter is not pushed per
     * tick: clients advance their own copy from the synced RPM, so this fires
     * on insert / eject / start / finish / recipe change / speed change only.
     */
    @Override
    public void setChanged() {
        super.setChanged();
        sendData();
    }
}
