package dev.ignis.createpneumatictacticals.item.crafting;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Recipe serializer registration.
 */
public final class ModRecipes {

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<RecipeSerializer<PodAssemblyRecipe>> POD_ASSEMBLY =
            SERIALIZERS.register("pod_assembly", PodAssemblyRecipe.Serializer::new);

    private ModRecipes() {}

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }
}