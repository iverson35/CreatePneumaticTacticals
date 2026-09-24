package dev.ignis.createpneumatictacticals.gun;

import java.util.EnumSet;
import java.util.Map;

import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.module.GunType;
import dev.ignis.createpneumatictacticals.network.GunFireHandler;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

/**
 * Ranged AI for any mob holding one of our guns, mirroring vanilla
 * RangedBowAttackGoal's chase/seeTime/strafe/attackTime framework. Guns
 * have no draw phase: the cooldown timer IS the fire cadence — when it
 * hits zero and the target is visible, the shot goes out via
 * GunFireHandler.onMobFire (which re-gates on the gun's own ammo fire
 * rate and LAST_SHOT anyway; this goal's interval is only a coarse
 * pre-gate).
 *
 * <p>Guns need full assembly (receiver+feed+supply+barrel), a selected
 * ammo type and rounds in the magazine to fire; a gun that cannot fire
 * makes this goal effectively harmless.
 */
public class GunAttackGoal extends Goal {
    /**
     * Designated magazine ammo per receiver caliber, chosen as the cheapest
     * plain round with no standout properties (no bounce/poison/explosion/
     * food-effect payload): farm crops beat recipe or mineral rounds.
     * LIGHT: beetroot — weakest of the roster (16 blocks, 3.0 damage).
     * MEDIUM: potato — the vanilla staple, nothing special about it.
     * HEAVY: melon_block — grown in bulk, unlike cake/golden_carrot recipes.
     * SHOTGUN: sweet_berry — a bush crop, unlike glow_berry (cave-only) or
     * chocolate_berry (recipe).
     */
    private static final Map<GunType, String> DESIGNATED_AMMO = Map.of(
            GunType.LIGHT, "create:beetroot",
            GunType.MEDIUM, "create:potato",
            GunType.HEAVY, "create:melon_block",
            GunType.SHOTGUN, "create:sweet_berry");

    /** ticks an empty magazine takes to refill; matches a player clip swap */
    private static final int RELOAD_DURATION = 40;

    /** reload state: 0 idle, >0 ticks left filling the magazine */
    private int reloadTicks = 0;

    private final net.minecraft.world.entity.Mob mob;
    private final double speedModifier;
    private final int attackIntervalMin;
    private final float attackRadiusSqr;

    private int attackTime = -1;
    private int seeTime;
    private boolean strafingClockwise;
    private boolean strafingBackwards;
    private int strafingTime = -1;

    public GunAttackGoal(net.minecraft.world.entity.Mob mob, double speedModifier,
                         int attackIntervalMin, float attackRadius) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.attackIntervalMin = attackIntervalMin;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** designated cheap ammo id for the receiver's caliber, null when unknown */
    private static String designatedAmmoFor(GunType caliber) {
        return DESIGNATED_AMMO.get(caliber);
    }

    /**
     * True when the gun cannot fire another round: magazine empty, or an
     * internal-tank gun out of air (an air-broken gun never consumes rounds,
     * so the ammo count alone misses it).
     */
    private boolean gunNeedsReload(ItemStack gun) {
        if (GunNbt.getAmmoCount(gun) <= 0) return true;
        GunStats stats = GunStats.ofGun(gun);
        if (stats.supply == null) return false;
        return stats.supply.supplyType == dev.ignis.createpneumatictacticals.module.SupplyType.INTERNAL_TANK
                && gun.getDamageValue() < stats.supply.airPerShot;
    }

    private boolean isHoldingGun() {
        return this.mob.isHolding(is -> is.getItem() instanceof GunItem);
    }

    @Override
    public boolean canUse() {
        return this.mob.getTarget() != null && this.isHoldingGun();
    }

    @Override
    public boolean canContinueToUse() {
        return (this.canUse() || !this.mob.getNavigation().isDone()) && this.isHoldingGun();
    }

    @Override
    public void start() {
        super.start();
        this.mob.setAggressive(true);
    }

    @Override
    public void stop() {
        super.stop();
        this.mob.setAggressive(false);
        this.seeTime = 0;
        this.attackTime = -1;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) return;

        double distSqr = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        boolean seesTarget = this.mob.getSensing().hasLineOfSight(target);
        boolean sawTarget = this.seeTime > 0;
        if (seesTarget != sawTarget) this.seeTime = 0;
        this.seeTime += seesTarget ? 1 : -1;

        if (distSqr <= (double) this.attackRadiusSqr && this.seeTime >= 20) {
            this.mob.getNavigation().stop();
            ++this.strafingTime;
        } else {
            this.mob.getNavigation().moveTo(target, this.speedModifier);
            this.strafingTime = -1;
        }

        if (this.strafingTime >= 20) {
            if (this.mob.getRandom().nextFloat() < 0.3F) this.strafingClockwise = !this.strafingClockwise;
            if (this.mob.getRandom().nextFloat() < 0.3F) this.strafingBackwards = !this.strafingBackwards;
            this.strafingTime = 0;
        }

        if (this.strafingTime > -1) {
            if (distSqr > (double) (this.attackRadiusSqr * 0.75F)) {
                this.strafingBackwards = false;
            } else if (distSqr < (double) (this.attackRadiusSqr * 0.25F)) {
                this.strafingBackwards = true;
            }
            this.mob.getMoveControl().strafe(this.strafingBackwards ? -0.5F : 0.5F, this.strafingClockwise ? 0.5F : -0.5F);
            this.mob.lookAt(target, 30.0F, 30.0F);
        } else {
            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }

        // mob reload: when the magazine runs dry the mob swaps to the
        // designated cheap ammo and refills after a fixed delay. It has no
        // inventory to feed from, so the refill itself is free — the cost is
        // the reload window: the mob keeps moving/strafing but cannot fire.
        ItemStack gun = this.mob.getMainHandItem();
        if (reloadTicks > 0) {
            if (--reloadTicks == 0 && gun.getItem() instanceof GunItem) {
                GunStats stats = GunStats.ofGun(gun);
                if (stats.isComplete() && stats.feed != null) {
                    String ammo = designatedAmmoFor(stats.receiver.gunType);
                    if (ammo != null) {
                        GunNbt.setAmmo(gun, ammo);
                        GunNbt.setAmmoCount(gun, stats.feed.clipSize);
                        if (stats.supply.supplyType == dev.ignis.createpneumatictacticals.module.SupplyType.INTERNAL_TANK) {
                            int full = stats.supply.airCapacity > 0
                                    ? stats.supply.airCapacity : gun.getMaxDamage();
                            gun.setDamageValue(full);
                        }
                    }
                }
            }
        } else if (gun.getItem() instanceof GunItem && gunNeedsReload(gun)) {
            reloadTicks = RELOAD_DURATION;
        }

        // fire: no draw phase — the cooldown timer is the cadence. When the
        // gun cannot actually fire (empty/no ammo selected, no air,
        // incompatible receiver) the core silently returns and the mob just
        // keeps pressing toward its target without shooting.
        if (reloadTicks == 0 && --this.attackTime <= 0 && this.seeTime >= -60 && seesTarget) {
            GunFireHandler.onMobFire(this.mob);
            this.attackTime = this.attackIntervalMin;
        }
    }
}