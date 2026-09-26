package dev.ignis.createpneumatictacticals.recipe;

import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Receiver disassembly: a BARE gun (receiver only, nothing installed, no
 * rounds loaded) crafted alone in a crafting grid yields the receiver
 * module item back with every receiver attribute intact — manufacturing
 * rolls, dye colors, AW skin descriptor and the hidden flag. This is the
 * inverse of {@link dev.ignis.createpneumatictacticals.menu.WorkbenchAssembler#stageGun}
 * and the only way to recover a receiver (installed receivers can never
 * be pulled: the workbench has no uninstall path for them).
 *
 * The receiver's data moves gun -> item by the same materialize copy the
 * workbench's module removal uses, so a staged-then-disassembled receiver
 * is byte-identical to one that never left the inventory. Leftover ammo
 * (possible when the feed module was already removed) is dropped, not
 * returned — same rule as workbench disassembly.
 */
public class ReceiverDisassemblyRecipe extends CustomRecipe {

    public ReceiverDisassemblyRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer inv, Level level) {
        return findBareGun(inv) != null;
    }

    @Override
    public ItemStack assemble(CraftingContainer inv, RegistryAccess access) {
        ItemStack gun = findBareGun(inv);
        if (gun == null) return ItemStack.EMPTY;
        return disassemble(gun);
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 1;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        return ItemStack.EMPTY; // result depends on the gun's receiver NBT
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public ResourceLocation getId() {
        return super.getId();
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.RECEIVER_DISASSEMBLY_SERIALIZER.get();
    }

    /**
     * The lone bare gun in the grid, or null. "Bare" = the receiver is the
     * only module (no feed/supply/sight/stock/barrel/handguard/charm, no
     * handguard attachments) and the magazine holds no rounds. Any other
     * stack in the grid, or a gun that still has parts installed, disqualifies.
     */
    @Nullable
    private static ItemStack findBareGun(CraftingContainer inv) {
        ItemStack gun = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof GeoGunItem) || gun != null) return null;
            gun = stack;
        }
        if (gun == null || !isBareReceiver(gun)) return null;
        return gun;
    }

    /** receiver-only check shared with the JEI display (docs §2.5/§3.9) */
    public static boolean isBareReceiver(ItemStack gun) {
        var installed = GunNbt.readModules(gun);
        if (installed.size() != 1) return false;
        ModuleDefinition receiver = installed.get(ModuleType.RECEIVER);
        if (receiver == null) return false;
        if (!GunNbt.readHandguardAttachments(gun).isEmpty()) return false;
        return GunNbt.getAmmoCount(gun) == 0;
    }

    /**
     * Gun -> receiver item: the materialize copy mirrors
     * WorkbenchAssembler.removeModule (rolls, colors, skin, hidden flag
     * travel out of the gun's render copies; the item is the authority
     * again once re-staged).
     */
    public static ItemStack disassemble(ItemStack gun) {
        ModuleDefinition receiver = GunNbt.readModules(gun).get(ModuleType.RECEIVER);
        if (receiver == null) return ItemStack.EMPTY;
        ItemStack out = ModuleItem.of(receiver.id);
        java.util.Map<String, net.minecraft.nbt.CompoundTag> rolls = GunNbt.readModuleRolls(gun);
        ModuleItem.setRolls(out, rolls.get(ModuleType.RECEIVER.getSerializedName()));
        int[] colors = GunNbt.getColors(gun, receiver.id);
        if (colors != null && colors.length >= 3) {
            ModuleItem.setDyeColors(out, colors);
        }
        net.minecraft.nbt.CompoundTag skin = GunNbt.getSkin(gun, receiver.id);
        if (skin != null) {
            ModuleItem.setSkinTag(out, skin);
        }
        ModuleItem.setHidden(out, GunNbt.isHidden(gun, receiver.id));
        return out;
    }

    /** receiver of a bare gun, for the JEI display recipes (may be null) */
    @Nullable
    public static ModuleDefinition receiverOf(ItemStack gun) {
        return GunNbt.readModules(gun).get(ModuleType.RECEIVER);
    }
}