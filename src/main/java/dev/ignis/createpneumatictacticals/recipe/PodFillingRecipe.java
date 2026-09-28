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
 * 封装弹: one vial + one content item (must have a registered
 * PotatoCannonProjectileType) -> the pod tier that vial fills. An air vial
 * fills a plain pod, a pressurized air vial fills a pressurized pod directly
 * (no intermediate plain pod). Shapeless; shown in JEI via the plugin's
 * per-ammo display recipes.
 */
public class PodFillingRecipe extends CustomRecipe {

    public PodFillingRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer inv, Level level) {
        return findFilling(inv, level.registryAccess()) != null;
    }

    @Override
    public ItemStack assemble(CraftingContainer inv, RegistryAccess access) {
        Filling filling = findFilling(inv, access);
        return filling == null ? ItemStack.EMPTY : PodItem.ofContent(filling.content(), filling.pod());
    }

    /** The vial's pod tier and the content item, or null unless exactly one vial + one valid ammo item. */
    @Nullable
    private static Filling findFilling(CraftingContainer inv, RegistryAccess access) {
        Item pod = null;
        Item content = null;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            Item vialPod = podForVial(stack.getItem());
            if (vialPod != null) {
                if (pod != null) return null;
                pod = vialPod;
            } else {
                if (content != null) return null;
                if (PotatoCannonProjectileType.getTypeForItem(access, stack.getItem()).isEmpty()) return null;
                content = stack.getItem();
            }
        }
        return pod != null && content != null ? new Filling(pod, content) : null;
    }

    /** The pod a vial fills into, or null when the item is not a vial. */
    @Nullable
    private static Item podForVial(Item item) {
        if (item == ModItems.AIR_VIAL.get()) return ModItems.POD.get();
        if (item == ModItems.PRESSURIZED_AIR_VIAL.get()) return ModItems.PRESSURIZED_POD.get();
        return null;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.POD_FILLING_SERIALIZER.get();
    }

    private record Filling(Item pod, Item content) {
    }
}
