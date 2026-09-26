package dev.ignis.createpneumatictacticals.menu;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.sound.ModSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Server-side assembly logic for the 3D gun workbench. All state lives on the
 * staged gun ItemStack (GunNbt) — this class holds no session mirroring. The
 * client drives interactions via {@link dev.ignis.createpneumatictacticals
 * .network.Workbench3dPacket}; every entry point validates and replies with an
 * actionbar message on failure.
 *
 * <p>Mount points are identified by the bone name ("loc_barrel", ...) — the
 * client resolved them from geometry; the server never needs geo data.
 */
public final class WorkbenchAssembler {

    private WorkbenchAssembler() {}

    /** the bench block entity at pos, or null (wrong block/not loaded) */
    @Nullable
    public static dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity
    benchAt(net.minecraft.world.level.Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof
                dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity be
                ? be : null;
    }

    // ---------------------------------------------------------------
    // stage: receiver -> auto-create bare gun; assembled gun -> stage as-is;
    // occupied bench -> reject
    // ---------------------------------------------------------------

    public static void stageGun(ServerPlayer player, BlockPos pos, InteractionHandUse hand) {
        var bench = benchAt(player.level(), pos);
        if (bench == null) return;
        ItemStack held = player.getItemInHand(hand.vanilla);
        boolean emptyBench = bench.getGunSlot().getItem(0).isEmpty();
        if (held.getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem) {
            if (!emptyBench) {
                msg(player, "occupied");
                return;
            }
            bench.getGunSlot().setItem(0, held.copyWithCount(1));
            player.setItemInHand(hand.vanilla, ItemStack.EMPTY);
            bench.setChanged();
            return;
        }
        // receiver module item -> bare gun
        dev.ignis.createpneumatictacticals.module.ModuleDefinition receiverDef =
                dev.ignis.createpneumatictacticals.module.ModuleManager.definitionOf(held);
        if (receiverDef == null || receiverDef.type != dev.ignis.createpneumatictacticals.module.ModuleType.RECEIVER) {
            return; // not a receiver: no-op (let vanilla continue)
        }
        if (!emptyBench) {
            msg(player, "occupied");
            return;
        }
        ItemStack newGun = dev.ignis.createpneumatictacticals.item.ModItems.GUN.get().getDefaultInstance();
        Map<dev.ignis.createpneumatictacticals.module.ModuleType,
                dev.ignis.createpneumatictacticals.module.ModuleDefinition> initial = new EnumMap<>(
                dev.ignis.createpneumatictacticals.module.ModuleType.class);
        initial.put(dev.ignis.createpneumatictacticals.module.ModuleType.RECEIVER, receiverDef);
        dev.ignis.createpneumatictacticals.gun.GunNbt.writeModules(newGun, initial,
                new EnumMap<>(dev.ignis.createpneumatictacticals.module.HandguardPosition.class));
        // the receiver item is consumed here: its roll, dye colors, AW skin
        // and hidden flag all move onto the gun (same item-as-authority
        // contract as installModule — the receiver gets the full treatment
        // because it never passes through the install path)
        dev.ignis.createpneumatictacticals.gun.GunNbt.setModuleRolls(newGun,
                dev.ignis.createpneumatictacticals.module.ModuleType.RECEIVER.getSerializedName(),
                dev.ignis.createpneumatictacticals.item.ModuleItem.getRolls(held));
        int[] itemColors = dev.ignis.createpneumatictacticals.item.ModuleItem.getDyeColors(held);
        if (itemColors != null) {
            dev.ignis.createpneumatictacticals.gun.GunNbt.setColor(newGun, receiverDef.id, 0, itemColors[0]);
            dev.ignis.createpneumatictacticals.gun.GunNbt.setColor(newGun, receiverDef.id, 1, itemColors[1]);
            dev.ignis.createpneumatictacticals.gun.GunNbt.setColor(newGun, receiverDef.id, 2, itemColors[2]);
        }
        net.minecraft.nbt.CompoundTag itemSkin =
                dev.ignis.createpneumatictacticals.item.ModuleItem.getSkinTag(held);
        if (itemSkin != null) {
            dev.ignis.createpneumatictacticals.gun.GunNbt.setSkin(newGun, receiverDef.id, itemSkin);
        }
        dev.ignis.createpneumatictacticals.gun.GunNbt.setHidden(newGun, receiverDef.id,
                dev.ignis.createpneumatictacticals.item.ModuleItem.isHidden(held));
        if (receiverDef.gunName != null) {
            newGun.setHoverName(net.minecraft.network.chat.Component.translatable(receiverDef.gunName));
        }
        if (receiverDef.fireModes != null && !receiverDef.fireModes.isEmpty()) {
            dev.ignis.createpneumatictacticals.gun.GunNbt.setFireMode(newGun, receiverDef.fireModes.get(0));
        }
        dev.ignis.createpneumatictacticals.gun.GunNbt.setAmmoCount(newGun, 0);
        bench.getGunSlot().setItem(0, newGun);
        held.shrink(1);
        bench.setChanged();
    }

