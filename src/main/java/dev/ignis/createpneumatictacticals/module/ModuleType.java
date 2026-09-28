package dev.ignis.createpneumatictacticals.module;

import net.minecraft.util.StringRepresentable;

/**
 * Attachment slot categories. Slot order also drives assembly-bench layout.
 */
public enum ModuleType implements StringRepresentable {
    RECEIVER("receiver", true),
    FEED("feed", true),
    SUPPLY("supply", true),
    BARREL("barrel", true),
    MUZZLE("muzzle", false),
    HANDGUARD("handguard", false),
    HANDGUARD_ATTACHMENT("handguard_attachment", false),
    SIGHT("sight", false),
    TACTICAL_SIGHT("tactical_sight", false),
    STOCK("stock", false),
    /** decorative chain + pendant, single slot on the receiver (plan_v4) */
    CHARM("charm", false);

    private final String name;
    private final boolean required;

    ModuleType(String name, boolean required) {
        this.name = name;
        this.required = required;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public boolean isRequired() {
        return required;
    }

    /**
     * Slots whose module carries its own {@code gun_type} and may only be
     * installed on a receiver of the same caliber. These are the parts that
     * travel between gun families (barrels, muzzle devices, magazines), unlike
     * handguards/sights/stocks/charms which fit any receiver.
     *
     * <p>A muzzle device technically mounts on the barrel, but the barrel is
     * itself caliber-locked to the receiver, so comparing against the
     * receiver's own {@code gun_type} is equivalent and covers every order
     * the parts can be installed in.
     */
    public boolean isGunTyped() {
        return this == BARREL || this == MUZZLE || this == FEED;
    }

    public static ModuleType byName(String name, ModuleType fallback) {
        for (ModuleType t : values()) {
            if (t.name.equals(name)) return t;
        }
        return fallback;
    }
}
