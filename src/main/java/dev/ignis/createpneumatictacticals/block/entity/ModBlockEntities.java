package dev.ignis.createpneumatictacticals.block.entity;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.ModBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Block entity registrations.
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<BlockEntityType<ModuleWorkbenchBlockEntity>> MODULE_WORKBENCH =
            BLOCK_ENTITIES.register("module_workbench", () -> BlockEntityType.Builder
                    .of(ModuleWorkbenchBlockEntity::new, ModBlocks.MODULE_WORKBENCH.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<AmmoBoxBlockEntity>> AMMO_BOX =
            BLOCK_ENTITIES.register("ammo_box", () -> BlockEntityType.Builder
                    .of(AmmoBoxBlockEntity::new, ModBlocks.AMMO_BOX.get())
                    .build(null));

    public static void register(net.minecraftforge.eventbus.api.IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }

    private ModBlockEntities() {
    }
}