package dev.ignis.createpneumatictacticals.gun;

import dev.ignis.createpneumatictacticals.module.HandguardPosition;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleRoll;
import net.minecraft.util.Mth;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import org.jetbrains.annotations.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Aggregated properties of an assembled gun (receiver + installed modules).
 * All ratio stats are additive (base 1.0 + Σ module modifiers), clamped >= 0.1.
 */
public final class GunStats {

    public double reloadSpeed = 1.0;
    public double damageMultiplier = 1.0;
    public double fireRateMultiplier = 1.0;
    public double hipfireAccuracyMultiplier = 1.0;
    public double ergonomics = 1.0;
    /**
     * Launch speed is 2 * ammoVelocityMultiplier * bulletSpeed, uncapped:
     * vanilla quantizes both velocity channels per axis at +-3.9, so the true
     * launch velocity is shipped in the spawn payload (cpt_vel_*) and the
     * clamped motion sync is refused by the client replica (EntityMotionMixin).
     * It buys time-of-flight only: ammo reach is the ammo's own absolute
     * effective_range and never scales with the gun.
     */
    public double bulletSpeed = 1.0;
    /**
     * Vertical recoil scale: camera pitch kick + the gun model's backward
     * push / muzzle flip + the screen shake. Base 1.0, additive over
     * modules, clamped 0.1..3.0.
     */
    public double recoilVerticalMultiplier = 1.0;
    /**
     * Horizontal recoil scale: the random left/right camera yaw kick per
     * shot. View-only by design — the gun model itself never kicks
     * sideways. Same base/additive/clamp rules as the vertical one.
     */
    public double recoilHorizontalMultiplier = 1.0;
    public double recoilRecovery = 1.0;
    /**
     * Exterior-ballistics scales, applied per tick by PotatoProjectileMixin
     * to the projectile's {@code -0.05 * type.gravityMultiplier()} vertical
     * accel: base 1.0 (the ammo type's own value), additive over modules,
     * clamped 0.1..3.0. Lower = flatter trajectory.
     */
    public double gravityMultiplier = 1.0;
    /**
     * Air-drag scale: the effective per-tick velocity retention becomes
     * {@code 1 - (1 - type.drag()) * this}. 1.0 reproduces the ammo type
     * exactly; lower keeps speed (and therefore range / time of flight)
     * better. Same base/additive/clamp rules as {@link #gravityMultiplier}.
     */
    public double dragMultiplier = 1.0;
    /** total muzzle gas suppression, clamped -5..1; smoke = (1 - v) * base (±1 = ±100%) */
    public double gasSuppression = 0;
    public double aimZoom = 1.25;
    public double tacticalAimZoom = 1.0;
    @Nullable public ModuleDefinition receiver;
    @Nullable public ModuleDefinition feed;
    @Nullable public ModuleDefinition supply;
    @Nullable public ModuleDefinition barrel;
    @Nullable public ModuleDefinition muzzle;

    /** handling-speed ratio bounds applied to ergonomics (aim/stance/ready feel) */
    public static final double ERGO_MIN = 0.25, ERGO_MAX = 3.0;
    /** ergonomics above this keeps the gun firing-ready while sprinting */
    public static final double SPRINT_FIRE_ERGO = 1.2;

    /**
     * Ergonomics handling factor shared by aim/stance/ready recovery: 1 =
     * base feel, clamped to {@link #ERGO_MIN}..{@link #ERGO_MAX} so no module
     * combination produces degenerate timing. Common code — the server fire
     * gate mirrors the client's sprint-fire rule with this.
     */
    public static double ergoScale(net.minecraft.world.item.ItemStack stack) {
        if (!(stack.getItem() instanceof dev.ignis.createpneumatictacticals.item.GeoGunItem)) return 1.0;
        return Mth.clamp(ofGun(stack).ergonomics, ERGO_MIN, ERGO_MAX);
    }

    /** aggregates singles + position-bound handguard attachments from the gun stack */
    public static GunStats ofGun(net.minecraft.world.item.ItemStack stack) {
        return of(GunNbt.readModules(stack), GunNbt.readHandguardAttachments(stack),
                GunNbt.readModuleRolls(stack));
    }

    public static GunStats of(Map<ModuleType, ModuleDefinition> installed) {
        return of(installed, java.util.Map.of(), java.util.Map.of());
    }