    // ---------------------------------------------------------------
    // install: validate via GunNbt.validate, write module + colors into NBT
    // ---------------------------------------------------------------

    public static void installModule(ServerPlayer player, BlockPos pos, InteractionHandUse hand,
                                     ResourceLocation mountId) {
        var bench = benchAt(player.level(), pos);
        if (bench == null) return;
        ItemStack gun = bench.getGunSlot().getItem(0);
        if (!(gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem)) return;
        ItemStack held = player.getItemInHand(hand.vanilla);
        var def = dev.ignis.createpneumatictacticals.module.ModuleManager.definitionOf(held);
        if (def == null) return; // not a module: ignore
        // server-side mount check: the held module type must match the
        // clicked mount (mirror of WorkbenchMarkerRenderer.mountType)
        if (def.type == dev.ignis.createpneumatictacticals.module.ModuleType.HANDGUARD_ATTACHMENT) {
            if (handguardPosFromMount(mountId) == null) return;
        } else if (mountTypeOf(mountId) != def.type) {
            return; // desync guard: marker and item disagree
        }
        java.util.Map<dev.ignis.createpneumatictacticals.module.ModuleType,
                dev.ignis.createpneumatictacticals.module.ModuleDefinition> installed =
                dev.ignis.createpneumatictacticals.gun.GunNbt.readModules(gun);
        java.util.Collection<dev.ignis.createpneumatictacticals.module.ModuleDefinition> hgAtt =
                dev.ignis.createpneumatictacticals.gun.GunNbt.readHandguardAttachments(gun).values();
        String reason;
        if (def.type == dev.ignis.createpneumatictacticals.module.ModuleType.HANDGUARD_ATTACHMENT) {
            var mountPos = handguardPosFromMount(mountId);
            if (mountPos == null) return;
            reason = dev.ignis.createpneumatictacticals.gun.GunNbt.validateHandguardAttachment(
                    installed, hgAtt, mountPos, def);
        } else {
            reason = dev.ignis.createpneumatictacticals.gun.GunNbt.validate(installed, hgAtt, def);
        }
        if (reason != null) {
            msg(player, "reject." + reason);
            return;
        }
        if (def.type == dev.ignis.createpneumatictacticals.module.ModuleType.HANDGUARD_ATTACHMENT) {
            var mountPos = handguardPosFromMount(mountId);
            java.util.Map<dev.ignis.createpneumatictacticals.module.HandguardPosition,
                    dev.ignis.createpneumatictacticals.module.ModuleDefinition> atts =
                    new EnumMap<>(dev.ignis.createpneumatictacticals.module.HandguardPosition.class);
            atts.putAll(dev.ignis.createpneumatictacticals.gun.GunNbt.readHandguardAttachments(gun));
            atts.put(mountPos, def);
            dev.ignis.createpneumatictacticals.gun.GunNbt.writeModules(gun, installed, atts);
        } else {
            installed.put(def.type, def);
            dev.ignis.createpneumatictacticals.gun.GunNbt.writeModules(gun, installed,
                    dev.ignis.createpneumatictacticals.gun.GunNbt.readHandguardAttachments(gun));
        }
        // the stack roll travels with the module into the gun NBT
        dev.ignis.createpneumatictacticals.gun.GunNbt.setModuleRolls(gun,
                def.type == dev.ignis.createpneumatictacticals.module.ModuleType.HANDGUARD_ATTACHMENT
                        ? handguardPosFromMount(mountId).slotKey()
                        : def.type.getSerializedName(),
                dev.ignis.createpneumatictacticals.item.ModuleItem.getRolls(held));
        // item-as-dye-authority: undyed held item clears the gun's stale
        // copy; dyed item overwrites it (mirrors the old menu sync)
        int[] itemColors = dev.ignis.createpneumatictacticals.item.ModuleItem.getDyeColors(held);
        if (itemColors != null) {
            dev.ignis.createpneumatictacticals.gun.GunNbt.setColor(gun, def.id, 0, itemColors[0]);
            dev.ignis.createpneumatictacticals.gun.GunNbt.setColor(gun, def.id, 1, itemColors[1]);
            dev.ignis.createpneumatictacticals.gun.GunNbt.setColor(gun, def.id, 2, itemColors[2]);
        } else {
            dev.ignis.createpneumatictacticals.gun.GunNbt.clearColors(gun, def.id);
        }
        // item-as-skin-authority: same contract as the dye colors — an
        // unskinned held item clears the gun's stale copy, a skinned item
        // overwrites it. The whole "ArmourersWorkshop" Compound travels
        // verbatim (skin + its own dye scheme), never parsed here.
        net.minecraft.nbt.CompoundTag itemSkin =
                dev.ignis.createpneumatictacticals.item.ModuleItem.getSkinTag(held);
        if (itemSkin != null) {
            dev.ignis.createpneumatictacticals.gun.GunNbt.setSkin(gun, def.id, itemSkin);
        } else {
            dev.ignis.createpneumatictacticals.gun.GunNbt.clearSkin(gun, def.id);
        }
        // item-as-hidden-authority: unconditional copy (false = clear), so a
        // shown module can never inherit the previous occupant's hidden flag
        dev.ignis.createpneumatictacticals.gun.GunNbt.setHidden(gun, def.id,
                dev.ignis.createpneumatictacticals.item.ModuleItem.isHidden(held));
        held.shrink(1);
        bench.setChanged();
        click(player, pos, ModSoundEvents.MODULE_ASSEMBLE.get());
    }

