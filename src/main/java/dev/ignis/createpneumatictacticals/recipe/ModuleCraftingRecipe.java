package dev.ignis.createpneumatictacticals.recipe;

import com.google.gson.JsonObject;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Data-driven recipe: a list of ingredients consumed from the player inventory
 * produces one module item. Shapeless, matched by ingredient presence only.
 *
 * JSON (data/&lt;ns&gt;/recipes/*.json):
 * {
 *   "type": "createpneumatictacticals:cpt_module_crafting",
 *   "ingredients": [ {"item": "..."}, ... ],
 *   "result": "createpneumatictacticals:light_stock"
 * }
 */
public class ModuleCraftingRecipe implements Recipe<CraftingContainer> {

    private final ResourceLocation id;
    private final List<Ingredient> ingredients;
    private final ResourceLocation moduleId;

    public ModuleCraftingRecipe(ResourceLocation id, List<Ingredient> ingredients, ResourceLocation moduleId) {
        this.id = id;
        this.ingredients = ingredients;
        this.moduleId = moduleId;
    }

    public List<Ingredient> getIngredientList() {
        return ingredients;
    }

    public ResourceLocation getModuleId() {
        return moduleId;
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        return matchesInventory(container);
    }

    /** Ingredient multiset contained in the given container's items? */
    public boolean matchesInventory(net.minecraft.world.Container container) {
        // count needed per ingredient
        boolean[] used = new boolean[container.getContainerSize()];
        for (Ingredient ing : ingredients) {
            boolean found = false;
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (used[i]) continue;
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && ing.test(stack)) {
                    used[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess registryAccess) {
        return resultStack();
    }

    public ItemStack resultStack() {
        return ModuleItem.of(moduleId);
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryAccess) {
        return resultStack();
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.MODULE_CRAFTING_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.MODULE_CRAFTING.get();
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    public static class Serializer implements RecipeSerializer<ModuleCraftingRecipe> {

        @Override
        public ModuleCraftingRecipe fromJson(ResourceLocation id, JsonObject json) {
            List<Ingredient> ingredients = List.of();
            if (json.has("ingredients")) {
                ingredients = java.util.stream.StreamSupport
                        .stream(json.getAsJsonArray("ingredients").spliterator(), false)
                        .map(Ingredient::fromJson)
                        .toList();
            }
            if (ingredients.isEmpty()) throw new IllegalArgumentException("empty ingredients in " + id);
            ResourceLocation result = new ResourceLocation(GsonHelper.getAsString(json, "result"));
            return new ModuleCraftingRecipe(id, ingredients, result);
        }

        @Override
        public ModuleCraftingRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            Ingredient[] ingredients = new Ingredient[count];
            for (int i = 0; i < count; i++) {
                ingredients[i] = Ingredient.fromNetwork(buf);
            }
            ResourceLocation result = buf.readResourceLocation();
            return new ModuleCraftingRecipe(id, List.of(ingredients), result);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, ModuleCraftingRecipe recipe) {
            buf.writeVarInt(recipe.ingredients.size());
            for (Ingredient ing : recipe.ingredients) {
                ing.toNetwork(buf);
            }
            buf.writeResourceLocation(recipe.moduleId);
        }
    }
}