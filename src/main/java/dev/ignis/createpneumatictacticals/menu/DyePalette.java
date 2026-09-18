package dev.ignis.createpneumatictacticals.menu;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Hardcoded 16 vanilla-dye mapping for the workbench color picker.
 * Index 0..15 = DyeColor order; data-driven material mapping can replace
 * this later. Colors are the dye's firework color (saturated representative
 * ARGB), stored opaque (0xFF alpha) in module NBT.
 */
public final class DyePalette {

    /** regionIndex (0-2) -> dye item; same dye for all regions for now. */
    private static final DyeItem[] DYES;
    private static final int[] ARGB;
    private static final Map<Item, Integer> BY_ITEM = new HashMap<>();

    static {
        DyeColor[] colors = DyeColor.values();
        DYES = new DyeItem[colors.length];
        ARGB = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            DYES[i] = DyeItem.byColor(colors[i]);
            ARGB[i] = 0xFF000000 | colors[i].getFireworkColor();
            BY_ITEM.put(DYES[i], i);
        }
    }

    public static final int SIZE = DYES.length;

    private DyePalette() {
    }

    @Nullable
    public static DyeItem forIndex(int index) {
        if (index < 0 || index >= SIZE) return null;
        return DYES[index];
    }

    public static int argbOf(int index) {
        if (index < 0 || index >= SIZE) return 0;
        return ARGB[index];
    }

    @Nullable
    public static Integer indexForItem(Item item) {
        return BY_ITEM.get(item);
    }
}