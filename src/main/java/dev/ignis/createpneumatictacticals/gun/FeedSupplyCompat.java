package dev.ignis.createpneumatictacticals.gun;

import dev.ignis.createpneumatictacticals.module.FeedType;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.SupplyType;
import org.jetbrains.annotations.Nullable;

/**
 * Static assembly-time constraint between the feed module's load type and the
 * supply module's supply type:
 *   backpack_tank supply requires load_type=backpack feed,
 *   magazine/round feed require internal_tank or cartridge supply.
 */
public final class FeedSupplyCompat {

    private FeedSupplyCompat() {}

    /**
     * Validates a feed+supply pairing.
     *
     * @return null if legal, otherwise a human-readable reason
     *         (lang key suffix, same convention as {@link GunNbt#validate}).
     */
    @Nullable
    public static String validate(ModuleDefinition feed, ModuleDefinition supply) {
        FeedType feedType = feed.feedType;
        SupplyType supplyType = supply.supplyType;
        if (feedType == null || supplyType == null) return null;
        if (supplyType == SupplyType.BACKPACK_TANK && feedType != FeedType.BACKPACK) {
            return "backpack_tank_needs_backpack_feed";
        }
        if (feedType == FeedType.BACKPACK && supplyType != SupplyType.BACKPACK_TANK) {
            return "backpack_feed_needs_backpack_tank";
        }
        return null;
    }
}