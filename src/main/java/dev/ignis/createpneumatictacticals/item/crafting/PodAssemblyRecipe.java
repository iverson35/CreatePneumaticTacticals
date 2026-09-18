package dev.ignis.createpneumatictacticals.item.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ignis.createpneumatictacticals.item.ModItems;
import dev.ignis.createpneumatictacticals.item.PodItem;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * Shapeless assembly recipes whose inputs may be NBT-qualified (pods with a
 * content id, pressurized vial). Two recipes use this serializer:
 *   pod + pressurized air vial -> pressurized pod
 * The JSON declares ordered {@code ingredients} items; matching counts
 * occurrences of each declared item (stack size >= 1) anywhere in the grid.
 */
public class PodAssemblyRecipe extends CustomRecipe {

    public static final String KEY_PRESSED = "Pressed";

    private final NonNullList<ResourceLocation> ingredients;

    public PodAssemblyRecipe(ResourceLocation id, CraftingBookCategory category,
                             NonNullList<ResourceLocation> ingredients) {
        super(id, category);
        this.ingredients = ingredients;
    }

    private static boolean isPressurizedVial(ItemStack stack) {
        return stack.is(ModItems.PRESSURIZED_AIR_VIAL.get());
    }

    /** Marks a pod as pressurized (loaded with pressurized air). */
    public static ItemStack pressurize(ItemStack pod) {
        pod.getOrCreateTag().putBoolean(KEY_PRESSED, true);
        return pod;
    }

    public static boolean isPressurized(ItemStack pod) {
        return pod.hasTag() && pod.getTag().contains(KEY_PRESSED, Tag.TAG_BYTE)
                && pod.getTag().getBoolean(KEY_PRESSED);
    }

    private boolean ingredientMatches(ResourceLocation ing, ItemStack stack) {
        if (ing.equals(ModItems.PRESSURIZED_AIR_VIAL_KEY)) {
            return isPressurizedVial(stack);
        }
        if (ing.equals(ModItems.POD_KEY)) {
            return stack.getItem() instanceof PodItem && !isPressurized(stack);
        }
        if (ing.equals(ModItems.PRESSURIZED_POD_KEY)) {
            return stack.getItem() instanceof PodItem && isPressurized(stack);
        }
        Item item = ForgeRegistries.ITEMS.getValue(ing);
        return item != null && stack.is(item);
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        // count matches per declared ingredient
        java.util.Map<ResourceLocation, Integer> need = new java.util.HashMap<>();
        for (ResourceLocation ing : ingredients) need.merge(ing, 1, Integer::sum);

        java.util.Map<ResourceLocation, Integer> have = new java.util.HashMap<>();
        java.util.Set<ItemStack> matched = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        // greedy: pressurized pod must be matched before plain pod (disjoint ids, but
        // a plain pod also matches the plain-pod ingredient — handle by matching the
        // more specific ingredients first)
        for (ResourceLocation ing : new ResourceLocation[]{
                ModItems.PRESSURIZED_POD_KEY, ModItems.POD_KEY, ModItems.PRESSURIZED_AIR_VIAL_KEY}) {
            int count = need.getOrDefault(ing, 0);
            if (count <= 0) continue;
            for (int i = 0; i < container.getContainerSize() && count > have.getOrDefault(ing, 0); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty() || matched.contains(stack)) continue;
                if (ingredientMatches(ing, stack)) {
                    matched.add(stack);
                    have.merge(ing, 1, Integer::sum);
                }
            }
        }
        // remaining generic (unlisted) ingredients match any item by id
        for (ResourceLocation ing : ingredients) {
            if (ing.equals(ModItems.PRESSURIZED_POD_KEY) || ing.equals(ModItems.POD_KEY)
                    || ing.equals(ModItems.PRESSURIZED_AIR_VIAL_KEY)) continue;
            int count = need.getOrDefault(ing, 0) - have.getOrDefault(ing, 0);
            if (count <= 0) continue;
            for (int i = 0; i < container.getContainerSize() && count > 0; i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty() || matched.contains(stack)) continue;
                if (ingredientMatches(ing, stack)) {
                    matched.add(stack);
                    have.merge(ing, 1, Integer::sum);
                    count--;
                }
            }
        }
        return have.equals(need);
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess registryAccess) {
        ItemStack result = new ItemStack(resultItem());
        boolean podConsumed = false;
        // copy content from the pod input
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.getItem() instanceof PodItem) {
                ResourceLocation content = PodItem.contentId(stack);
                if (content != null) {
                    result.getOrCreateTag().putString(PodItem.KEY_CONTENT, content.toString());
                }
                podConsumed = true;
                break;
            }
        }
        if (!podConsumed) return ItemStack.EMPTY;
        return pressurize(result);
    }

    private Item resultItem() {
        return ModItems.PRESSURIZED_POD.get();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= ingredients.size();
    }

    @Override
    public ItemStack getResultItem(RegistryAccess registryAccess) {
        return new ItemStack(resultItem());
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.POD_ASSEMBLY.get();
    }

    // --- serializer ---

    public static class Serializer implements RecipeSerializer<PodAssemblyRecipe> {

        @Override
        public PodAssemblyRecipe fromJson(ResourceLocation id, JsonObject json) {
            CraftingBookCategory category = CraftingBookCategory.MISC;
            if (json.has("category")) {
                category = CraftingBookCategory.valueOf(GsonHelper.getAsString(json, "category").toUpperCase());
            }
            JsonArray array = GsonHelper.getAsJsonArray(json, "ingredients");
            if (array.size() < 1 || array.size() > 9) {
                throw new IllegalArgumentException("PodAssemblyRecipe " + id + " needs 1..9 ingredients");
            }
            NonNullList<ResourceLocation> list = NonNullList.create();
            for (JsonElement e : array) {
                ResourceLocation rl = ResourceLocation.tryParse(GsonHelper.convertToString(e, "ingredient"));
                if (rl == null) throw new IllegalArgumentException("Bad ingredient id in " + id);
                list.add(rl);
            }
            return new PodAssemblyRecipe(id, category, list);
        }

        @Override
        public PodAssemblyRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            int n = buf.readVarInt();
            NonNullList<ResourceLocation> list = NonNullList.create();
            for (int i = 0; i < n; i++) {
                list.add(buf.readResourceLocation());
            }
            return new PodAssemblyRecipe(id, CraftingBookCategory.MISC, list);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, PodAssemblyRecipe recipe) {
            buf.writeVarInt(recipe.ingredients.size());
            for (ResourceLocation ing : recipe.ingredients) {
                buf.writeResourceLocation(ing);
            }
        }
    }
}