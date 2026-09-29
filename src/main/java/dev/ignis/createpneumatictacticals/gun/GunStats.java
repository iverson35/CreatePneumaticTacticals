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
 * {@link #reloadSpeed} is the one exception: its sum is a reload TIME ratio and
 * goes through the proportional-exponential curve below before it becomes the
 * playback rate every timing consumer reads.
 */
public final class GunStats {

    /**
     * Reload/bolt animation playback rate = 1/D, DERIVED from {@link #reloadSum}
     * by {@link #reloadPlaybackRate} (never accumulated into directly — the
     * curve input must not carry the stats' usual 1.0 base):
     * D = 1.25 means the reload takes 25% longer and the animations play at
     * 0.8x. Lock windows, the GeckoLib playback speed and the AwCompat mirror
     * all read THIS one number, so the bolt can never outlive the lock.
     */
    public double reloadSpeed = 1.0;

    /**
     * Modules' additive reload_speed SUM — the curve INPUT, and the only place
     * the pack's sign convention lives: NEGATIVE (what every module ships) means
     * a longer reload, positive a shorter one, 0 untouched. Kept apart from
     * {@link #reloadSpeed} precisely because that field carries the 1.0 base of
     * every other stat; feeding the base into the exponential would flip the
     * curve (sum -0.2 would come out FASTER).
     */
    private double reloadSum;
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
    /** SoundEvent id of the shot sound: muzzle override, else the receiver's;
     * null = the vanilla potato-cannon default. Volume and attenuation live in
     * the sound definition itself (the pack's sounds.json) */
    @Nullable public String fireSound;

    /** handling-speed ratio bounds applied to ergonomics (aim/stance/ready feel) */
    public static final double ERGO_MIN = 0.25, ERGO_MAX = 3.0;

    /**
     * One {@link #RELOAD_CURVE_STEP} of summed reload_speed multiplies the
     * reload time by this: the curve is proportional, so every step is the same
     * +-11.8% (0.1 of stat) anywhere in the range - unlike the old 1/(1+sum)
     * hyperbola, which exploded near the bottom (sum -0.9 was a 10x reload).
     */
    public static final double RELOAD_CURVE_BASE = 1.25;
    /** summed reload_speed span equal to one {@link #RELOAD_CURVE_BASE} step */
    public static final double RELOAD_CURVE_STEP = 0.2;
    /** reload-time multiplier D bounds: a sanity net for absurd pack data, far
     *  outside anything a real loadout reaches (|sum| <= 0.5 for the whole pack) */
    public static final double RELOAD_TIME_MIN = 0.25, RELOAD_TIME_MAX = 8.0;
    /** the resulting playback-rate bounds (1/D) every timing consumer shares */
    public static final double RELOAD_SPEED_MIN = 1.0 / RELOAD_TIME_MAX,
            RELOAD_SPEED_MAX = 1.0 / RELOAD_TIME_MIN;
    /** ergonomics above this keeps the gun firing-ready while sprinting;
     *  at or below it the gun may only sprint FROM the ready pose (firing
     *  raises it and denies the sprint until the ready pose returns) */
    public static final double SPRINT_FIRE_ERGO = 1.2;

    /**
     * Ergonomics handling factor shared by aim/stance/ready recovery: 1 =
     * base feel, clamped to {@link #ERGO_MIN}..{@link #ERGO_MAX} so no module
     * combination produces degenerate timing. Common code — the client's
     * sprint-fire rule (ReadyModel) reads this.
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
        // suppressor: a muzzle device that declares fire_sound wins over the
        // receiver's own; both call sites (local shot sound + server broadcast
        // to everyone else) read this field so they can never disagree
        if (s.muzzle != null && s.muzzle.fireSound != null) {
            s.fireSound = s.muzzle.fireSound;
        } else if (s.receiver != null) {
            s.fireSound = s.receiver.fireSound;
        }
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
        s.reloadSum += ModuleRoll.value(def, ModuleRoll.Attr.RELOAD_SPEED, rolls);
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

    /**
     * Maps the modules' additive {@code reload_speed} sum to the reload/bolt
     * animation playback rate (1/D, D = the reload TIME multiplier):
     * {@code D = 1.25^(-sum/0.2)}, so every 0.1 of stat is the same +-11.8% step
     * anywhere in the range and no sum can produce the old hyperbola's cliff.
     * NEGATIVE sum = longer reload (the pack's convention: -0.2 -> D 1.25 ->
     * rate 0.8 = a 25% longer reload). Pure math on purpose: the sign convention
     * and the clamps stay checkable without a Minecraft classpath.
     */
    public static double reloadPlaybackRate(double sum) {
        double d = Math.pow(RELOAD_CURVE_BASE, -sum / RELOAD_CURVE_STEP);
        return 1.0 / Math.min(RELOAD_TIME_MAX, Math.max(RELOAD_TIME_MIN, d));
    }

    /**
     * The RAW stat the workbench panel prints: the same {@code 1.0 + module sum}
     * shape every other row keeps (one -0.2 magazine -> 0.80), deliberately NOT
     * {@link #reloadSpeed}, which is the curve-mapped value the engine plays.
     * The stat sheet stays comparable with the modules' own numbers; only the
     * real animation timing follows the curve.
     */
    public double rawReloadSpeed() {
        return 1.0 + reloadSum;
    }

    private static void clampAll(GunStats s) {
        // reload_speed is a TIME ratio, not a plain multiplier: the modules'
        // additive sum (-0.2 = "25% longer") maps through the proportional curve
        // above, so small sums stay gentle and large ones cannot explode. Stored
        // as the playback rate 1/D - the single number the lock window, the
        // GeckoLib speed and the AwCompat mirror all share.
        s.reloadSpeed = reloadPlaybackRate(s.reloadSum);
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