    // ---------------------------------------------------------------
    // remove: empty hand + [-] marker; module leaves via NBT delete
    // ---------------------------------------------------------------

    public static void removeModule(ServerPlayer player, BlockPos pos, ResourceLocation moduleId) {
        var bench = benchAt(player.level(), pos);
        if (bench == null) return;
        if (!player.getMainHandItem().isEmpty()) return;
        ItemStack gun = bench.getGunSlot().getItem(0);
        if (!(gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem)) return;
        java.util.Map<dev.ignis.createpneumatictacticals.module.ModuleType,
                dev.ignis.createpneumatictacticals.module.ModuleDefinition> installed =
                dev.ignis.createpneumatictacticals.gun.GunNbt.readModules(gun);
        // find and remove
        var removed = (dev.ignis.createpneumatictacticals.module.ModuleDefinition) null;
        String removedKey = null;
        var atts = dev.ignis.createpneumatictacticals.gun.GunNbt.readHandguardAttachments(gun);
        boolean wasAttachment = false;
        for (var e : new java.util.ArrayList<>(atts.entrySet())) {
            if (e.getValue().id.equals(moduleId)) {
                atts.remove(e.getKey());
                removed = e.getValue();
                removedKey = e.getKey().slotKey();
                wasAttachment = true;
            }
        }
        if (!wasAttachment) {
            for (var e : new java.util.ArrayList<>(installed.entrySet())) {
                if (e.getValue().id.equals(moduleId)) {
                    installed.remove(e.getKey());
                    removed = e.getValue();
                    removedKey = e.getKey().getSerializedName();
                }
            }
        }
        if (removed == null) return;
        // rolls are keyed by the same slot keys as the Modules tag: read them
        // before writeModules, which prunes the slots it drops
        java.util.Map<String, net.minecraft.nbt.CompoundTag> rolls =
                dev.ignis.createpneumatictacticals.gun.GunNbt.readModuleRolls(gun);
        dev.ignis.createpneumatictacticals.gun.GunNbt.writeModules(gun, installed, atts);
        // dye copy travels back out of the gun's NBT (item keeps its paid dye)
        ItemStack out = dev.ignis.createpneumatictacticals.item.ModuleItem.of(moduleId);
        dev.ignis.createpneumatictacticals.item.ModuleItem.setRolls(out, rolls.get(removedKey));
        int[] gunColors = dev.ignis.createpneumatictacticals.gun.GunNbt.getColors(gun, moduleId);
        if (gunColors != null && gunColors.length >= 3) {
            dev.ignis.createpneumatictacticals.item.ModuleItem.setDyeColors(out, gunColors);
            dev.ignis.createpneumatictacticals.gun.GunNbt.clearColors(gun, moduleId);
        }
        // skin copy travels back out of the gun's NBT (materialize), then the
        // gun's render copy is dropped — the reinstalled item is the authority
        net.minecraft.nbt.CompoundTag gunSkin =
                dev.ignis.createpneumatictacticals.gun.GunNbt.getSkin(gun, moduleId);
        if (gunSkin != null) {
            dev.ignis.createpneumatictacticals.item.ModuleItem.setSkinTag(out, gunSkin);
            dev.ignis.createpneumatictacticals.gun.GunNbt.clearSkin(gun, moduleId);
        }
        // hidden flag travels back out, then the gun's copy is dropped
        dev.ignis.createpneumatictacticals.item.ModuleItem.setHidden(out,
                dev.ignis.createpneumatictacticals.gun.GunNbt.isHidden(gun, moduleId));
        dev.ignis.createpneumatictacticals.gun.GunNbt.clearHidden(gun, moduleId);
        // dependency ejection mirrors the old menu: removing the barrel drops
        // the muzzle; removing the handguard drops its attachments
        var dependents = dependentsOf(removed, installed, atts);
        for (var dep : dependents) {
            removeInternal(gun, dep.getValue());
            ItemStack depOut = dev.ignis.createpneumatictacticals.item.ModuleItem.of(dep.getValue().id);
            dev.ignis.createpneumatictacticals.item.ModuleItem.setRolls(depOut, rolls.get(dep.getKey()));
            int[] depColors = dev.ignis.createpneumatictacticals.gun.GunNbt.getColors(gun, dep.getValue().id);
            if (depColors != null && depColors.length >= 3) {
                dev.ignis.createpneumatictacticals.item.ModuleItem.setDyeColors(depOut, depColors);
                dev.ignis.createpneumatictacticals.gun.GunNbt.clearColors(gun, dep.getValue().id);
            }
            net.minecraft.nbt.CompoundTag depSkin =
                    dev.ignis.createpneumatictacticals.gun.GunNbt.getSkin(gun, dep.getValue().id);
            if (depSkin != null) {
                dev.ignis.createpneumatictacticals.item.ModuleItem.setSkinTag(depOut, depSkin);
                dev.ignis.createpneumatictacticals.gun.GunNbt.clearSkin(gun, dep.getValue().id);
            }
            dev.ignis.createpneumatictacticals.item.ModuleItem.setHidden(depOut,
                    dev.ignis.createpneumatictacticals.gun.GunNbt.isHidden(gun, dep.getValue().id));
            dev.ignis.createpneumatictacticals.gun.GunNbt.clearHidden(gun, dep.getValue().id);
            give(player, depOut);
        }
        give(player, out);
        bench.setChanged();
        // one click per action: ejecting dependents is part of the same pull
        click(player, pos, ModSoundEvents.MODULE_DISASSEMBLE.get());
    }

