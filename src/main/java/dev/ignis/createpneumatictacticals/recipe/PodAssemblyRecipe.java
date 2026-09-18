package dev.ignis.createpneumatictacticals.recipe;

import dev.ignis.createpneumatictacticals.item.ModItems;
import dev.ignis.createpneumatictacticals.item.PodItem;
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
 * 加压封装弹: content pod + pressurized air vial -> pressurized pod with the
 * same content NBT (cartridge supply guns only accept these).
 */
public class PodAssemblyRecipe extends CustomRecipe {

    public PodAssemblyRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer inv, Level level) {
        return findPod(inv) != null;
    }

    @Override
    public ItemStack assemble(CraftingContainer inv, RegistryAccess access) {
        ItemStack pod = findPod(inv);
        if (pod == null) return ItemStack.EMPTY;
        ItemStack out = new ItemStack(ModItems.PRESSURIZED_POD.get());
        if (pod.hasTag()) out.setTag(pod.getTag().copy());
        return out;
    }

    /** The content pod in the grid, or null unless exactly one content pod + one pressurized vial. */
    @Nullable
    private static ItemStack findPod(CraftingContainer inv) {
        ItemStack pod = null;
        boolean vial = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == ModItems.POD.get()) {
                if (pod != null) return null;
                if (PodItem.contentId(stack) == null) return null; // empty pod is a dead end
                pod = stack;
            } else if (stack.getItem() == ModItems.PRESSURIZED_AIR_VIAL.get()) {
                if (vial) return null;
                vial = true;
            } else {
                return null;
            }
        }
        return pod != null && vial ? pod : null;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.POD_ASSEMBLY_SERIALIZER.get();
    }
}