package dev.ignis.createpneumatictacticals.block.entity;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Block entity for the gun assembly bench. Holds no data; exists to provide
 * the MenuProvider (and later, machine state if the bench gains processing).
 */
public class GunWorkbenchBlockEntity extends BlockEntity implements MenuProvider {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<BlockEntityType<GunWorkbenchBlockEntity>> GUN_WORKBENCH =
            BLOCK_ENTITIES.register("gun_workbench", () -> BlockEntityType.Builder.of(
                    GunWorkbenchBlockEntity::new, dev.ignis.createpneumatictacticals.block.ModBlocks.GUN_WORKBENCH.get()).build(null));

    public GunWorkbenchBlockEntity(BlockPos pos, BlockState state) {
        super(GUN_WORKBENCH.get(), pos, state);
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable(
                "container." + CreatePneumaticTacticals.MODID + ".gun_workbench");
    }

    @Override
    @Nullable
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new dev.ignis.createpneumatictacticals.menu.GunWorkbenchMenu(id, inventory,
                ContainerLevelAccess.create(this.level, this.worldPosition));
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}