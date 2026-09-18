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
    STOCK("stock", false);

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

    public static ModuleType byName(String name, ModuleType fallback) {
        for (ModuleType t : values()) {
            if (t.name.equals(name)) return t;
        }
        return fallback;
    }
}
