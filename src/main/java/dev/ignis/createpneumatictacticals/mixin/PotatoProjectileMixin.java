package dev.ignis.createpneumatictacticals.mixin;

import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import com.simibubi.create.content.equipment.potatoCannon.PotatoProjectileEntity;
import com.simibubi.create.foundation.damageTypes.CreateDamageSources;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.HitConfirmPacket;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Gun-fired projectile runtime. Projectiles spawned by GunFireHandler carry
 * {@code cpt_gunshot}/{@code cpt_ammo}/{@code cpt_dmg} persistent data; for
 * those, this mixin REPLACES Create's hit handling (cancels it) so the ammo
 * extension attributes apply:
 * <ul>
 * <li>damage = (extension override or Create type damage) x gun damage
 * multiplier x headshot multiplier x distance falloff</li>
 * <li>explosion on impact / range self-destruct (never breaks blocks;
 * cover-tested via LOS sampling, floored by penetrate_ratio)</li>
 * <li>block reflection with speed_decay, up to max_reflect</li>
 * <li>potion/fire effects on direct hits and in the explosion radius</li>
 * <li>per-tick exterior ballistics: the gun's aggregated gravity/drag scales
 * (cpt_gravity/cpt_drag) rescale the ammo type's own values, so attachments
 * flatten or steepen the drop</li>
 * <li>launch velocity (cpt_vel_*): the vanilla channels quantize each axis at
 * +-3.9, so the true value rides the spawn payload and the clamped motion
 * sync is refused client-side (EntityMotionMixin) — without this, gun speeds
 * above 3.9 blocks/tick bend toward the 45-degree diagonals</li>
 * <li>10 s fallback lifetime: a gun shot that never hits anything is removed
 * (detonating first when the ammo is explosive), so stray projectiles cannot
 * pile up</li>
 * </ul>
 * Plain potato-cannon projectiles keep Create's behavior untouched.
 * Traveled distance / reflect count are mixin instance state: not persisted
 * across chunk reloads, acceptable for projectiles that live for seconds.
 */
@Mixin(PotatoProjectileEntity.class)
public abstract class PotatoProjectileMixin {

    /**
     * Create's per-ammo projectile type: source of the base gravity accel and
     * air drag that the gun's attachment scales modulate.
     */
    @Shadow(remap = false) protected PotatoCannonProjectileType type;

    @Unique private double cpt$traveled;
    @Unique private int cpt$reflects;
    @Unique private Vec3 cpt$lastPos;
    /** client spawn: true launch velocity, re-applied on the first tick (see
     *  createpneumatictacticals$restoreLaunchVelocity) */
    @Unique private Vec3 cpt$spawnVel;

    @Unique
    private PotatoProjectileEntity cpt$self() {
        return (PotatoProjectileEntity) (Object) this;
    }

    @Unique
    private static boolean cpt$isGunShot(PotatoProjectileEntity self) {
        return self.getPersistentData().getBoolean("cpt_gunshot");
    }

    @Unique
    private static AmmoExtension cpt$ext(PotatoProjectileEntity self) {
        return AmmoExtension.get(self.getPersistentData().getString("cpt_ammo"));
    }

    // --- distance falloff + range self-destruct -------------------------------

    @Inject(method = "tick", at = @At("HEAD"))
    private void createpneumatictacticals$trackRange(CallbackInfo ci) {
        PotatoProjectileEntity self = cpt$self();
        if (self.level().isClientSide() || !cpt$isGunShot(self)) return;
        Vec3 pos = self.position();
        if (cpt$lastPos != null) cpt$traveled += pos.distanceTo(cpt$lastPos);
        cpt$lastPos = pos;
        AmmoExtension ext = cpt$ext(self);
        if (ext.damageFalloffRate > 0 && cpt$damageAt(self, ext, cpt$baseDamage(self, ext)) <= 0) {
            // damage fell to zero: detonate once if explosive, then die
            if (ext.affectRadius > 0) cpt$explode(self, pos, ext);
            self.kill();
        }
    }

    // --- fallback lifetime -----------------------------------------------------

