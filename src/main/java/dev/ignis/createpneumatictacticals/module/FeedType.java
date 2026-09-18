package dev.ignis.createpneumatictacticals.module;

import net.minecraft.util.StringRepresentable;

/** Feed module load types. */
public enum FeedType implements StringRepresentable {
    MAGAZINE("magazine"), ROUND("round"), BACKPACK("backpack");

    private final String name;

    FeedType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public static FeedType byName(String name, FeedType fallback) {
        for (FeedType t : values()) {
            if (t.name.equals(name)) return t;
        }
        return fallback;
    }
}