package dev.ignis.createpneumatictacticals.compat.jei;

import com.simibubi.create.api.registry.CreateRegistries;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.item.ModItems;
import dev.ignis.createpneumatictacticals.item.PodItem;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.core.NonNullList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pod filling/assembly recipes are dynamic CustomRecipes (content item is
 * any registered potato-cannon ammo), invisible to JEI by default. This plugin
 * expands them into one display-only ShapelessRecipe per registered ammo item.
 */
@JeiPlugin
public final class CptJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RegistryAccess access = mc.level.registryAccess();

        Set<ResourceLocation> seen = new HashSet<>();
        List<CraftingRecipe> display = new ArrayList<>();
        access.lookupOrThrow(CreateRegistries.POTATO_PROJECTILE_TYPE)
                .listElements()
                .forEach(ref -> ref.value().items().forEach(holder -> {
                    Item item = holder.value();
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
                    if (!seen.add(itemId)) return;
                    String suffix = itemId.getNamespace() + "_" + itemId.getPath();

                    ItemStack podOut = PodItem.ofContent(item, ModItems.POD.get());
                    display.add(new ShapelessRecipe(
                            new ResourceLocation(CreatePneumaticTacticals.MODID, "jei_pod_filling/" + suffix),
                            "createpneumatictacticals.pod_filling", CraftingBookCategory.MISC, podOut,
                            NonNullList.of(Ingredient.EMPTY,
                                    Ingredient.of(ModItems.AIR_VIAL.get()),
                                    Ingredient.of(item))));

                    ItemStack pressurizedOut = PodItem.ofContent(item, ModItems.PRESSURIZED_POD.get());
                    display.add(new ShapelessRecipe(
                            new ResourceLocation(CreatePneumaticTacticals.MODID, "jei_pod_assembly/" + suffix),
                            "createpneumatictacticals.pod_assembly", CraftingBookCategory.MISC, pressurizedOut,
                            NonNullList.of(Ingredient.EMPTY,
                                    Ingredient.of(podOut.copy()),
                                    Ingredient.of(ModItems.PRESSURIZED_AIR_VIAL.get()))));
                }));
        registration.addRecipes(RecipeTypes.CRAFTING, display);
    }
}