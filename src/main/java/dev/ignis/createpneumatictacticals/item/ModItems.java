package dev.ignis.createpneumatictacticals.item;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.item.crafting.ModRecipes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Item registration. Module items are generated per definition after datapack
 * load; a placeholder set of base items (gun, pod, air vial) is static.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<Item> GUN = ITEMS.register("gun",
            () -> new GeoGunItem(new Item.Properties().stacksTo(1).durability(1000)));

    public static final RegistryObject<Item> POD = ITEMS.register("pod",
            () -> new PodItem(new Item.Properties().stacksTo(16)));

    public static final RegistryObject<Item> AIR_VIAL = ITEMS.register("air_vial",
            () -> new Item(new Item.Properties().stacksTo(16)));

    public static final RegistryObject<Item> PRESSURIZED_AIR_VIAL = ITEMS.register("pressurized_air_vial",
            () -> new Item(new Item.Properties().stacksTo(16)));

    public static final ResourceLocation PRESSURIZED_AIR_VIAL_KEY = new ResourceLocation(CreatePneumaticTacticals.MODID, "pressurized_air_vial");

    public static final ResourceLocation PRESSURIZED_POD_KEY = new ResourceLocation(CreatePneumaticTacticals.MODID, "pressurized_pod");

    public static final ResourceLocation POD_KEY = new ResourceLocation(CreatePneumaticTacticals.MODID, "pod");

    /** Pressurized pod (加压封装弹): pod assembled with a pressurized air vial. */
    public static final RegistryObject<Item> PRESSURIZED_POD = ITEMS.register("pressurized_pod",
            () -> new PodItem(new Item.Properties().stacksTo(16)));

    /** Single module item: the module definition is selected via the ModuleId NBT tag. */
    public static final RegistryObject<Item> MODULE = ITEMS.register("module",
            () -> new ModuleItem(new Item.Properties().stacksTo(16)));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        ModRecipes.register(modBus);
    }
}