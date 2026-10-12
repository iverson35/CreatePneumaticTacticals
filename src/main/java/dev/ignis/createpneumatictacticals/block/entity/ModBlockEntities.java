package dev.ignis.createpneumatictacticals.block.entity;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.ModBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Block entity registrations.
 *
 * <p>Every block entity here holds items, and Create's schematic pipeline is what
 * keeps them from being duplicated by a blueprint print: the schematicannon, the
 * creative print and the deployer all put a placed block's stored NBT through
 * {@code BlockHelper#prepareBlockEntityData} first, and that returns {@code null}
 * for anything implementing neither {@code PartialSafeNBT} nor a
 * {@code SafeNbtWriter} - so the freshly placed block entity is left untouched and
 * prints empty, however much the blueprint file itself still carries.
 *
 * <p>Keep it that way: implementing {@code PartialSafeNBT} is exactly what would
 * route our NBT through {@code writeSafe} instead. {@link
 * WeaponModuleMachineBlockEntity} already gets that interface from Create's
 * {@code SmartBlockEntity}, and safely: its {@code writeSafe} never reaches the
 * machine's own {@code write}, and its behaviour list is empty (see {@code
 * addBehaviours}), so nothing opts into the safe tag either.
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<BlockEntityType<ModuleWorkbenchBlockEntity>> MODULE_WORKBENCH =
            BLOCK_ENTITIES.register("module_workbench", () -> BlockEntityType.Builder
                    .of(ModuleWorkbenchBlockEntity::new, ModBlocks.MODULE_WORKBENCH.get())
                    .build(null));

    /**
     * The extension half of the machine tool is a plain Create kinetic block:
     * it carries no machine state, it only relays rotation from a shaft
     * attached to the machine's right end through to the master's block entity
     * one cell further in. {@link KineticBlockEntity} is concrete, so no
     * subclass is needed.
     */
    public static final RegistryObject<BlockEntityType<KineticBlockEntity>> WEAPON_MODULE_MACHINE_EXTENSION =
            BLOCK_ENTITIES.register("weapon_module_machine_extension", () -> BlockEntityType.Builder
                    .of((pos, state) -> new KineticBlockEntity(
                                    ModBlockEntities.WEAPON_MODULE_MACHINE_EXTENSION.get(), pos, state),
                            ModBlocks.WEAPON_MODULE_MACHINE_TOOL_EXTENSION.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<ModuleTunerBlockEntity>> MODULE_TUNER =
            BLOCK_ENTITIES.register("module_tuner", () -> BlockEntityType.Builder
                    .of(ModuleTunerBlockEntity::new, ModBlocks.MODULE_TUNER.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<AmmoBoxBlockEntity>> AMMO_BOX =
            BLOCK_ENTITIES.register("ammo_box", () -> BlockEntityType.Builder
                    .of(AmmoBoxBlockEntity::new, ModBlocks.AMMO_BOX.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<WeaponPegboardBlockEntity>> WEAPON_PEGBOARD =
            BLOCK_ENTITIES.register("weapon_pegboard", () -> BlockEntityType.Builder
                    .of(WeaponPegboardBlockEntity::new, ModBlocks.WEAPON_PEGBOARD.get())
                    .build(null));

    public static final RegistryObject<BlockEntityType<WeaponModuleMachineBlockEntity>> WEAPON_MODULE_MACHINE =
            BLOCK_ENTITIES.register("weapon_module_machine_tool", () -> BlockEntityType.Builder
                    .of(WeaponModuleMachineBlockEntity::new, ModBlocks.WEAPON_MODULE_MACHINE_TOOL.get())
                    .build(null));

    public static void register(net.minecraftforge.eventbus.api.IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }

    private ModBlockEntities() {
    }
}