    /** modules that must leave with `removed` (installed + atts mutated);
     *  each entry keeps its slot key so its roll can travel back out */
    private static java.util.List<java.util.Map.Entry<String,
            dev.ignis.createpneumatictacticals.module.ModuleDefinition>>
    dependentsOf(dev.ignis.createpneumatictacticals.module.ModuleDefinition removed,
                 java.util.Map<dev.ignis.createpneumatictacticals.module.ModuleType,
                         dev.ignis.createpneumatictacticals.module.ModuleDefinition> installed,
                 java.util.Map<dev.ignis.createpneumatictacticals.module.HandguardPosition,
                         dev.ignis.createpneumatictacticals.module.ModuleDefinition> atts) {
        java.util.List<java.util.Map.Entry<String,
                dev.ignis.createpneumatictacticals.module.ModuleDefinition>> out = new java.util.ArrayList<>();
        switch (removed.type) {
            case BARREL -> {
                var muzzle = installed.remove(dev.ignis.createpneumatictacticals.module.ModuleType.MUZZLE);
                if (muzzle != null) out.add(java.util.Map.entry(
                        dev.ignis.createpneumatictacticals.module.ModuleType.MUZZLE.getSerializedName(), muzzle));
            }
            case HANDGUARD -> {
                for (var e : new java.util.ArrayList<>(atts.entrySet())) {
                    out.add(java.util.Map.entry(e.getKey().slotKey(), e.getValue()));
                    atts.remove(e.getKey());
                }
            }
            case RECEIVER -> {
                // taking the receiver = taking the gun (handled by TAKE)
            }
            default -> {}
        }
        return out;
    }

    private static void removeInternal(ItemStack gun,
                                        dev.ignis.createpneumatictacticals.module.ModuleDefinition def) {
        var installed = dev.ignis.createpneumatictacticals.gun.GunNbt.readModules(gun);
        var atts = dev.ignis.createpneumatictacticals.gun.GunNbt.readHandguardAttachments(gun);
        installed.remove(def.type);
        for (var e : new java.util.ArrayList<>(atts.entrySet())) {
            if (e.getValue().id.equals(def.id)) atts.remove(e.getKey());
        }
        dev.ignis.createpneumatictacticals.gun.GunNbt.writeModules(gun, installed, atts);
    }

