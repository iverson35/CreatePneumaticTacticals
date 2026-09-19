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
 * Block entity for the gun assembly bench. PERSISTENTLY holds the staged gun
 * (1 slot): closing the UI keeps it, it leaves only via the UI or by breaking
 * the block. Module slots are menu-session state (rebuilt from the gun's NBT
 * on open, baked back on every edit) — persisting them too would duplicate
 * modules. Deliberately NOT a Container and exposes no IItemHandler
 * capability, so hoppers/pipes cannot touch the gun.
 */
public class GunWorkbenchBlockEntity extends BlockEntity implements MenuProvider {

    private final net.minecraft.world.SimpleContainer gunSlot =
            new net.minecraft.world.SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            GunWorkbenchBlockEntity.this.setChanged();
        }
    };

    /** the bench's persistent gun slot (menu slot 0 binds to this, server side) */
    public net.minecraft.world.SimpleContainer getGunSlot() {
        return this.gunSlot;
    }

    @Override
    protected void saveAdditional(net.minecraft.nbt.CompoundTag tag) {
        super.saveAdditional(tag);
        net.minecraft.world.item.ItemStack gun = this.gunSlot.getItem(0);
        if (!gun.isEmpty()) {
            tag.put("Gun", gun.save(new net.minecraft.nbt.CompoundTag()));
        }
    }

    @Override
    public void load(net.minecraft.nbt.CompoundTag tag) {
        super.load(tag);
        this.gunSlot.setItem(0, tag.contains("Gun")
                ? net.minecraft.world.item.ItemStack.of(tag.getCompound("Gun"))
                : net.minecraft.world.item.ItemStack.EMPTY);
    }

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
                ContainerLevelAccess.create(this.level, this.worldPosition), this.gunSlot);
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}