    /**
     * 10 s safety net for gun shots that never hit anything. Works off vanilla
     * {@code tickCount}, which the level bumps right before each entity tick,
     * so the count is exact and costs no extra state; it is NOT persisted in
     * NBT, so reloading a chunk restarts it - a bullet parked in an unloaded
     * chunk is not ticking anyway and expires 10 s after a player returns.
     * Explosive ammo detonates, mirroring the range self-destruct.
     */
    private static final int cpt$LIFETIME_TICKS = 200;

    @Inject(method = "tick", at = @At("HEAD"))
    private void createpneumatictacticals$lifetime(CallbackInfo ci) {
        PotatoProjectileEntity self = cpt$self();
        if (self.level().isClientSide() || !cpt$isGunShot(self)) return;
        if (self.tickCount < cpt$LIFETIME_TICKS) return;
        AmmoExtension ext = cpt$ext(self);
        if (ext.affectRadius > 0) cpt$explode(self, self.position(), ext);
        self.kill();
    }

    // --- exterior ballistics (attachment gravity/drag) ------------------------

    /**
     * Rescales Create's own per-tick physics line
     * {@code setDeltaMovement(getDeltaMovement().add(0, -0.05 * gravityMultiplier, 0).scale(drag))}.
     * Instead of re-deriving the -0.05 constant (which would silently drift if
     * Create retunes it), the gravity term is recovered from the vector Create
     * already built: {@code physics = (before + (0, g, 0)) * d}. A projectile
     * without the stamped scales (plain potato cannon, or a gun with no
     * ballistics modules) takes the untouched path, bit-identical to Create.
     */
    @Redirect(method = "tick", remap = false, at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/equipment/potatoCannon/PotatoProjectileEntity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void createpneumatictacticals$ballistics(PotatoProjectileEntity self, Vec3 physics) {
        Vec3 before = self.getDeltaMovement();
        CompoundTag data = self.getPersistentData();
        double gravityScale = cpt$scale(data, "cpt_gravity");
        double dragScale = cpt$scale(data, "cpt_drag");
        double baseDrag = type.drag();
        if ((gravityScale == 1 && dragScale == 1) || baseDrag == 0) {
            self.setDeltaMovement(physics);
            return;
        }
        double gravity = physics.y / baseDrag - before.y;
        self.setDeltaMovement(before.add(0, gravity * gravityScale, 0)
                .scale(1 - (1 - baseDrag) * dragScale));
    }

    /** stamped scale, or 1.0 (Create's own value) when the key is absent */
    @Unique
    private static double cpt$scale(CompoundTag data, String key) {
        return data.contains(key) ? data.getDouble(key) : 1.0;
    }

    /**
     * The spawn packet is Create's writeSpawnData, which serializes exactly
     * this payload, so mirroring the ballistics keys here hands them to the
     * client replica and both sides tick the same trajectory. Plain save/load
     * round-trips them too, which keeps chunk-reloaded projectiles honest.
     * The gun-shot flag rides along for the same reason: ForgeData never goes
     * over the network, and the renderer needs the flag to size gun ammo.
     */
    @Inject(method = "addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", remap = false, at = @At("TAIL"))
    private void createpneumatictacticals$saveBallistics(CompoundTag nbt, CallbackInfo ci) {
        CompoundTag data = cpt$self().getPersistentData();
        if (data.contains("cpt_gravity")) nbt.putDouble("cpt_gravity", data.getDouble("cpt_gravity"));
        if (data.contains("cpt_drag")) nbt.putDouble("cpt_drag", data.getDouble("cpt_drag"));
        if (data.getBoolean("cpt_gunshot")) nbt.putBoolean("cpt_gunshot", true);
        if (data.contains("cpt_vel_x")) {
            nbt.putDouble("cpt_vel_x", data.getDouble("cpt_vel_x"));
            nbt.putDouble("cpt_vel_y", data.getDouble("cpt_vel_y"));
            nbt.putDouble("cpt_vel_z", data.getDouble("cpt_vel_z"));
        }
    }

    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", remap = false, at = @At("TAIL"))
    private void createpneumatictacticals$readBallistics(CompoundTag nbt, CallbackInfo ci) {
        CompoundTag data = cpt$self().getPersistentData();
        if (nbt.contains("cpt_gravity")) data.putDouble("cpt_gravity", nbt.getDouble("cpt_gravity"));
        if (nbt.contains("cpt_drag")) data.putDouble("cpt_drag", nbt.getDouble("cpt_drag"));
        if (nbt.getBoolean("cpt_gunshot")) data.putBoolean("cpt_gunshot", true);
        if (nbt.contains("cpt_vel_x")) {
            data.putDouble("cpt_vel_x", nbt.getDouble("cpt_vel_x"));
            data.putDouble("cpt_vel_y", nbt.getDouble("cpt_vel_y"));
            data.putDouble("cpt_vel_z", nbt.getDouble("cpt_vel_z"));
        }
    }

    /**
     * Client replica: the vanilla spawn packet clamps each axis to +-3.9
     * (ClientboundAddEntityPacket), so the replica would start out slower and
     * off-axis. Create ships the entity NBT as spawn data (writeSpawnData ->
     * addAdditionalSaveData), so the true launch velocity rides along and is
     * picked up here.
     *
     * <p>The tick re-apply is NOT redundant: Forge applies this payload first
     * and the packet's own (clamped) velocity afterwards, so a write here is
     * always overwritten. Verified in-game — spawn read saw before=(-3.9, ...)
     * with cpt_vel=(-7.53, ...), and tick 0 still held the clamped value until
     * this re-apply. tick() HEAD runs before Projectile.tick integrates the
     * movement, so no frame is ever simulated with the truncated velocity.
     */
    @Inject(method = "readSpawnData", remap = false, at = @At("TAIL"))
    private void createpneumatictacticals$captureLaunchVelocity(FriendlyByteBuf buf, CallbackInfo ci) {
        CompoundTag data = cpt$self().getPersistentData();
        if (!data.contains("cpt_vel_x")) return;
        cpt$spawnVel = new Vec3(data.getDouble("cpt_vel_x"), data.getDouble("cpt_vel_y"),
                data.getDouble("cpt_vel_z"));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void createpneumatictacticals$applyLaunchVelocity(CallbackInfo ci) {
        if (cpt$spawnVel == null) return;
        cpt$self().setDeltaMovement(cpt$spawnVel);
        cpt$spawnVel = null;
    }

    // --- entity hit: full replacement for gun shots ---------------------------

    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
    private void createpneumatictacticals$gunHitEntity(EntityHitResult ray, CallbackInfo ci) {
        PotatoProjectileEntity self = cpt$self();
        if (!cpt$isGunShot(self)) return;
        // gun shots never collide with each other: a split burst shares the
        // launch ray with only ~0.1 block of ring offset (less than the 0.25
        // hitbox), so pellets would annihilate each other on tick 1-2.
        // Create's vanilla handler grants a 10-tick grace instead — wrong
        // for guns, where a burst is one shot and must not self-destruct.
        if (ray.getEntity() instanceof PotatoProjectileEntity other && cpt$isGunShot(other)) {
            ci.cancel();
            return;
        }
        Level level = self.level();
        // client replica: leave Create's own handler in place so its hit
        // particles still play; the server owns damage, effects and death
        if (level.isClientSide()) return;
        ci.cancel(); // full replacement of Create's hit handling for gun shots

        AmmoExtension ext = cpt$ext(self);
        Entity target = ray.getEntity();
        Vec3 hit = ray.getLocation();
        Entity owner = self.getOwner();
        PotatoCannonProjectileType type = self.getProjectileType();

        double damage = cpt$damageAt(self, ext, cpt$baseDamage(self, ext))
                * self.getPersistentData().getDouble("cpt_dmg");
        boolean headshot = target instanceof LivingEntity living && cpt$isHeadshot(living, hit);
        if (headshot) damage *= ext.headshotMultiplier;

        DamageSource source = CreateDamageSources.potatoCannon(level, self, owner);
        if (target.hurt(source, (float) damage) && target instanceof LivingEntity living) {
            // full-auto: vanilla sets 20 ticks of hit immunity on hurt(),
            // which would absorb most rounds at 300+ rpm - reset it
            living.invulnerableTime = 0;
            cpt$applyEffects(living, ext.effects.direct(), 1.0);
            // knockback along the flight direction, like Create's handler
            double knockback = type.knockback();
            if (knockback > 0) {
                Vec3 motion = self.getDeltaMovement().multiply(1, 0, 1);
                if (motion.lengthSqr() > 0) {
                    Vec3 dir = motion.normalize();
                    living.knockback(knockback * 0.6, -dir.x, -dir.z);
                }
            }
            if (owner instanceof ServerPlayer sp) {
                CptNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new HitConfirmPacket());
            }
        }
        if (ext.affectRadius > 0) cpt$explode(self, hit, ext);
        self.kill();
    }

    // --- block hit: reflect or detonate ---------------------------------------

    @Inject(method = "onHitBlock", at = @At("HEAD"), cancellable = true)
    private void createpneumatictacticals$gunHitBlock(BlockHitResult ray, CallbackInfo ci) {
        PotatoProjectileEntity self = cpt$self();
        if (!cpt$isGunShot(self)) return;
        Level level = self.level();
        // client replica: leave Create's own handler in place so its
        // block-hit pop particles still play; the server owns reflection
        // and death
        if (level.isClientSide()) return;
        ci.cancel();

        AmmoExtension ext = cpt$ext(self);
        if (ext.maxReflect > 0 && cpt$reflects < ext.maxReflect) {
            Vec3 v = self.getDeltaMovement();
            Vec3 n = new Vec3(ray.getDirection().getStepX(), ray.getDirection().getStepY(),
                    ray.getDirection().getStepZ());
            // reflect about the face normal, keep speed_decay of the speed
            Vec3 reflected = v.subtract(n.scale(2 * v.dot(n))).scale(ext.speedDecay);
            if (reflected.lengthSqr() > 1.0E-4) {
                cpt$reflects++;
                self.setDeltaMovement(reflected);
                // nudge off the surface so the next tick doesn't instantly re-hit
                Vec3 pos = ray.getLocation().add(n.scale(0.05));
                self.setPos(pos.x, pos.y, pos.z);
                return;
            }
        }
        if (ext.affectRadius > 0) cpt$explode(self, ray.getLocation(), ext);
        self.kill();
    }

    // --- shared bits ------------------------------------------------------------

    /**
     * Head region per plan_v2 (from pointblank's HitScan.isHeadshot): the top
     * slab of the bounding box starting at {@code height - width x 0.12},
     * expanded horizontally by 0.301 on each side.
     */
    @Unique
    private static boolean cpt$isHeadshot(LivingEntity entity, Vec3 hit) {
        AABB bb = entity.getBoundingBox();
        double headStart = bb.minY + bb.getYsize() - bb.getXsize() * 0.12;
        return hit.y >= headStart && hit.y <= bb.maxY
                && hit.x >= bb.minX - 0.301 && hit.x <= bb.maxX + 0.301
                && hit.z >= bb.minZ - 0.301 && hit.z <= bb.maxZ + 0.301;
    }

    /**
     * Manual explosion: entity damage/knockback/effects only, NEVER blocks.
     * Cover: sample LOS from the center to points on the victim's bounding
     * box; damage multiplier = max(exposed fraction, penetrate_ratio), so a
     * fully hidden target still takes penetrate_ratio of the damage.
     */
    @Unique
    private static void cpt$explode(PotatoProjectileEntity self, Vec3 center, AmmoExtension ext) {
        Level level = self.level();
        Entity owner = self.getOwner();
        double radius = ext.affectRadius;
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(radius));
        DamageSource source = level.damageSources().explosion(self, owner);
        for (LivingEntity e : targets) {
            double dist = e.getBoundingBox().getCenter().distanceTo(center);
            if (dist > radius) continue;
            double exposure = cpt$exposure(level, center, e);
            double mult = Math.max(exposure, ext.penetrateRatio);
            double distScale = 1.0 - dist / radius;
            float dmg = (float) (ext.explosionDamage * distScale * mult);
            if (dmg > 0) e.hurt(source, dmg);
            if (ext.explosionKnockback != 0) {
                Vec3 dir = e.position().subtract(center);
                if (dir.lengthSqr() > 1.0E-6) {
                    // knockback falls off quadratically (damage is linear, per spec)
                    Vec3 push = dir.normalize().scale(ext.explosionKnockback * distScale * distScale * mult);
                    e.push(push.x, push.y + 0.1, push.z);
                    e.hurtMarked = true;
                }
            }
            cpt$applyEffects(e, ext.effects.explosion(), mult);
        }
        if (level instanceof ServerLevel server) {
            int count = Math.max(1, (int) (radius * 2));
            server.sendParticles(radius >= 3 ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION,
                    center.x, center.y, center.z, count, radius * 0.3, radius * 0.3, radius * 0.3, 0);
            level.playSound(null, center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE,
                    SoundSource.BLOCKS, 1.5f, 0.9f + level.random.nextFloat() * 0.2f);
        }
    }

