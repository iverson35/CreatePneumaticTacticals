package dev.ignis.createpneumatictacticals.module;

import net.minecraft.util.StringRepresentable;

/** Supply module types (air source). */
public enum SupplyType implements StringRepresentable {
    BACKPACK_TANK("backpack_tank"), INTERNAL_TANK("internal_tank"), CARTRIDGE("cartridge");

    private final String name;

    SupplyType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static SupplyType byName(String name, SupplyType fallback) {
        for (SupplyType t : values()) {
            if (t.name.equals(name)) return t;
        }
        return fallback;
    }
}