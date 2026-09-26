package dev.ignis.createpneumatictacticals.recipe;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for the module-crafting recipe type and serializer.
 */
public final class ModRecipes {

    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, CreatePneumaticTacticals.MODID);

    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<RecipeType<ModuleCraftingRecipe>> MODULE_CRAFTING =
            RECIPE_TYPES.register("cpt_module_crafting",
                    () -> RecipeType.simple(new net.minecraft.resources.ResourceLocation(
                            CreatePneumaticTacticals.MODID, "cpt_module_crafting")));

    public static final RegistryObject<net.minecraft.world.item.crafting.RecipeSerializer<ModuleCraftingRecipe>> MODULE_CRAFTING_SERIALIZER =
            RECIPE_SERIALIZERS.register("cpt_module_crafting", ModuleCraftingRecipe.Serializer::new);

    public static final RegistryObject<net.minecraft.world.item.crafting.RecipeSerializer<PodFillingRecipe>> POD_FILLING_SERIALIZER =
            RECIPE_SERIALIZERS.register("pod_filling",
                    () -> new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(PodFillingRecipe::new));

    public static final RegistryObject<net.minecraft.world.item.crafting.RecipeSerializer<PodAssemblyRecipe>> POD_ASSEMBLY_SERIALIZER =
            RECIPE_SERIALIZERS.register("pod_assembly",
                    () -> new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(PodAssemblyRecipe::new));

    public static final RegistryObject<RecipeType<ReceiverDisassemblyRecipe>> RECEIVER_DISASSEMBLY =
            RECIPE_TYPES.register("receiver_disassembly",
                    () -> RecipeType.simple(new net.minecraft.resources.ResourceLocation(
                            CreatePneumaticTacticals.MODID, "receiver_disassembly")));

    public static final RegistryObject<net.minecraft.world.item.crafting.RecipeSerializer<ReceiverDisassemblyRecipe>> RECEIVER_DISASSEMBLY_SERIALIZER =
            RECIPE_SERIALIZERS.register("receiver_disassembly",
                    () -> new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(ReceiverDisassemblyRecipe::new));

    public static void register(net.minecraftforge.eventbus.api.IEventBus modBus) {
        RECIPE_TYPES.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
    }

    private ModRecipes() {
    }
}