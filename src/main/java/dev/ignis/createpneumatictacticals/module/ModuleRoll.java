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

    /** iid uniform weights; only their ratios matter, so every attribute is
     *  equally likely to end up large no matter where it sits in the list */
    private static double[] weights(RandomSource rng, int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = rng.nextDouble();
        return w;
    }

    /**
     * Splits {@code total} over the weights, proportional to them, capping
     * each share at 1.0 and redistributing the excess. The cap set depends
     * only on the weights, never on iteration order, so permuting the
     * attributes permutes the outputs and nothing else. Needs total <= n.
     */
    private static double[] splitBounded(double total, double[] w) {
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
                if (share[i] >= 1) anyCapped = true;
            }
            if (!anyCapped) {
                for (int i = 0; i < n; i++) if (!capped[i]) out[i] = share[i];
                return out;
            }
            for (int i = 0; i < n; i++) {
                if (capped[i] || share[i] < 1) continue;
                capped[i] = true;
                out[i] = 1;
                remaining -= 1;
                free--;
            }
        }
        return out;
    }
}
