package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Block registrations.
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, CreatePneumaticTacticals.MODID);

    public static final DeferredRegister<Item> BLOCK_ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<Block> MODULE_WORKBENCH = BLOCKS.register("module_workbench",
            () -> new ModuleWorkbenchBlock(BlockBehaviour.Properties.of()
                    .strength(2.0F, 6.0F).requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> MODULE_WORKBENCH_ITEM = BLOCK_ITEMS.register("module_workbench",
            () -> new BlockItem(MODULE_WORKBENCH.get(), new Item.Properties()));

    public static final RegistryObject<Block> GUN_WORKBENCH = BLOCKS.register("gun_workbench",
            () -> new GunWorkbenchBlock(BlockBehaviour.Properties.of()
                    .strength(2.0F, 6.0F).requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> GUN_WORKBENCH_ITEM = BLOCK_ITEMS.register("gun_workbench",
            () -> new BlockItem(GUN_WORKBENCH.get(), new Item.Properties()));

    public static void register(net.minecraftforge.eventbus.api.IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ITEMS.register(modBus);
        dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity.register(modBus);
        dev.ignis.createpneumatictacticals.menu.ModMenus.register(modBus);
        dev.ignis.createpneumatictacticals.block.entity.ModBlockEntities.register(modBus);
    }

    private ModBlocks() {
    }
}