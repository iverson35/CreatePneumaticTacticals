package dev.ignis.createpneumatictacticals.network;

import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import com.simibubi.create.content.equipment.potatoCannon.PotatoProjectileEntity;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.PodItem;
import dev.ignis.createpneumatictacticals.module.GunType;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Optional;

/**
 * Server-authoritative fire logic: validates completeness, ammo count, air and
 * gun-type compatibility, spawns Create's PotatoProjectileEntity from the pod
 * content, applies fire rate and recoil values, decrements ammo/air.
 */
public final class GunFireHandler {

    /** ms timestamp of last shot per player; server-side fire rate validation */
    private static final Map<String, Long> LAST_SHOT = new java.util.concurrent.ConcurrentHashMap<>();

    private GunFireHandler() {}

    public static void onFireRequest(ServerPlayer player) {
        ItemStack gun = player.getMainHandItem();
        if (!(gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem)) return;

        GunStats stats = GunStats.of(GunNbt.readModules(gun));
        if (!stats.isComplete()) return;

        ModuleDefinition receiver = stats.receiver;
        ModuleDefinition feed = stats.feed;
        ModuleDefinition supply = stats.supply;

        // --- rate limit ---
        String key = player.getStringUUID();
        long now = player.level().getGameTime();
        String ammoId = GunNbt.getAmmo(gun);
        if (ammoId == null || ammoId.isEmpty()) return;
        AmmoExtension ext = AmmoExtension.get(ammoId);
        long intervalTicks = Math.max(1, (long) (1200.0 / (ext.fireRate * stats.fireRateMultiplier)));
        Long last = LAST_SHOT.get(key);
        if (last != null && now - last < intervalTicks) return;
        LAST_SHOT.put(key, now);

        // --- backpack feed bypasses count; others need rounds in magazine ---
        boolean backpack = feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK;
        if (!backpack) {
            if (GunNbt.getAmmoCount(gun) <= 0) return;
        }

        // --- air ---
        if (supply.supplyType == dev.ignis.createpneumatictacticals.module.SupplyType.INTERNAL_TANK) {
            int air = gun.getDamageValue();
            if (air >= supply.airPerShot) {
                gun.setDamageValue(air - supply.airPerShot);
            } else {
                return;
            }
        }
        // CARTRIDGE type consumes the pod itself (stack shrink below);
        // BACKPACK_TANK consumes nothing.

        // --- find pod in inventory for backpack/cartridge modes ---
        ItemStack pod = null;
        if (backpack || supply.supplyType == dev.ignis.createpneumatictacticals.module.SupplyType.CARTRIDGE) {
            pod = findPod(player, ammoId);
            if (pod == null) return;
        }

        // --- gun type compatibility ---
        if (!receiver.gunType.accepts(ext.gunType)) return;

        // --- spawn projectile (mirrors PotatoCannonItem.use) ---
        Optional<PotatoCannonProjectileType> typeOpt = resolveType(player, ammoId);
        if (typeOpt.isEmpty()) return;
        PotatoCannonProjectileType type = typeOpt.get();

        Vec3 barrelPos = player.getEyePosition().add(player.getLookAngle().scale(0.8));
        Vec3 motion = player.getLookAngle().scale(2 * type.velocityMultiplier() * stats.bulletSpeed);

        for (int i = 0; i < Math.max(1, type.split()); i++) {
            PotatoProjectileEntity projectile = AllEntityTypes.POTATO_PROJECTILE.get().create(player.level());
            if (projectile == null) return;
            // content: backpack/cartridge use the pod itself; magazine mode
            // synthesizes a plain content stack from the selected ammo item
            ItemStack contentStack = pod != null ? pod.copy() : contentFor(player, ammoId);
            if (contentStack == null) return;
            contentStack.setTag(null);
            projectile.setItem(contentStack);
            projectile.setPos(barrelPos.x, barrelPos.y - 0.1, barrelPos.z);
            Vec3 splitMotion = motion;
            if (type.split() > 1) {
                double ang = (Math.PI * 2 / type.split()) * i;
                splitMotion = motion.add(new Vec3(Math.cos(ang), Math.sin(ang), 0).scale(0.1));
            }
            projectile.setDeltaMovement(splitMotion);
            projectile.setOwner(player);
            player.level().addFreshEntity(projectile);
        }

        // --- consume ---
        if (!player.isCreative()) {
            if (backpack || supply.supplyType == dev.ignis.createpneumatictacticals.module.SupplyType.CARTRIDGE) {
                pod.shrink(1);
            } else {
                GunNbt.setAmmoCount(gun, GunNbt.getAmmoCount(gun) - 1);
            }
        }

        // --- sound from receiver definition ---
        if (receiver.fireSound != null) {
            SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(ResourceLocation.tryParse(receiver.fireSound));
            if (sound != null) {
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        sound, SoundSource.PLAYERS, 1.0f, 1.0f);
            }
        }
    }

    private static ItemStack findPod(ServerPlayer player, String ammoId) {
        // Find a pod whose content matches the current ammo type id
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() instanceof PodItem) {
                ResourceLocation content = PodItem.contentId(stack);
                if (content != null && ammoId.startsWith("create:")) {
                    // ammoId references the projectile type; pods store item ids.
                    // Accept pods whose content item maps to this type.
                    var item = player.level().registryAccess()
                            .registryOrThrow(Registries.ITEM).get(content);
                    if (item != null) return stack;
                }
            }
        }
        return null;
    }

    private static Optional<PotatoCannonProjectileType> resolveType(ServerPlayer player, ItemStack contentStack) {
        return PotatoCannonProjectileType.getTypeForItem(player.level().registryAccess(), contentStack.getItem())
                .map(ref -> ref.value());
    }

    private static Optional<PotatoCannonProjectileType> resolveType(ServerPlayer player, String ammoOrItemId) {
        var item = player.level().registryAccess().registryOrThrow(Registries.ITEM)
                .get(ResourceLocation.tryParse(ammoOrItemId));
        if (item == null) return Optional.empty();
        return resolveType(player, new ItemStack(item));
    }

    private static ItemStack contentFor(ServerPlayer player, String itemId) {
        var item = player.level().registryAccess().registryOrThrow(Registries.ITEM)
                .get(ResourceLocation.tryParse(itemId));
        return item == null ? null : new ItemStack(item);
    }
}