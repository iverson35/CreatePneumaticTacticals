package dev.ignis.createpneumatictacticals.mixin;

import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import com.simibubi.create.content.equipment.potatoCannon.PotatoProjectileEntity;
import com.simibubi.create.foundation.damageTypes.CreateDamageSources;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.HitBloodPacket;
import dev.ignis.createpneumatictacticals.network.HitConfirmPacket;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
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
import java.util.Optional;

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

    /** surface nudge of the last bounce, applied at the tick tail */
    @Unique
    private Vec3 cpt$bouncePos;

    // --- client tracer trail (createpneumatictacticals$trail) ---
    /** resolved trail particle; null = none (never set, or unknown id) */
    @Unique private net.minecraft.core.particles.ParticleOptions cpt$trailOptions;
    @Unique private boolean cpt$trailResolved;
    /** last client tick position, and distance travelled since the last spawn */
    @Unique private Vec3 cpt$trailLast;
    @Unique private double cpt$trailAccum;
    /** hard cap on particles per tick: a fast round with a tiny interval must
     *  not flood the particle engine (the trail skips points instead) */
    private static final int cpt$TRAIL_MAX_PER_TICK = 32;

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
        double base = cpt$baseDamage(self, ext);
        // damage fell to zero: detonate once if explosive, then die. The
        // derived rate counts too, not just an authored damage_falloff_rate.
        if (base > 0 && cpt$damageAt(ext, base) <= 0) {
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

    /**
     * Client-side tracer trail: the ammo's {@code trail_particle} spawned every
     * {@code trail_interval} blocks of real flight path, so a distant sniper's
     * round is visible to everyone who can see it (that is the whole point -
     * the trail is per client and costs the server nothing). The particle id
     * and spacing ride the spawn payload (cpt_trail / cpt_trail_interval, see
     * the save hooks) because a dedicated server's client has no ammo table.
     *
     * <p>Points are placed by walking the segment between the last tick's
     * position and this one, so the spacing is true distance at any speed - a
     * round crossing 6 blocks in a tick with a 2-block interval gets three
     * points, not one. Only option-less particles can be named by id alone
     * (SimpleParticleType: flame, end_rod, crit, electric_spark, ...); a type
     * that needs options resolves to nothing and drops the trail once instead
     * of retrying every tick.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void createpneumatictacticals$trail(CallbackInfo ci) {
        PotatoProjectileEntity self = cpt$self();
        Level level = self.level();
        if (!level.isClientSide() || !cpt$isGunShot(self)) return;
        CompoundTag data = self.getPersistentData();
        if (!cpt$trailResolved) {
            cpt$trailResolved = true;
            if (data.contains("cpt_trail")) {
                net.minecraft.resources.ResourceLocation id =
                        net.minecraft.resources.ResourceLocation.tryParse(data.getString("cpt_trail"));
                var type = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.get(id);
                if (type instanceof net.minecraft.core.particles.ParticleOptions options) {
                    cpt$trailOptions = options;
                } else {
                    data.remove("cpt_trail"); // unknown / needs options: never retry
                }
            }
        }
        if (cpt$trailOptions == null) return;
        double gap = data.getDouble("cpt_trail_interval");
        if (gap < 0.1) gap = 0.1;
        Vec3 pos = self.position();
        if (cpt$trailLast == null) {
            cpt$trailLast = pos;
            return;
        }
        double seg = pos.distanceTo(cpt$trailLast);
        if (seg > 0) {
            Vec3 dir = pos.subtract(cpt$trailLast).scale(1.0 / seg);
            // distance since the last point, in blocks: n full gaps fit, and the
            // remainder carries into the next tick, so the spacing is exactly
            // `gap` no matter how the per-tick steps fall
            double since = cpt$trailAccum + seg;
            int n = (int) Math.floor(since / gap + 1e-9);
            double spacing = gap;
            if (n > cpt$TRAIL_MAX_PER_TICK) {
                // absurd gap for this speed (a 20-block tick with a 0.1 gap):
                // spread the cap evenly over the segment instead of dropping
                // its tail, which would leave a hole in the line
                n = cpt$TRAIL_MAX_PER_TICK;
                spacing = since / n;
            }
            for (int i = 0; i < n; i++) {
                Vec3 p = cpt$trailLast.add(dir.scale(spacing - cpt$trailAccum + i * spacing));
                level.addParticle(cpt$trailOptions, p.x, p.y, p.z, 0, 0, 0);
            }
            cpt$trailAccum = since - n * spacing;
        }
        cpt$trailLast = pos;
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
    // remap stays ENABLED (default) here: tick/setDeltaMovement are vanilla
    // names and the refmap rewrites them to SRG in production. remap = false
    // works in dev (official names) but crashes every production launch.
    @Redirect(method = "tick", at = @At(value = "INVOKE",
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
    // vanilla Entity NBT hooks: same remap rule as above, NEVER remap = false.
    @Inject(method = "addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void createpneumatictacticals$saveBallistics(CompoundTag nbt, CallbackInfo ci) {
        CompoundTag data = cpt$self().getPersistentData();
        if (data.contains("cpt_gravity")) nbt.putDouble("cpt_gravity", data.getDouble("cpt_gravity"));
        if (data.contains("cpt_drag")) nbt.putDouble("cpt_drag", data.getDouble("cpt_drag"));
        if (data.contains("cpt_range")) nbt.putDouble("cpt_range", data.getDouble("cpt_range"));
        if (data.contains("cpt_trail")) {
            nbt.putString("cpt_trail", data.getString("cpt_trail"));
            nbt.putDouble("cpt_trail_interval", data.getDouble("cpt_trail_interval"));
        }
        if (data.getBoolean("cpt_gunshot")) nbt.putBoolean("cpt_gunshot", true);
        if (data.contains("cpt_vel_x")) {
            nbt.putDouble("cpt_vel_x", data.getDouble("cpt_vel_x"));
            nbt.putDouble("cpt_vel_y", data.getDouble("cpt_vel_y"));
            nbt.putDouble("cpt_vel_z", data.getDouble("cpt_vel_z"));
        }
        if (data.getBoolean("cpt_gunshot")) {
            // bounce parameters ride the same payload: the client replica has
            // no ammo table (AmmoExtension is filled by the server reload
            // listener), so without them Create's own client handler would
            // kill a bouncing bullet at its first impact
            AmmoExtension ext = cpt$ext(cpt$self());
            nbt.putInt("cpt_bounce", ext.maxReflect);
            nbt.putDouble("cpt_decay", ext.speedDecay);
        }
    }

    @Inject(method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
    private void createpneumatictacticals$readBallistics(CompoundTag nbt, CallbackInfo ci) {
        CompoundTag data = cpt$self().getPersistentData();
        if (nbt.contains("cpt_gravity")) data.putDouble("cpt_gravity", nbt.getDouble("cpt_gravity"));
        if (nbt.contains("cpt_drag")) data.putDouble("cpt_drag", nbt.getDouble("cpt_drag"));
        if (nbt.contains("cpt_range")) data.putDouble("cpt_range", nbt.getDouble("cpt_range"));
        if (nbt.contains("cpt_trail")) {
            data.putString("cpt_trail", nbt.getString("cpt_trail"));
            data.putDouble("cpt_trail_interval", nbt.getDouble("cpt_trail_interval"));
        }
        if (nbt.getBoolean("cpt_gunshot")) data.putBoolean("cpt_gunshot", true);
        if (nbt.contains("cpt_vel_x")) {
            data.putDouble("cpt_vel_x", nbt.getDouble("cpt_vel_x"));
            data.putDouble("cpt_vel_y", nbt.getDouble("cpt_vel_y"));
            data.putDouble("cpt_vel_z", nbt.getDouble("cpt_vel_z"));
        }
        if (nbt.contains("cpt_bounce")) {
            data.putInt("cpt_bounce", nbt.getInt("cpt_bounce"));
            data.putDouble("cpt_decay", nbt.getDouble("cpt_decay"));
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

    /**
     * Last word of the tick for a bounce. AbstractHurtingProjectile moves the
     * entity AFTER onHit, so the surface nudge has to be re-applied here, once
     * vanilla and Create's own tick around it are done writing the position.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void createpneumatictacticals$applyBounce(CallbackInfo ci) {
        if (cpt$bouncePos == null) return;
        PotatoProjectileEntity self = cpt$self();
        self.setPos(cpt$bouncePos.x, cpt$bouncePos.y, cpt$bouncePos.z);
        cpt$bouncePos = null;
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

        double damage = cpt$damageAt(ext, cpt$baseDamage(self, ext))
                * self.getPersistentData().getDouble("cpt_dmg");
        boolean headshot = target instanceof LivingEntity living && cpt$isHeadshot(living);
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
            cpt$sprayBlood(living, damage, headshot);
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
        AmmoExtension ext = cpt$ext(self);
        CompoundTag data = self.getPersistentData();
        // the client replica has no ammo table (AmmoExtension is filled by the
        // server reload listener), so it reads the bounce parameters that came
        // along in the spawn payload - see createpneumatictacticals$saveBallistics
        int maxReflect = ext.maxReflect;
        double speedDecay = ext.speedDecay;
        if (level.isClientSide()) {
            maxReflect = data.contains("cpt_bounce") ? data.getInt("cpt_bounce") : 0;
            speedDecay = data.contains("cpt_decay") ? data.getDouble("cpt_decay") : 0.5;
        }
        if (maxReflect > 0 && cpt$reflects < maxReflect) {
            Vec3 v = self.getDeltaMovement();
            Vec3 n = new Vec3(ray.getDirection().getStepX(), ray.getDirection().getStepY(),
                    ray.getDirection().getStepZ());
            // reflect about the face normal, keep speed_decay of the speed
            Vec3 reflected = v.subtract(n.scale(2 * v.dot(n))).scale(speedDecay);
            if (reflected.lengthSqr() > 1.0E-4) {
                cpt$reflects++;
                // the velocity IS honoured (the vanilla tick re-reads it after
                // onHit), the position is not: AbstractHurtingProjectile ends the
                // tick with setPos(getX() + delta), throwing away any setPos done
                // here. Park the surface nudge for the tick tail instead - left
                // as is, the projectile finishes the tick at (pre-hit position +
                // reflected velocity), a point that disagrees with the segment it
                // just swept, and in corners it lands inside neighbouring blocks:
                // the next clip then starts inside a solid and the bullet flies
                // straight through the wall.
                cpt$bouncePos = ray.getLocation().add(n.scale(0.05));
                self.setDeltaMovement(reflected);
                // the bounce must run on BOTH sides: the replica integrates its
                // own motion, so leaving Create's handler in place here would
                // make a bouncing bullet vanish at its first impact on screen
                cpt$bouncePop(self, ray.getLocation());
                ci.cancel();
                return;
            }
        }
        // terminal impact: the client keeps Create's own handler (pop + kill),
        // the server runs the gun's impact handling
        if (level.isClientSide()) return;
        ci.cancel();
        if (ext.affectRadius > 0) cpt$explode(self, ray.getLocation(), ext);
        self.kill();
    }

    /**
     * Impact feedback for a bounce, mirroring Create's own pop(): a burst of
     * item particles (both sides, exactly like Create's block hit) plus the
     * potato hit sound (server only - playOnServer broadcasts it to everyone).
     */
    @Unique
    private static void cpt$bouncePop(PotatoProjectileEntity self, Vec3 hit) {
        Level level = self.level();
        ItemStack stack = self.getItem();
        if (!stack.isEmpty()) {
            for (int i = 0; i < 7; i++) {
                Vec3 m = new Vec3((level.random.nextDouble() - 0.5) * 0.25,
                        (level.random.nextDouble() - 0.5) * 0.25,
                        (level.random.nextDouble() - 0.5) * 0.25);
                level.addParticle(new ItemParticleOption(ParticleTypes.ITEM, stack), hit.x, hit.y, hit.z,
                        m.x, m.y, m.z);
            }
        }
        if (!level.isClientSide()) PotatoProjectileEntity.playHitSound(level, hit);
    }

    // --- shared bits ------------------------------------------------------------

    /**
     * Head region: the top slab of the bounding box, twice as deep as the gap
     * between eye level and the crown ({@code height - eyeHeight}) - the body
     * above the eyes counts double, so aiming at the eyes lands mid-band. The
     * slab is dropped entirely when it would cover half the creature or more
     * (spider: height 0.9 with eyes at 0.65 would be 56% head) or when the
     * eyes sit at or above the crown.
     */
    @Unique
    private boolean cpt$isHeadshot(LivingEntity entity) {
        AABB bb = entity.getBoundingBox();
        double height = bb.getYsize();
        double headDepth = (height - entity.getEyeHeight()) * 2;
        if (headDepth <= 0 || headDepth * 2 >= height) return false;
        double y = cpt$hitPoint(bb).y;
        return y >= bb.maxY - headDepth && y <= bb.maxY;
    }

    /**
     * Point at which the round met the target box. The vanilla entity test
     * hands back an EntityHitResult whose location is the ENTITY's own position
     * (feet) whenever the projectile's tick began inside the inflated box - the
     * normal case at gun speeds, and why the raw hit.y read as the ground - so
     * both the hit height and the blood spray come from the projectile's own
     * path instead.
     *
     * <p>The segment is backed up by more than the box diagonal before the
     * clip, because AABB.clip only reports crossings at t &gt; 0: a segment that
     * STARTS inside the box reports nothing at all. That is the point-blank
     * case - muzzle already inside the inflated box, which the vanilla test
     * still resolves as a hit through its own shortcut - and without the
     * extension the spray would come off the gun instead of the target.
     * Backed up that far, the start is provably outside, so the crossing it
     * reports is the face the round came in through.
     *
     * <p>Grazes count at the box surface: the vanilla test inflates the target
     * by 0.3, so a shot can land without touching the box.
     */
    @Unique
    private Vec3 cpt$hitPoint(AABB box) {
        PotatoProjectileEntity self = cpt$self();
        Vec3 pos = self.position();
        Vec3 dir = cpt$flightDir(self);
        // Walk the very step the hit test used. AbstractHurtingProjectile.tick
        // runs onHit BEFORE its setPos, so position() is still the tick's start
        // - one full step short of the target at gun speeds (the vanilla test
        // reaches ahead with getDeltaMovement). Reading the point off position()
        // alone is what put the spray at the muzzle up close and a step short at
        // range. Both ends are padded by more than the box diagonal because
        // AABB.clip only reports crossings at t > 0: a segment that STARTS
        // inside the box reports nothing at all.
        double reach = Math.sqrt(box.getXsize() * box.getXsize() + box.getYsize() * box.getYsize()
                + box.getZsize() * box.getZsize()) + 1.0;
        Vec3 from = pos.subtract(dir.scale(reach));
        Vec3 to = pos.add(self.getDeltaMovement()).add(dir.scale(reach));
        Optional<Vec3> hit = box.clip(from, to);
        if (hit.isEmpty()) hit = box.inflate(0.3).clip(from, to);
        if (hit.isPresent()) {
            Vec3 p = hit.get();
            return new Vec3(p.x, Mth.clamp(p.y, box.minY, box.maxY), p.z);
        }
        return pos;
    }

    /**
     * Direction the round is travelling. Velocity first: the hit is resolved
     * BEFORE the projectile moves this tick, so its position still equals the
     * tick's start and the position step is zero at that moment.
     */
    @Unique
    private Vec3 cpt$flightDir(PotatoProjectileEntity self) {
        Vec3 v = self.getDeltaMovement();
        if (v.lengthSqr() > 1.0E-6) return v.normalize();
        if (cpt$lastPos != null) {
            Vec3 step = self.position().subtract(cpt$lastPos);
            if (step.lengthSqr() > 1.0E-6) return step.normalize();
        }
        return new Vec3(0.0, 1.0, 0.0);
    }

    /**
     * Blood on a landed hit: a forward cone down the flight path plus a small
     * backward sputter, both rolled client side (see HitBlood) - the server
     * ships point, direction and drop count only, never per-particle packets.
     * The count grows with the damage actually dealt, so a headshot (multiplier
     * applied first) sprays noticeably more than the same round to the chest.
     * Only players within HitBloodPacket.RADIUS blocks of the impact are told;
     * nobody further out can see the spray anyway.
     */
    @Unique
    private void cpt$sprayBlood(LivingEntity target, double damage, boolean headshot) {
        if (!(cpt$self().level() instanceof ServerLevel serverLevel)) return;
        Vec3 point = cpt$hitPoint(target.getBoundingBox());
        int drops = Mth.clamp((int) Math.round(headshot ? 5 + damage * 1.5 : 4 + damage * 1.2),
                headshot ? 5 : 4, headshot ? 36 : 28);
        HitBloodPacket packet = new HitBloodPacket(point, cpt$flightDir(cpt$self()), drops);
        for (ServerPlayer sp : serverLevel.players()) {
            if (sp.distanceToSqr(point) <= HitBloodPacket.RADIUS * HitBloodPacket.RADIUS) {
                CptNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), packet);
            }
        }
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
        return AmmoExtension.baseDamage(self.getProjectileType(), ext);
    }

    /**
     * Damage after range falloff. The ammo's {@code effective_range} is how far
     * THAT round keeps full damage, and beyond it damage drops per block until
     * it reaches zero: at damage_falloff_rate when the ammo authors one, else
     * at a rate that fades the shot out by twice the falloff start.
     *
     * <p>The firing gun's {@code range_multiplier} (cpt_range, 1.0 when the
     * projectile wasn't fired by a gun with the stat) scales the WHOLE profile:
     * the full-damage distance AND the fade-out are both stretched by it, so a
     * +50% gun keeps full damage 1.5x as far out and also takes 1.5x as long to
     * fade to zero (hence the round flies 1.5x as far before the self-destruct
     * at zero damage). The multiplier is applied to the ammo's authored numbers,
     * never to the already-scaled distance, so the shape is identical for every
     * gun and bullet_speed stays time-of-flight only.
     */
    @Unique
    private double cpt$damageAt(AmmoExtension ext, double base) {
        double mult = cpt$self().getPersistentData().getDouble("cpt_range");
        if (mult <= 0) mult = 1.0;                      // legacy / non-gun shots
        double range = ext.effectiveRange * mult;
        if (cpt$traveled <= range) return base;
        double rate = ext.damageFalloffRate > 0 ? ext.damageFalloffRate : base / ext.effectiveRange;
        return Math.max(0, base - (cpt$traveled - range) * rate / mult);
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
