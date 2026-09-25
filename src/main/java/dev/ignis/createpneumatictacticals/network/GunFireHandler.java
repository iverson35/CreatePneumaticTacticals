package dev.ignis.createpneumatictacticals.network;

import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import com.simibubi.create.content.equipment.potatoCannon.PotatoProjectileEntity;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.gun.PosePenalties;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Optional;

/**
 * Server-authoritative fire logic: validates completeness, ammo count, air and
 * gun-type compatibility, spawns Create's PotatoProjectileEntity from the pod
 * content, applies fire rate and recoil values, decrements ammo/air.
 *
 * <p>Two entry points share one core: {@link #onFireRequest} for player
 * input (client click-time camera, clamped), {@link #onMobFire} for mobs
 * (GunAttackGoal: server look vector, no HUD/feedback/backpack/recoil).
 */
public final class GunFireHandler {

    /** ms timestamp of last shot per shooter; server-side fire rate validation */
    private static final Map<String, Long> LAST_SHOT = new java.util.concurrent.ConcurrentHashMap<>();
    /** aim state mirrored from the client (AimStatePacket) for spread suppression */
    private static final Map<String, Boolean> AIMING = new java.util.concurrent.ConcurrentHashMap<>();
    /** accumulated hipfire bloom in degrees per shooter (mirrors client SpreadModel) */
    private static final Map<String, Double> BLOOM = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Random RANDOM = new java.util.Random();
    /** anti-cheat clamp: client-supplied view direction may deviate from the
     * server's own look vector by at most this many degrees (covers the
     * legitimate 20Hz rotation-sync lag with a wide margin) */
    private static final double MAX_DIR_DEVIATION = 15.0;
    /** bloom fully decays after this many ticks without shooting (client: 400ms) */
    private static final long BLOOM_DECAY_TICKS = 8;

    private GunFireHandler() {}

    public static void setAiming(ServerPlayer player, boolean aiming) {
        AIMING.put(player.getStringUUID(), aiming);
    }

    /**
     * Server-authoritative hipfire spread in degrees (mirrors the client
     * SpreadModel; aiming = pinpoint, instantaneous instead of interpolated).
     */
    private static double spreadDegrees(LivingEntity shooter, AmmoExtension ext, GunStats stats) {
        if (AIMING.getOrDefault(shooter.getStringUUID(), false)) return 0;
        String key = shooter.getStringUUID();
        long now = shooter.level().getGameTime();
        double bloom = BLOOM.getOrDefault(key, 0.0);
        Long last = LAST_SHOT.get(key);
        if (last != null) {
            bloom *= Math.max(0, 1 - (now - last) / (double) BLOOM_DECAY_TICKS);
        }
        double raw = ext.spread * PosePenalties.posePenalty(shooter) + bloom;
        return Math.max(0, raw / Math.max(0.1, stats.hipfireAccuracyMultiplier));
    }


    private static void addBloom(LivingEntity shooter, AmmoExtension ext) {
        String key = shooter.getStringUUID();
        BLOOM.put(key, Math.min(2.5 * ext.spread,
                BLOOM.getOrDefault(key, 0.0) + 0.15 * ext.spread));
    }

