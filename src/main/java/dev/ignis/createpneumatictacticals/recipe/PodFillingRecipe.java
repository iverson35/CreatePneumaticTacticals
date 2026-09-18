package dev.ignis.createpneumatictacticals.recipe;

import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import dev.ignis.createpneumatictacticals.item.ModItems;
import dev.ignis.createpneumatictacticals.item.PodItem;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 封装弹: air vial + one content item (must have a registered
 * PotatoCannonProjectileType) -> pod with the content in NBT. Shapeless;
 * shown in JEI via the plugin's per-ammo display recipes.
 */
public class PodFillingRecipe extends CustomRecipe {

    public PodFillingRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer inv, Level level) {
        return findContent(inv, level.registryAccess()) != null;
    }

    @Override
    public ItemStack assemble(CraftingContainer inv, RegistryAccess access) {
        Item content = findContent(inv, access);
        return content == null ? ItemStack.EMPTY : PodItem.ofContent(content, ModItems.POD.get());
    }

    /** The content item, or null unless exactly one vial + one valid ammo item. */
    @Nullable
    private static Item findContent(CraftingContainer inv, RegistryAccess access) {
        boolean vial = false;
        Item content = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() == ModItems.AIR_VIAL.get()) {
                if (vial) return null;
                vial = true;
            } else {
                if (content != null) return null;
                if (PotatoCannonProjectileType.getTypeForItem(access, stack.getItem()).isEmpty()) return null;
                content = stack.getItem();
            }
        }
        return vial ? content : null;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.POD_FILLING_SERIALIZER.get();
    }
}