package dev.ignis.createpneumatictacticals.module;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Manufacturing rolls: every module is randomized when it is crafted.
 *
 * <p>Budget rule (配件预算): a fresh module holds a budget of 1.0, which buys
 * exactly one fully-filled <em>good</em> attribute (one that helps the
 * shooter). Every filled <em>bad</em> attribute hands back as much budget as
 * it was filled with, so the finished module always ends at budget 0:
 *
 * <pre>    sum(good fractions) - sum(bad fractions) = 1</pre>
 *
 * <p>Each rollable attribute moves anywhere in 0..1 of its authored value
 * (0 = the attribute is gone, 1 = the value written in the module JSON).
 * Everything else — damage, fire rate, bullet speed, gravity, drag — keeps
 * its authored value and neither costs nor grants budget. Attributes authored
 * as 0 are skipped, and a module with no good attribute at all rolls flat:
 * its budget has nothing to buy, so its downsides stay at 0.
 *
 * <p>Order independence (与属性顺序无关): the fractions come from iid uniform
 * weights and a symmetric split, so the distribution never depends on the
 * order attributes are declared in. Shuffling the JSON keys of a module
 * changes nothing, and no attribute is privileged to be the one that gets
 * filled first.
 *
 * <p>Storage: the item carries the fractions under {@link #TAG_ROLLS}; a
 * missing entry means 1.0, so stacks that predate this system (or the
 * creative-tab copies) simply read as the authored values.
 */
public final class ModuleRoll {

    private ModuleRoll() {}

    /** module item NBT: { "<attr key>": <fraction 0..1>, ... } */
    public static final String TAG_ROLLS = "Rolls";

    /**
     * The attributes that take part in the budget. {@code higherIsBetter}
     * says which sign of the authored value helps the shooter: recoil
     * multipliers scale the kick directly, so a negative one is the upgrade.
     */
    public enum Attr {
        RELOAD_SPEED("reload_speed", true, d -> d.reloadSpeed),
        HIPFIRE_ACCURACY("hipfire_accuracy_multiplier", true, d -> d.hipfireAccuracyMultiplier),
        ERGONOMICS("ergonomics", true, d -> d.ergonomics),
        RECOIL_VERTICAL("recoil_vertical_multiplier", false, d -> d.recoilVerticalMultiplier),
        RECOIL_HORIZONTAL("recoil_horizontal_multiplier", false, d -> d.recoilHorizontalMultiplier),
        RECOIL_RECOVERY("recoil_recovery", true, d -> d.recoilRecovery),
        GAS_SUPPRESSION("gas_suppression", true, d -> d.gasSuppression);

        /** JSON key of the attribute (gun_properties) and its NBT key */
        public final String key;
        /** true when a positive authored value helps the shooter */
        public final boolean higherIsBetter;
        private final ToDoubleFunction<ModuleDefinition> getter;

        Attr(String key, boolean higherIsBetter, ToDoubleFunction<ModuleDefinition> getter) {
            this.key = key;
            this.higherIsBetter = higherIsBetter;
            this.getter = getter;
        }

        /** the value authored in the module JSON */
        public double authored(ModuleDefinition def) {
            return getter.applyAsDouble(def);
        }

        /** does filling this attribute help the shooter? (authored sign) */
        public boolean isGood(ModuleDefinition def) {
            double v = authored(def);
            return v != 0 && (v > 0) == higherIsBetter;
        }
    }

    /**
     * Rolls one module. Returns a fraction per rollable attribute; attributes
     * that came out at 1.0 (or were skipped) are absent.
     */
    public static Map<Attr, Double> roll(ModuleDefinition def, RandomSource rng) {
        List<Attr> good = new ArrayList<>();
        List<Attr> bad = new ArrayList<>();
        for (Attr a : Attr.values()) {
            double v = a.authored(def);
            if (v == 0) continue;                     // authored 0: out of the budget
            if (a.isGood(def)) good.add(a);
            else bad.add(a);
        }
        Map<Attr, Double> out = new EnumMap<>(Attr.class);
        // No good attribute: the budget has nothing to buy, so the bad ones
        // stay at 0 rather than handing out free downsides.
        if (good.isEmpty()) return out;
        // Total bad fill q is the extra budget the good attributes may spend
        // (sum(good) = 1 + q). Each good tops out at 1.0, so q <= |good| - 1;
        // each bad tops out at 1.0, so q <= |bad|. Uniform over that range.
        double badTotal = bad.isEmpty() ? 0
                : rng.nextDouble() * Math.min(bad.size(), good.size() - 1);
        if (badTotal > 0) {
            double[] q = splitBounded(badTotal, weights(rng, bad.size()));
            for (int i = 0; i < bad.size(); i++) out.put(bad.get(i), q[i]);
        }
        double[] p = splitBounded(1 + badTotal, weights(rng, good.size()));
        for (int i = 0; i < good.size(); i++) out.put(good.get(i), p[i]);
        return out;
    }

    /** fraction of one attribute's authored value; 1.0 = unrolled */
    public static double fraction(@Nullable CompoundTag rolls, Attr attr) {
        if (rolls == null || !rolls.contains(attr.key, Tag.TAG_ANY_NUMERIC)) return 1.0;
        return Mth.clamp(rolls.getDouble(attr.key), 0, 1);
    }

    /** the attribute's effective value: authored value times rolled fraction */
    public static double value(ModuleDefinition def, Attr attr, @Nullable CompoundTag rolls) {
        return attr.authored(def) * fraction(rolls, attr);
    }

    /** packs rolled fractions into NBT; null when nothing landed below 1.0 */
    @Nullable
    public static CompoundTag toTag(Map<Attr, Double> fractions) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<Attr, Double> e : fractions.entrySet()) {
            double f = Mth.clamp(e.getValue(), 0, 1);
            if (f < 1) tag.putDouble(e.getKey().key, f);
        }
        return tag.isEmpty() ? null : tag;
    }

    // --- 配件调整台 (tuning table) ------------------------------------------
    //
    // The table walks a finished module's fractions one step at a time. Each
    // step hands ONE STEP to the good side and one STEP to the bad side, each
    // spread over that side's movable attributes in random proportions and
    // capped by what each attribute can still absorb. Both sides always spend
    // the same total — the STEP, or less when a side cannot absorb that much —
    // so sum(good) - sum(bad) never changes: a crafted module keeps sitting
    // inside the set of rolls {@link #roll} can produce, however often the
    // table is used.

    /** one step of the tuning table: a tenth of an attribute's authored value */
    public static final double STEP = 0.1;

    /** a bar this close to a bound counts as full (or empty): it cannot move */
    private static final double STEP_EPS = 1e-9;

    /** the attributes a definition actually rolls; authored 0 sits out the budget */
    public static List<Attr> rollable(ModuleDefinition def) {
        List<Attr> out = new ArrayList<>();
        for (Attr a : Attr.values()) {
            if (a.authored(def) != 0) out.add(a);
        }
        return out;
    }

    /** current fraction of every rollable attribute (missing NBT entry = 1.0) */
    public static Map<Attr, Double> state(ModuleDefinition def, @Nullable CompoundTag rolls) {
        Map<Attr, Double> out = new EnumMap<>(Attr.class);
        for (Attr a : rollable(def)) out.put(a, fraction(rolls, a));
        return out;
    }

    /** true when EACH side still has an attribute that can be raised */
    public static boolean canKnock(ModuleDefinition def, Map<Attr, Double> state) {
        return !eligible(def, state, true, true).isEmpty()
                && !eligible(def, state, false, true).isEmpty();
    }

    /** true when EACH side still has an attribute that can be lowered */
    public static boolean canCalibrate(ModuleDefinition def, Map<Attr, Double> state) {
        return !eligible(def, state, true, false).isEmpty()
                && !eligible(def, state, false, false).isEmpty();
    }

    /**
     * 敲击: the whole STEP is spread over the good side's attributes and over
     * the bad side's attributes — each side spends the same total, so
     * sum(good) - sum(bad) never moves. Null when either side has no room,
     * the caller's "cannot knock".
     */
    @Nullable
    public static Map<Attr, Double> knock(ModuleDefinition def, Map<Attr, Double> state, RandomSource rng) {
        return step(def, state, rng, true);
    }

    /**
     * 校准: the whole STEP is spread over each side's attributes, both sides
     * spending the same total in the lowering direction. Null when either side
     * has nothing left to give.
     */
    @Nullable
    public static Map<Attr, Double> calibrate(ModuleDefinition def, Map<Attr, Double> state, RandomSource rng) {
        return step(def, state, rng, false);
    }

    /**
     * One step of the table: each side spreads one STEP over the attributes it
     * can still move, in random proportions, each attribute capped by what it
     * can still absorb (the excess goes to the others). Both sides spend the
     * same total — the STEP, or less when a side cannot absorb that much — so
     * {@code sum(good) - sum(bad)} never changes and a crafted module stays
     * inside the set of rolls {@link #roll} can produce.
     */
    @Nullable
    private static Map<Attr, Double> step(ModuleDefinition def, Map<Attr, Double> state,
                                          RandomSource rng, boolean up) {
        List<Attr> good = eligible(def, state, true, up);
        List<Attr> bad = eligible(def, state, false, up);
        if (good.isEmpty() || bad.isEmpty()) return null;
        // a side that cannot absorb a whole step caps BOTH sides, keeping the
        // two totals equal — a sliver on one side never lets the other run on
        double spend = Math.min(STEP, Math.min(capacity(state, good, up), capacity(state, bad, up)));
        Map<Attr, Double> out = new EnumMap<>(state);
        spread(out, state, good, spend, up, rng);
        spread(out, state, bad, spend, up, rng);
        return out;
    }

    /** the total room left on one side, in the given direction */
    private static double capacity(Map<Attr, Double> state, List<Attr> side, boolean up) {
        double sum = 0;
        for (Attr a : side) sum += room(state, a, up);
        return sum;
    }

    /**
     * Hands one side its share of the step: random iid weights, proportional
     * shares, each capped by what that attribute can still absorb, the excess
     * redistributed — the same split the crafting roll uses, so the table
     * keeps a module inside the set of rolls the budget can produce.
     */
    private static void spread(Map<Attr, Double> out, Map<Attr, Double> state, List<Attr> side,
                               double total, boolean up, RandomSource rng) {
        int n = side.size();
        double[] cap = new double[n];
        for (int i = 0; i < n; i++) cap[i] = room(state, side.get(i), up);
        double[] share = splitCapped(total, weights(rng, n), cap);
        for (int i = 0; i < n; i++) {
            Attr a = side.get(i);
            double f = state.getOrDefault(a, 1.0) + (up ? share[i] : -share[i]);
            out.put(a, Mth.clamp(f, 0, 1));
        }
    }

    /** how far this attribute can still move in the given direction */
    private static double room(Map<Attr, Double> state, Attr attr, boolean up) {
        double f = state.getOrDefault(attr, 1.0);
        return up ? 1 - f : f;
    }

    /** one side's attributes that can still move in the given direction */
    private static List<Attr> eligible(ModuleDefinition def, Map<Attr, Double> state,
                                       boolean good, boolean up) {
        List<Attr> out = new ArrayList<>();
        for (Attr a : rollable(def)) {
            if (a.isGood(def) != good) continue;
            if (room(state, a, up) > STEP_EPS) out.add(a);
        }
        return out;
    }

    /** iid uniform weights; only their ratios matter, so every attribute is
     *  equally likely to end up large no matter where it sits in the list */
    private static double[] weights(RandomSource rng, int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = rng.nextDouble();
        return w;
    }

    /**
     * Splits {@code total} over the weights, proportional to them, capping
     * each share at 1.0 — a fraction cannot exceed its authored value — and
     * redistributing the excess. Needs total <= n.
     */
    private static double[] splitBounded(double total, double[] w) {
        double[] cap = new double[w.length];
        for (int i = 0; i < cap.length; i++) cap[i] = 1;
        return splitCapped(total, w, cap);
    }

    /**
     * Splits {@code total} over the weights, proportional to them, capping
     * each share at its own cap and redistributing the excess. The cap set
     * depends only on the weights, never on iteration order, so permuting the
     * attributes permutes the outputs and nothing else. Needs
     * total <= sum(caps).
     */
    private static double[] splitCapped(double total, double[] w, double[] cap) {
        int n = w.length;
        double[] out = new double[n];
        boolean[] capped = new boolean[n];
        int free = n;
        double remaining = total;
        while (free > 0) {
            double sum = 0;
            for (int i = 0; i < n; i++) if (!capped[i]) sum += w[i];
            double[] share = new double[n];
            boolean anyCapped = false;
            for (int i = 0; i < n; i++) {
                if (capped[i]) continue;
                share[i] = sum > 0 ? remaining * w[i] / sum : remaining / free;
                if (share[i] >= cap[i]) anyCapped = true;
            }
            if (!anyCapped) {
                for (int i = 0; i < n; i++) if (!capped[i]) out[i] = share[i];
                return out;
            }
            for (int i = 0; i < n; i++) {
                if (capped[i] || share[i] < cap[i]) continue;
                capped[i] = true;
                out[i] = cap[i];
                remaining = Math.max(0, remaining - cap[i]);
                free--;
            }
        }
        return out;
    }
}