    /**
     * Rotate the unit {@code dir} by a random angle within a cone of
     * {@code spreadDeg} (uniform over the disc: radius sqrt-sampled, angle
     * uniform). Returns a unit vector when given one; callers normalize
     * again after adding split-pellet ring offsets.
     */
    private static Vec3 applySpread(Vec3 dir, double spreadDeg) {
        if (spreadDeg <= 0) return dir;
        double angle = Math.toRadians(spreadDeg) * Math.sqrt(RANDOM.nextDouble());
        double phi = RANDOM.nextDouble() * Math.PI * 2;
        Vec3 up = Math.abs(dir.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 u = dir.cross(up).normalize();
        Vec3 v = dir.cross(u).normalize();
        return dir.scale(Math.cos(angle))
                .add(u.scale(dir.length() * Math.sin(angle) * Math.cos(phi)))
                .add(v.scale(dir.length() * Math.sin(angle) * Math.sin(phi)));
    }

    /**
     * @param clientEye the shooter's eye position at click time (client frame);
     *                  null for legacy/internal callers
     * @param clientDir the shooter's view direction at click time; clamped to
     *                  within {@link #MAX_DIR_DEVIATION} degrees of the
     *                  server's own look vector so a hacked client cannot
     *                  shoot around corners
     */
    public static void onFireRequest(ServerPlayer player, Vec3 clientEye, Vec3 clientDir) {
        fire(player, clientEye, clientDir);
    }

    /**
     * Mob fire: the gun AI (GunAttackGoal) calls this with the mob's own
     * eye/look vectors. The shared core runs without the player conveniences
     * (client camera trust, HUD feedback, backpack inventory, recoil
     * broadcast). A mob gun that cannot fire (empty magazine, no air,
     * backpack feed without an inventory) is simply silent.
     */
    public static void onMobFire(LivingEntity shooter) {
        fire(shooter, null, null);
    }

    /**
     * The shared fire core; see the class javadoc for the caller contract.
     */
    private static void fire(LivingEntity shooter, Vec3 clientEye, Vec3 clientDir) {
        ItemStack gun = shooter.getMainHandItem();
        if (!(gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem)) return;

        GunStats stats = GunStats.ofGun(gun);

        // ready pose: no firing while elytra flying, or sprinting with a gun
        // too sluggish to stay firing-ready — ergonomics above
        // SPRINT_FIRE_ERGO keeps the gun up (the client mirrors this gate 1:1)
        if (shooter.isFallFlying()) return;
        if (shooter.isSprinting() && GunStats.ergoScale(gun) <= GunStats.SPRINT_FIRE_ERGO) return;

        if (!stats.isComplete()) return;

        ModuleDefinition receiver = stats.receiver;
        ModuleDefinition feed = stats.feed;
        ModuleDefinition supply = stats.supply;

        // --- rate limit bookkeeping (the interval itself is computed below,
        // once the ammo type and its reload_ticks are known) ---
        String key = shooter.getStringUUID();
        long now = shooter.level().getGameTime();
        String ammoId = GunNbt.getAmmo(gun);
        if (ammoId == null || ammoId.isEmpty()) {
            if (shooter instanceof ServerPlayer player) feedback(player, "no_ammo_selected");
            return;
        }
        AmmoExtension ext = AmmoExtension.get(ammoId);
        // fire rate = the ammo's own potato-cannon cadence (reload_ticks),
        // scaled by the gun's fire_rate_multiplier — multiplier 1 is exactly
        // Create-cannon parity. Resolved and gated BEFORE any consumption so
        // spam clicks never drain air or ammo.
        Optional<PotatoCannonProjectileType> typeOpt = resolveType(shooter.level(), ammoId);
        if (typeOpt.isEmpty()) return;
        PotatoCannonProjectileType type = typeOpt.get();
        long intervalTicks = Math.max(1,
                (long) (type.reloadTicks() / stats.fireRateMultiplier));
        Long last = LAST_SHOT.get(key);
        if (last != null && now - last < intervalTicks) return;
        LAST_SHOT.put(key, now);

        // --- backpack feed bypasses count; others need rounds in magazine ---
        boolean backpack = feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK;
        if (!backpack) {
            if (GunNbt.getAmmoCount(gun) <= 0) return; // empty magazine: silent
        }

        // --- air ---
        if (supply.supplyType == dev.ignis.createpneumatictacticals.module.SupplyType.INTERNAL_TANK) {
            int air = gun.getDamageValue();
            if (air >= supply.airPerShot) {
                gun.setDamageValue(air - supply.airPerShot);
            } else {
                if (shooter instanceof ServerPlayer player) feedback(player, "no_air");
                return;
            }
        }
        // CARTRIDGE type consumes the pod itself (stack shrink below);
        // BACKPACK_TANK consumes nothing.

        // --- find a plain pod in inventory for backpack feed (clip-based feeds
        // already consumed their pods at reload; cartridge guns loaded
        // pressurized pods then) ---
        ItemStack pod = null;
        if (backpack) {
            if (shooter instanceof ServerPlayer player) {
                pod = findPod(player, ammoId);
                if (pod == null) {
                    feedback(player, "no_pod");
                    return;
                }
            } else {
                return; // no inventory to feed from: silent
            }
        }
        // --- gun type compatibility ---
        if (!receiver.gunType.accepts(ext.gunType)) {
            if (shooter instanceof ServerPlayer player) feedback(player, "ammo_type_mismatch");
            return;
        }

        // --- spawn projectile (mirrors PotatoCannonItem.use) ---
        double spreadDeg = spreadDegrees(shooter, ext, stats);

        // spread cone apexes at the EYE: sample the angular offset first,
        // put each launch point on its own ray 0.5 blocks out (matches the
        // muzzle-clearance ray), fire along eye -> launchPoint. Spawning
        // everything on the shared axis point instead hinges the cone there,
        // giving (1-R)*tan(θ) deviation and trajectories that don't pass
        // through the eye — close-range shots wrongly collapse toward the
        // crosshair.
        Vec3 eye = shooter.getEyePosition();
        Vec3 look = shooter.getLookAngle();
        if (clientEye != null && clientDir != null && clientDir.lengthSqr() > 1.0E-8) {
            // trust the client's click-time camera (zero-latency crosshair),
            // clamped: position to 2 blocks of the server eye, direction to
            // MAX_DIR_DEVIATION of the server look
            if (clientEye.distanceToSqr(eye) < 4.0) {
                Vec3 d = clientDir.normalize();
                double dot = net.minecraft.util.Mth.clamp(d.dot(look), -1.0, 1.0);
                if (Math.toDegrees(Math.acos(dot)) <= MAX_DIR_DEVIATION) {
                    eye = clientEye;
                    look = d;
                }
            }
        }
        int pellets = Math.max(1, type.split());
        // Vanilla clamps BOTH velocity channels to +-3.9 per axis: the spawn
        // packet (ClientboundAddEntityPacket) and the per-change motion packet
        // (ClientboundSetEntityMotionPacket). Above that the dominant axis is
        // truncated on the client while diagonal components (S * 0.707)
        // survive, bending the visible trajectory toward the 45-degree
        // diagonals, and the replica's own integration falls behind the
        // server's. Both are countered instead of capped: the true launch
        // velocity rides the spawn payload (cpt_vel_*) and the replica refuses
        // the clamped motion sync (EntityMotionMixin), so the launch speed
        // stays exact on both sides at any value.
        double speed = 2 * type.velocityMultiplier() * stats.bulletSpeed;
        // dynamic muzzle distance from the assembled gun's Z-axis bone
        // chain (GunLength; legacy 0.5 when the pack lacks the bones)
        double muzzleDistance = dev.ignis.createpneumatictacticals.gun.GunLength.of(gun);
        for (int i = 0; i < pellets; i++) {
            PotatoProjectileEntity projectile = AllEntityTypes.POTATO_PROJECTILE.get().create(shooter.level());
            if (projectile == null) return;
            // content: backpack/cartridge use the pod itself; magazine mode
            // synthesizes a plain content stack from the selected ammo item
            ItemStack contentStack = pod != null ? pod.copy() : contentFor(shooter.level(), ammoId);
            if (contentStack == null) return;
            contentStack.setTag(null);
            projectile.setItem(contentStack);
            Vec3 dir = applySpread(look, spreadDeg);
            if (pellets > 1) {
                // Create's spray (PotatoCannonItem.use): per-pellet deterministic
                // ring offset + jitter in the plane perpendicular to the shot.
                // Reimplemented here because catnip's VecHelper is not on the
                // compile classpath.
                double ang = Math.toRadians((360.0 / pellets) * i
                        + 360.0 * shooter.getRandom().nextFloat()
                        + 40 * (shooter.getRandom().nextFloat() - 0.5f));
                Vec3 upAxis = Math.abs(dir.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
                Vec3 u = dir.cross(upAxis).normalize();
                Vec3 w = dir.cross(u).normalize();
                dir = dir.add(u.scale(Math.cos(ang) * 0.1)).add(w.scale(Math.sin(ang) * 0.1));
            }
            dir = dir.normalize();
            // launch at the dynamic muzzle distance (receiver->barrel->
            // muzzle-device chain measured from the models' Z-axis bones;
            // 0.5 legacy default when the pack lacks the bones). Cone apex
            // stays at the eye; only the distance along the ray scales.
            Vec3 launch = eye.add(dir.scale(muzzleDistance));
            // setPos anchors the entity ORIGIN (feet), but both the rendered
            // sprite and the hitbox are centered bbHeight/2 (0.125) above it —
            // drop the anchor so the projectile's center sits exactly on the
            // eye ray through the crosshair
            projectile.setPos(launch.x, launch.y - 0.125f, launch.z);
            Vec3 launchVel = dir.scale(speed);
            projectile.setDeltaMovement(launchVel);
            // true launch velocity, mirrored into the spawn payload: the
            // vanilla channels quantize per axis at +-3.9 (see the speed
            // note above), so the client replica needs its own copy
            // (PotatoProjectileMixin restores it on readSpawnData)
            projectile.getPersistentData().putDouble("cpt_vel_x", launchVel.x);
            projectile.getPersistentData().putDouble("cpt_vel_y", launchVel.y);
            projectile.getPersistentData().putDouble("cpt_vel_z", launchVel.z);
            // mark gun-fired projectiles: the hit/bounce/explosion runtime
            // (PotatoProjectileMixin) keys off cpt_gunshot and reads the ammo
            // extension via cpt_ammo; cpt_dmg carries the gun's damage multiplier
            projectile.getPersistentData().putBoolean("cpt_gunshot", true);
            projectile.getPersistentData().putString("cpt_ammo", ammoId);
            projectile.getPersistentData().putDouble("cpt_dmg", stats.damageMultiplier);
            // exterior ballistics: consumed per tick by PotatoProjectileMixin
            // (scales the ammo type's gravity accel / air drag). Both are
            // also carried in addAdditionalSaveData so the client replica
            // ticks the identical trajectory.
            projectile.getPersistentData().putDouble("cpt_gravity", stats.gravityMultiplier);
            projectile.getPersistentData().putDouble("cpt_drag", stats.dragMultiplier);
            projectile.setOwner(shooter);
            shooter.level().addFreshEntity(projectile);
        }

        // --- consume: clip always decrements (creative included); backpack
        // pod shrink and reload-time pod consumption stay creative-free ---
        addBloom(shooter, ext);
        if (backpack) {
            if (!(shooter instanceof ServerPlayer player) || !player.isCreative()) pod.shrink(1);
        } else {
            GunNbt.setAmmoCount(gun, GunNbt.getAmmoCount(gun) - 1);
        }

        // --- cartridge supply ejects a casing: simulate its landing and
        // schedule the shell_drop click there (ShellDropScheduler) ---
        if (supply.supplyType == dev.ignis.createpneumatictacticals.module.SupplyType.CARTRIDGE) {
            dev.ignis.createpneumatictacticals.gun.ShellDropScheduler
                    .onCartridgeShot((net.minecraft.server.level.ServerLevel) shooter.level(), shooter);
        }

        // --- sound: receiver-defined, defaulting to the potato cannon's
        // FWOOMP; the shooter already heard it client-side (instant
        // feedback), so exclude them from the broadcast. Pitch follows the
        // ammo's sound_pitch (Create potato projectile type) unless the
        // receiver opts out via ignore_ammo_pitch ---
        String soundId = receiver.fireSound != null ? receiver.fireSound : "create:fwoomp";
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(ResourceLocation.tryParse(soundId));
        if (sound != null) {
            float pitch = receiver.ignoreAmmoPitch ? 1.0f : type.soundPitch();
            // the shooting player already heard the shot client-side (instant
            // feedback), so exclude only them; a mob shooter is heard by
            // everyone, its target included
            SoundSource source = shooter instanceof ServerPlayer
                    ? SoundSource.PLAYERS : SoundSource.HOSTILE;
            shooter.level().playSound(shooter instanceof ServerPlayer player ? player : null,
                    shooter.getX(), shooter.getY(), shooter.getZ(), sound, source, 1.0f, pitch);
        }
    }

    /** Finds a plain pod whose content item maps to the selected ammo TYPE id. */
    private static ItemStack findPod(ServerPlayer player, String ammoId) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() != dev.ignis.createpneumatictacticals.item.ModItems.POD.get()) continue;
            ResourceLocation content = PodItem.contentId(stack);
            if (content == null) continue;
            net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(content);
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            var typeRef = PotatoCannonProjectileType.getTypeForItem(player.level().registryAccess(), item);
            if (typeRef.isPresent()
                    && typeRef.get().unwrapKey().orElseThrow().location().toString().equals(ammoId)) {
                return stack;
            }
        }
        // deep reserve: surface one boxed round as a loose pod; the caller
        // shrinks it, which lands back in the inventory as usual
        for (ItemStack stack : player.getInventory().items) {
            if (!(stack.getItem() instanceof dev.ignis.createpneumatictacticals.block
                    .AmmoBoxBlockItem)) continue;
            ItemStack template = dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.boxTemplate(stack);
            if (template.isEmpty()
                    || template.getItem() != dev.ignis.createpneumatictacticals.item.ModItems.POD.get()) continue;
            String typeId = dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.ammoTypeId(player.level().registryAccess(), stack);
            if (!ammoId.equals(typeId)) continue;
            java.util.List<ItemStack> out = dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.take(stack, 1);
            if (out.isEmpty()) continue;
            ItemStack surfaced = out.get(0);
            player.getInventory().add(surfaced);
            if (!surfaced.isEmpty()) player.drop(surfaced, false);
            for (ItemStack s : player.getInventory().items) {
                if (s.getItem() == dev.ignis.createpneumatictacticals.item.ModItems.POD.get()
                        && ItemStack.isSameItemSameTags(s, template)) return s;
            }
            return null;
        }
        return null;
    }

    private static void feedback(ServerPlayer player, String key) {
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "gui." + CreatePneumaticTacticals.MODID + ".fail." + key), true);
    }

    /** ammoId is a potato-projectile-TYPE registry key (e.g. create:potato). */
    private static Optional<PotatoCannonProjectileType> resolveType(net.minecraft.world.level.Level level, String typeId) {
        return Optional.ofNullable(level.registryAccess()
                .registryOrThrow(com.simibubi.create.api.registry.CreateRegistries.POTATO_PROJECTILE_TYPE)
                .get(ResourceLocation.tryParse(typeId)));
    }

    /** Synthesizes a plain content stack: first item registered for the type. */
    private static ItemStack contentFor(net.minecraft.world.level.Level level, String typeId) {
        return resolveType(level, typeId)
                .flatMap(t -> t.items().stream().findFirst())
                .map(h -> new ItemStack(h.value()))
                .orElse(null);
    }
}