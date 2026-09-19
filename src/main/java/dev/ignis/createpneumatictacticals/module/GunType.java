package dev.ignis.createpneumatictacticals.module;

import net.minecraft.util.StringRepresentable;

/**
 * Ammo/receiver caliber class. Each receiver fires EXACTLY its own caliber
 * (no downward compatibility). SHOTGUN types split into multiple pellets
 * per shot; the shotgun rule takes priority over any other consideration
 * when classifying a potato projectile type.
 */
public enum GunType implements StringRepresentable {
    HEAVY("heavy"),
    MEDIUM("medium"),
    LIGHT("light"),
    SHOTGUN("shotgun");

    private final String name;

    GunType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** strict match: a receiver only accepts its own caliber */
    public boolean accepts(GunType ammoType) {
        return this == ammoType;
    }

    public static GunType byName(String name, GunType fallback) {
        for (GunType t : values()) {
            if (t.name.equals(name)) return t;
        }
        return fallback;
    }
}