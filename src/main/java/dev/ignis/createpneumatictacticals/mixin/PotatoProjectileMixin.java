package dev.ignis.createpneumatictacticals.mixin;

import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import com.simibubi.create.content.equipment.potatoCannon.PotatoProjectileEntity;
import com.simibubi.create.foundation.damageTypes.CreateDamageSources;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.HitConfirmPacket;
import net.minecraft.core.particles.ParticleTypes;
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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
 * </ul>
 * Plain potato-cannon projectiles keep Create's behavior untouched.
 * Traveled distance / reflect count are mixin instance state: not persisted
 * across chunk reloads, acceptable for projectiles that live for seconds.
 */
@Mixin(PotatoProjectileEntity.class)
public abstract class PotatoProjectileMixin {

    @Unique private double cpt$traveled;
    @Unique private int cpt$reflects;
    @Unique private Vec3 cpt$lastPos;

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
        if (ext.damageFalloffRate > 0 && ext.damageAt(cpt$traveled, 1.0) <= 0) {
            // damage fell to zero: detonate once if explosive, then die
            if (ext.affectRadius > 0) cpt$explode(self, pos, ext);
            self.kill();
        }
    }

    // --- entity hit: full replacement for gun shots ---------------------------

    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
    private void createpneumatictacticals$gunHitEntity(EntityHitResult ray, CallbackInfo ci) {
        PotatoProjectileEntity self = cpt$self();
        if (!cpt$isGunShot(self)) return;
        ci.cancel();
        Level level = self.level();
        if (level.isClientSide()) return;

        AmmoExtension ext = cpt$ext(self);
        Entity target = ray.getEntity();
        Vec3 hit = ray.getLocation();
        Entity owner = self.getOwner();
        PotatoCannonProjectileType type = self.getProjectileType();

        double base = ext.damage > 0 ? ext.damage : type.damage();
        double damage = base * self.getPersistentData().getDouble("cpt_dmg")
                * ext.damageAt(cpt$traveled, 1.0);
        boolean headshot = target instanceof LivingEntity living && cpt$isHeadshot(living, hit);
        if (headshot) damage *= ext.headshotMultiplier;

        DamageSource source = CreateDamageSources.potatoCannon(level, self, owner);
        if (target.hurt(source, (float) damage) && target instanceof LivingEntity living) {
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
        ci.cancel();
        Level level = self.level();
        if (level.isClientSide()) return;

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

    /** head region per pointblank's HitScan: above eye height minus 12% of body height */
    @Unique
    private static boolean cpt$isHeadshot(LivingEntity entity, Vec3 hit) {
        AABB bb = entity.getBoundingBox();
        double headStart = bb.minY + entity.getEyeHeight() - bb.getYsize() * 0.12;
        return hit.y >= headStart && hit.y <= bb.maxY + 0.1;
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
                    Vec3 push = dir.normalize().scale(ext.explosionKnockback * distScale * mult);
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
