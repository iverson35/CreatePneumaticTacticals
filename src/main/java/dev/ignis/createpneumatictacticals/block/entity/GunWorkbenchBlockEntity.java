package dev.ignis.createpneumatictacticals.block.entity;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.Connection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the 3D gun assembly bench. PERSISTENTLY holds the staged
 * gun (1 slot): it leaves only via the [▼] take interaction or by breaking
 * the block. Assembly state lives on the gun ItemStack NBT (GunNbt); there
 * is no GUI and no session mirroring. Deliberately NOT a Container and
 * exposes no IItemHandler capability, so hoppers/pipes cannot touch the gun.
 * getUpdatePacket/getUpdateTag sync the staged gun to the client for the
 * 3D renderer.
 */
public class GunWorkbenchBlockEntity extends BlockEntity {

    private final net.minecraft.world.SimpleContainer gunSlot =
            new net.minecraft.world.SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            GunWorkbenchBlockEntity.this.setChanged();
        }
    };

    /** the bench's persistent gun slot (staged gun; synced to clients) */
    public net.minecraft.world.SimpleContainer getGunSlot() {
        return this.gunSlot;
    }

    @Override
    protected void saveAdditional(net.minecraft.nbt.CompoundTag tag) {
        super.saveAdditional(tag);
        net.minecraft.world.item.ItemStack gun = this.gunSlot.getItem(0);
        // The slot state is ALWAYS recorded. An all-empty NBT is encoded as
        // "no data" on the wire, and the client then drops the update
        // entirely — a take would leave a stale gun rendered forever.
        tag.putBoolean("HasGun", !gun.isEmpty());
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

    // --- client sync: the 3D renderer needs the staged gun ---

    /**
     * REQUIRED override: vanilla's default returns an empty tag, which the
     * network layer encodes as "no data" and the client silently drops.
     */
    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection,
            net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt) {
        // client: replace the staged gun with the synced NBT
        // A packet always carries the bench's full state, so "no tag" means
        // an empty slot — never "keep whatever the client had".
        CompoundTag tag = pkt.getTag();
        this.load(tag != null ? tag : new CompoundTag());
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        this.load(tag);
    }

    /** re-sync to tracking clients on any change (renderer truth) */
    @Override
    public void setChanged() {
        super.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}