    /**
     * @param rolls per-slot manufacturing rolls, keyed by the same slot keys
     *              the Modules tag uses (module type name / hg_&lt;position&gt;);
     *              a missing entry means the module was never rolled
     */
    public static GunStats of(Map<ModuleType, ModuleDefinition> installed,
                              Map<HandguardPosition, ModuleDefinition> extras,
                              Map<String, net.minecraft.nbt.CompoundTag> rolls) {
        GunStats s = new GunStats();
        java.util.Set<ModuleDefinition> counted = new java.util.HashSet<>();
        for (Map.Entry<ModuleType, ModuleDefinition> e : installed.entrySet()) {
            // unique modules stack no stats beyond the first copy
            if (e.getValue().unique && !counted.add(e.getValue())) continue;
            addRolled(s, e.getValue(), rolls.get(e.getKey().getSerializedName()));
        }
        for (Map.Entry<HandguardPosition, ModuleDefinition> e : extras.entrySet()) {
            if (e.getValue().unique && !counted.add(e.getValue())) continue;
            addRolled(s, e.getValue(), rolls.get(e.getKey().slotKey()));
        }
        s.receiver = installed.get(ModuleType.RECEIVER);
        s.feed = installed.get(ModuleType.FEED);
        s.barrel = installed.get(ModuleType.BARREL);
        s.supply = installed.get(ModuleType.SUPPLY);
        s.muzzle = installed.get(ModuleType.MUZZLE);
        if (installed.containsKey(ModuleType.SIGHT)) {
            s.aimZoom = installed.get(ModuleType.SIGHT).aimZoom;
        }
        if (installed.containsKey(ModuleType.TACTICAL_SIGHT)) {
            s.tacticalAimZoom = installed.get(ModuleType.TACTICAL_SIGHT).tacticalAimZoom;
        }
        clampAll(s);
        return s;
    }

    /** adds one module's stats, scaling the rollable ones by its roll fractions */
    private static void addRolled(GunStats s, ModuleDefinition def,
                                  @Nullable net.minecraft.nbt.CompoundTag rolls) {
        s.reloadSpeed += ModuleRoll.value(def, ModuleRoll.Attr.RELOAD_SPEED, rolls);
        s.damageMultiplier += def.damageMultiplier;              // never rolled
        s.fireRateMultiplier += def.fireRateMultiplier;          // never rolled
        s.hipfireAccuracyMultiplier += ModuleRoll.value(def, ModuleRoll.Attr.HIPFIRE_ACCURACY, rolls);
        s.ergonomics += ModuleRoll.value(def, ModuleRoll.Attr.ERGONOMICS, rolls);
        s.bulletSpeed += def.bulletSpeed;                        // never rolled
        s.recoilVerticalMultiplier += ModuleRoll.value(def, ModuleRoll.Attr.RECOIL_VERTICAL, rolls);
        s.recoilHorizontalMultiplier += ModuleRoll.value(def, ModuleRoll.Attr.RECOIL_HORIZONTAL, rolls);
        s.recoilRecovery += ModuleRoll.value(def, ModuleRoll.Attr.RECOIL_RECOVERY, rolls);
        s.gravityMultiplier += def.gravityMultiplier;            // never rolled
        s.dragMultiplier += def.dragMultiplier;                  // never rolled
        s.gasSuppression += ModuleRoll.value(def, ModuleRoll.Attr.GAS_SUPPRESSION, rolls);
    }

    private static void clampAll(GunStats s) {
        s.reloadSpeed = Math.max(0.1, s.reloadSpeed);
        s.damageMultiplier = Math.max(0.1, s.damageMultiplier);
        s.fireRateMultiplier = Math.max(0.1, s.fireRateMultiplier);
        s.hipfireAccuracyMultiplier = Math.max(0.1, s.hipfireAccuracyMultiplier);
        // ergonomics feeds handling-speed ratios downstream (aim/stance/ready
        // recovery) that re-clamp to [0.25, 3] there; the 5.0 cap here keeps the
        // workbench display honest instead of showing silly sums
        s.ergonomics = Mth.clamp(s.ergonomics, 0.1, 5.0);
        s.bulletSpeed = Math.max(0.1, s.bulletSpeed);
        s.recoilVerticalMultiplier = Mth.clamp(s.recoilVerticalMultiplier, 0.1, 3.0);
        s.recoilHorizontalMultiplier = Mth.clamp(s.recoilHorizontalMultiplier, 0.1, 3.0);
        s.recoilRecovery = Mth.clamp(s.recoilRecovery, 0.2, 5.0);
        s.gravityMultiplier = Mth.clamp(s.gravityMultiplier, 0.1, 3.0);
        s.dragMultiplier = Mth.clamp(s.dragMultiplier, 0.1, 3.0);
        s.gasSuppression = Mth.clamp(s.gasSuppression, -5, 1);
    }

    public boolean isComplete() {
        return receiver != null && feed != null && supply != null && barrel != null;
    }
}