    // ---------------------------------------------------------------
    // take: empty hand + [▼] marker; gun leaves with modules baked into NBT
    // ---------------------------------------------------------------

    public static void takeGun(ServerPlayer player, BlockPos pos) {
        var bench = benchAt(player.level(), pos);
        if (bench == null) return;
        if (!player.getMainHandItem().isEmpty()) return;
        ItemStack gun = bench.getGunSlot().getItem(0);
        if (gun.isEmpty()) return;
        bench.getGunSlot().setItem(0, ItemStack.EMPTY);
        give(player, gun);
        bench.setChanged();
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    /** receiver mount bone name -> the module type it accepts (null = unknown) */
    @Nullable
    public static dev.ignis.createpneumatictacticals.module.ModuleType
    mountTypeOf(ResourceLocation mountId) {
        if (!CreatePneumaticTacticals.MODID.equals(mountId.getNamespace())) return null;
        return switch (mountId.getPath()) {
            case "loc_feed" -> dev.ignis.createpneumatictacticals.module.ModuleType.FEED;
            case "loc_supply" -> dev.ignis.createpneumatictacticals.module.ModuleType.SUPPLY;
            case "loc_sight" -> dev.ignis.createpneumatictacticals.module.ModuleType.SIGHT;
            case "loc_sight_side" -> dev.ignis.createpneumatictacticals.module.ModuleType.TACTICAL_SIGHT;
            case "loc_stock" -> dev.ignis.createpneumatictacticals.module.ModuleType.STOCK;
            case "loc_barrel" -> dev.ignis.createpneumatictacticals.module.ModuleType.BARREL;
            case "loc_handguard" -> dev.ignis.createpneumatictacticals.module.ModuleType.HANDGUARD;
            case "loc_charm" -> dev.ignis.createpneumatictacticals.module.ModuleType.CHARM;
            case "loc_muzzle_attachment" -> dev.ignis.createpneumatictacticals.module.ModuleType.MUZZLE;
            default -> null;
        };
    }

    /** handguard attachment mount bone name -> position (null = not a hg mount) */
    @Nullable
    public static dev.ignis.createpneumatictacticals.module.HandguardPosition
    handguardPosFromMount(ResourceLocation mountBoneId) {
        String bone = mountBoneId.getPath();
        return switch (bone) {
            case "loc_handguard_top" -> dev.ignis.createpneumatictacticals.module.HandguardPosition.TOP;
            case "loc_handguard_bottom" -> dev.ignis.createpneumatictacticals.module.HandguardPosition.BOTTOM;
            case "loc_handguard_left" -> dev.ignis.createpneumatictacticals.module.HandguardPosition.LEFT;
            case "loc_handguard_right" -> dev.ignis.createpneumatictacticals.module.HandguardPosition.RIGHT;
            default -> null;
        };
    }

    /** handguard attachment position -> mount bone name */
    public static ResourceLocation mountBoneOf(dev.ignis.createpneumatictacticals.module.HandguardPosition pos) {
        return new ResourceLocation(CreatePneumaticTacticals.MODID,
                "loc_handguard_" + pos.getSerializedName());
    }

    /** bench click at the block centre; loudness comes from the sounds.json
     *  entry, so all nearby players hear it */
    private static void click(ServerPlayer player, BlockPos pos,
                              net.minecraft.sounds.SoundEvent event) {
        player.level().playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                event, SoundSource.BLOCKS, 1.0f, 1.0f);
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static void msg(ServerPlayer player, String keySuffix) {
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "gui." + CreatePneumaticTacticals.MODID + ".reject." + keySuffix), true);
    }

    /** InteractionHand wrapper (avoids importing InteractionHand in the packet twice) */
    public enum InteractionHandUse {
        MAIN(net.minecraft.world.InteractionHand.MAIN_HAND),
        OFF(net.minecraft.world.InteractionHand.OFF_HAND);

        public final net.minecraft.world.InteractionHand vanilla;
        InteractionHandUse(net.minecraft.world.InteractionHand vanilla) {
            this.vanilla = vanilla;
        }

        public static InteractionHandUse of(net.minecraft.world.InteractionHand hand) {
            return hand == net.minecraft.world.InteractionHand.OFF_HAND ? OFF : MAIN;
        }
    }
}