package dev.ignis.createpneumatictacticals.gun;

import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import net.minecraft.world.item.ItemStack;

/**
 * The internal-tank air gauge, one source of truth for the fire gate, the
 * fan-flow recharge, the mob refill and the HUD readout.
 *
 * <p>The gauge lives in the item's vanilla {@code Damage} tag and counts
 * <b>USED</b> air, exactly like durability: a fresh gun (Damage 0) is a FULL
 * tank, the bar drains as the tank empties, and the fan recharge winds it
 * back toward 0. The previous code mixed both directions (the fire gate and
 * the recharge read the tag as STORED air while the HUD read it as USED air),
 * which left a fresh gun firing nothing but "no air" while its HUD read ~100%
 * — a gun straight out of the workbench or the creative tab must shoot.
 *
 * <p>Tank size: the weapon's own durability range ({@code maxDamage}), so the
 * durability bar IS the air gauge. The supply module only declares
 * {@code air_per_shot}: the durability drained per shot, which is what turns
 * the fixed 1000-point range into a round count (20/shot = 50 shots).
 */
public final class AirTank {

    private AirTank() {}

    /** Tank size in air units = the weapon's durability range. */
    public static int capacity(ItemStack gun) {
        return Math.max(1, gun.getMaxDamage());
    }

    /** Air left: capacity minus the used gauge, clamped to [0, capacity]. */
    public static int stored(ItemStack gun) {
        int capacity = capacity(gun);
        return Math.max(0, capacity - Math.min(gun.getDamageValue(), capacity));
    }

    /**
     * True when the tank can pay for one shot. {@code air_per_shot <= 0} means
     * the module charges nothing (same as the old {@code air >= 0} math).
     */
    public static boolean canFire(ItemStack gun, ModuleDefinition supply) {
        return supply.airPerShot <= 0 || stored(gun) >= supply.airPerShot;
    }

    /** Spend one shot's air; callers MUST have checked {@link #canFire}. */
    public static void consume(ItemStack gun, ModuleDefinition supply) {
        if (supply.airPerShot <= 0) return;
        int capacity = capacity(gun);
        int used = Math.min(gun.getDamageValue(), capacity);
        gun.setDamageValue(Math.min(capacity, used + supply.airPerShot));
    }

    /** Fan-flow recharge: wind the gauge back by up to {@code amount} units. */
    public static void recharge(ItemStack gun, int amount) {
        if (amount <= 0) return;
        gun.setDamageValue(Math.max(0, gun.getDamageValue() - amount));
    }

    /** Back to a full tank (mob refills: the AI pays no air, it just re-gasses). */
    public static void refill(ItemStack gun) {
        gun.setDamageValue(0);
    }
}
