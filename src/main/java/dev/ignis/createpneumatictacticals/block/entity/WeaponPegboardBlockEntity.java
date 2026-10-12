package dev.ignis.createpneumatictacticals.block.entity;

import com.simibubi.create.api.contraption.transformable.TransformableBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import dev.ignis.createpneumatictacticals.block.PegboardGrid;
import dev.ignis.createpneumatictacticals.block.PegboardHang;
import dev.ignis.createpneumatictacticals.block.WeaponPegboardBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What is hung on one pegboard block: item plus the hole it hangs on, as a
 * list (a plate has 16 holes, so it can hold up to 16 items). The list is
 * synced to clients in full — the renderer draws it — and drops with the
 * plate (see {@code WeaponPegboardBlock#getDrops}).
 */
public class WeaponPegboardBlockEntity extends BlockEntity implements TransformableBlockEntity {

    public static final String TAG_HANGS = "Hangs";
    public static final String TAG_COUNT = "Count";
    public static final String TAG_ITEM = "Item";
    public static final String TAG_X = "X";
    public static final String TAG_Y = "Y";
    public static final String TAG_VERTICAL = "Vertical";
    public static final String TAG_SIGN = "Sign";

    /** one hung item: stack is always 1; x/y are model-space px hole coordinates */
    public record Hang(ItemStack stack, int x, int y, boolean vertical, int sign) {
    }

    /** equals the hole count, so a full plate can never overfill */
    private static final int MAX_HANGS = 16;

    private final List<Hang> hangs = new ArrayList<>();

    public WeaponPegboardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WEAPON_PEGBOARD.get(), pos, state);
    }

    /** read-only view for the renderer and the drop list */
    public List<Hang> hangs() {
        return Collections.unmodifiableList(this.hangs);
    }

    public boolean occupied(int x, int y) {
        for (Hang hang : this.hangs) {
            if (hang.x() == x && hang.y() == y) return true;
        }
        return false;
    }

    /** hangs {@code stack} (one item) on the hole at x/y, then re-syncs */
    public void addHang(ItemStack stack, int x, int y, boolean vertical, int sign) {
        if (this.hangs.size() >= MAX_HANGS) return;
        this.hangs.add(new Hang(stack, x, y, vertical, sign));
        this.setChanged();
    }

    /**
     * Removes and returns the hang nearest to {@code x}/{@code y} within
     * {@code radiusPx}, or null when the board holds nothing that close.
     */
    @Nullable
    public ItemStack removeNearest(int x, int y, int radiusPx) {
        int nearest = -1;
        double nearestDist = Double.MAX_VALUE;
        for (int i = 0; i < this.hangs.size(); i++) {
            Hang hang = this.hangs.get(i);
            double dx = hang.x() - x;
            double dy = hang.y() - y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist <= radiusPx && dist < nearestDist) {
                nearestDist = dist;
                nearest = i;
            }
        }
        if (nearest < 0) return null;
        ItemStack taken = this.hangs.remove(nearest).stack().copy();
        this.setChanged();
        return taken;
    }

    /**
     * Removes and returns every hang, dropping the board's list. Used by the
     * creative break path, where vanilla's drop pipeline never runs.
     */
    public List<ItemStack> takeAllHangs() {
        if (this.hangs.isEmpty()) return List.of();
        List<ItemStack> out = new ArrayList<>(this.hangs.size());
        for (Hang hang : this.hangs) out.add(hang.stack().copy());
        this.hangs.clear();
        this.setChanged();
        return out;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag list = new ListTag();
        for (Hang hang : this.hangs) {
            CompoundTag entry = new CompoundTag();
            entry.put(TAG_ITEM, hang.stack().save(new CompoundTag()));
            entry.putByte(TAG_X, (byte) hang.x());
            entry.putByte(TAG_Y, (byte) hang.y());
            entry.putBoolean(TAG_VERTICAL, hang.vertical());
            entry.putByte(TAG_SIGN, (byte) hang.sign());
            list.add(entry);
        }
        tag.put(TAG_HANGS, list);
        // ALWAYS recorded: an all-empty NBT is encoded as "no data" on the
        // wire and the client then drops the update entirely, leaving a taken
        // item rendered forever (same lesson as GunWorkbenchBlockEntity)
        tag.putByte(TAG_COUNT, (byte) this.hangs.size());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.hangs.clear();
        ListTag list = tag.getList(TAG_HANGS, Tag.TAG_COMPOUND);
        int limit = Math.min(list.size(), MAX_HANGS);
        for (int i = 0; i < limit; i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains(TAG_ITEM, Tag.TAG_COMPOUND)) continue;
            ItemStack stack = ItemStack.of(entry.getCompound(TAG_ITEM));
            if (stack.isEmpty()) continue;
            // an out-of-range coordinate (older or future build) snaps back
            // onto the hole grid instead of throwing out of a chunk load
            int x = PegboardGrid.snap(entry.getByte(TAG_X));
            int y = PegboardGrid.snap(entry.getByte(TAG_Y));
            this.hangs.add(new Hang(stack, x, y, entry.getBoolean(TAG_VERTICAL),
                    entry.getByte(TAG_SIGN) >= 0 ? 1 : -1));
        }
    }

    // --- client sync: the renderer needs the hangs ---

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
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket pkt) {
        // a packet always carries the board's full state, so "no tag" means
        // an empty board — never "keep whatever the client had"
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

    // --- contraption rotation: Create disassembles a rotated structure through
    // a StructureTransform and hands it to the block entity so stored data can
    // follow. The plate's own state needs none of this (Create rotates it), but
    // the hang list does: a hole is model-space px on the plate's frame and a
    // pose's sign is read along that frame's axes, so both have to be
    // re-expressed in the frame of the state Create re-places the plate with —
    // which is what PegboardHang#follow does, off the transform itself instead
    // of off a table of cases.

    @Override
    public void transform(BlockEntity blockEntity, StructureTransform transform) {
        if (!(blockEntity instanceof WeaponPegboardBlockEntity board)) return;
        board.followStructureTransform(transform);
    }

    private void followStructureTransform(StructureTransform transform) {
        BlockState plate = this.getBlockState();
        for (int i = 0; i < this.hangs.size(); i++) {
            Hang hang = this.hangs.get(i);
            PegboardHang.Placement placement = PegboardHang.follow(transform, plate,
                    hang.x(), hang.y(), hang.vertical(), hang.sign());
            this.hangs.set(i, new Hang(hang.stack(), placement.x(), placement.y(),
                    placement.orient().vertical(), placement.orient().sign()));
        }
        this.setChanged();
    }
}
