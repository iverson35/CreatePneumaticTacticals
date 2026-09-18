package dev.ignis.createpneumatictacticals.module;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

/**
 * Handguard attachment positions. The handguard declares which of these it
 * exposes (attachment_points); a handguard attachment declares which it can
 * mount to (positions). Locator bone in the handguard model:
 * {@code loc_handguard_<serializedName>}.
 */
public enum HandguardPosition implements StringRepresentable {
    TOP("top"),
    BOTTOM("bottom"),
    LEFT("left"),
    RIGHT("right");

    private final String name;

    HandguardPosition(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** locator bone name in the handguard model */
    public String locatorName() {
        return "loc_handguard_" + name;
    }

    /** NBT slot key inside the gun's Modules compound */
    public String slotKey() {
        return "hg_" + name;
    }

    @Nullable
    public static HandguardPosition byName(String name) {
        for (HandguardPosition p : values()) {
            if (p.name.equals(name)) return p;
        }
        return null;
    }
}