    /** base hit damage: extension override, else the Create type's damage */
    @Unique
    private static double cpt$baseDamage(PotatoProjectileEntity self, AmmoExtension ext) {
        return ext.damage > 0 ? ext.damage : self.getProjectileType().damage();
    }

    /**
     * Damage after range falloff. Effective range scales with the gun's
     * bullet_speed (stamped as cpt_bspeed at spawn - post-spawn velocity is
     * polluted by drag/gravity); beyond it, damage drops by
     * damage_falloff_rate per block down to zero.
     */
    @Unique
    private double cpt$damageAt(PotatoProjectileEntity self, AmmoExtension ext, double base) {
        double bspeed = self.getPersistentData().getDouble("cpt_bspeed");
        if (bspeed <= 0) bspeed = 1;
        double range = ext.effectiveRange * bspeed;
        if (cpt$traveled <= range || ext.damageFalloffRate <= 0) return base;
        return Math.max(0, base - (cpt$traveled - range) * ext.damageFalloffRate);
    }

    /** fraction of sampled body points visible from the explosion center */
    @Unique
    private static double cpt$exposure(Level level, Vec3 center, LivingEntity e) {
        AABB bb = e.getBoundingBox();
        double cx = bb.getCenter().x, cz = bb.getCenter().z;
        double[] xs = {bb.minX, cx, bb.maxX};
        double[] ys = {bb.minY + 0.1, bb.minY + bb.getYsize() * 0.5, e.getEyeY()};
        double[] zs = {bb.minZ, cz, bb.maxZ};
        int clear = 0, total = 0;
        for (double x : xs) for (double y : ys) for (double z : zs) {
            total++;
            BlockHitResult r = level.clip(new ClipContext(center, new Vec3(x, y, z),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
            if (r.getType() == HitResult.Type.MISS) clear++;
        }
        return (double) clear / total;
    }

    /** potion effects (duration scaled by mult); "minecraft:fire" ignites instead */
    @Unique
    private static void cpt$applyEffects(LivingEntity target, List<AmmoExtension.EffectEntry> effects, double mult) {
        for (AmmoExtension.EffectEntry entry : effects) {
            int ticks = (int) (entry.durationSeconds() * 20 * mult);
            if (ticks <= 0) continue;
            if (cpt$isFire(entry)) {
                target.setSecondsOnFire(Math.max(1, ticks / 20));
                continue;
            }
            MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(
                    net.minecraft.resources.ResourceLocation.tryParse(entry.effectId()));
            if (effect != null) {
                target.addEffect(new MobEffectInstance(effect, ticks, entry.amplifier()));
            }
        }
    }

    @Unique
    private static boolean cpt$isFire(AmmoExtension.EffectEntry entry) {
        return AmmoExtension.EffectEntry.FIRE.equals(entry.effectId());
    }
}
