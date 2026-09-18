package dev.ignis.createpneumatictacticals.module;

import net.minecraft.util.StringRepresentable;

public enum FireMode implements StringRepresentable {
    SEMI("semi"), AUTO("auto"), BURST("burst");

    private final String name;

    FireMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static FireMode byName(String name) {
        for (FireMode m : values()) {
            if (m.name.equals(name)) return m;
        }
        throw new IllegalArgumentException("unknown fire mode: " + name);
    }
}