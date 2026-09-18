package dev.ignis.createpneumatictacticals.module;

import net.minecraft.util.StringRepresentable;

/**
 * Receiver class. Heavy receivers accept heavy/medium/light ammo; medium accepts
 * medium/light; light accepts light only.
 */
public enum GunType implements StringRepresentable {
    HEAVY("heavy", 0),
    MEDIUM("medium", 1),
    LIGHT("light", 2);

    private final String name;
    private final int tier;

    GunType(String name, int tier) {
        this.name = name;
        this.tier = tier;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public boolean accepts(GunType ammoType) {
        return ammoType.tier >= this.tier;
    }

    public static GunType byName(String name, GunType fallback) {
        for (GunType t : values()) {
            if (t.name.equals(name)) return t;
        }
        return fallback;
    }
}
