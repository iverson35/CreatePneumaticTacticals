package dev.ignis.createpneumatictacticals.gun;

import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import net.minecraft.util.Mth;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import org.jetbrains.annotations.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Aggregated properties of an assembled gun (receiver + installed modules).
 * All ratio stats are additive (base 1.0 + Σ module modifiers), clamped >= 0.1.
 */
public final class GunStats {

    public double reloadSpeed = 1.0;
    public double damageMultiplier = 1.0;
    public double fireRateMultiplier = 1.0;
    public double hipfireAccuracyMultiplier = 1.0;
    public double ergonomics = 1.0;
    public double bulletSpeed = 1.0;
    public double recoilMultiplier = 1.0;
    public double recoilRecovery = 1.0;
    public double aimZoom = 1.25;
    public double tacticalAimZoom = 1.25;
    @Nullable public ModuleDefinition receiver;
    @Nullable public ModuleDefinition feed;
    @Nullable public ModuleDefinition supply;

    public static GunStats of(Map<ModuleType, ModuleDefinition> installed) {
        GunStats s = new GunStats();
        for (ModuleDefinition def : installed.values()) {
            s.reloadSpeed += def.reloadSpeed;
            s.damageMultiplier += def.damageMultiplier;
            s.fireRateMultiplier += def.fireRateMultiplier;
            s.hipfireAccuracyMultiplier += def.hipfireAccuracyMultiplier;
            s.ergonomics += def.ergonomics;
            s.bulletSpeed += def.bulletSpeed;
            s.recoilMultiplier += def.recoilMultiplier;
            s.recoilRecovery += def.recoilRecovery;
        }
        s.receiver = installed.get(ModuleType.RECEIVER);
        s.feed = installed.get(ModuleType.FEED);
        s.supply = installed.get(ModuleType.SUPPLY);
        if (installed.containsKey(ModuleType.SIGHT)) {
            s.aimZoom = installed.get(ModuleType.SIGHT).aimZoom;
        }
        if (installed.containsKey(ModuleType.TACTICAL_SIGHT)) {
            s.tacticalAimZoom = installed.get(ModuleType.TACTICAL_SIGHT).tacticalAimZoom;
        }
        clampAll(s);
        return s;
    }

    private static void clampAll(GunStats s) {
        s.reloadSpeed = Math.max(0.1, s.reloadSpeed);
        s.damageMultiplier = Math.max(0.1, s.damageMultiplier);
        s.fireRateMultiplier = Math.max(0.1, s.fireRateMultiplier);
        s.hipfireAccuracyMultiplier = Math.max(0.1, s.hipfireAccuracyMultiplier);
        s.ergonomics = Math.max(0.1, s.ergonomics);
        s.bulletSpeed = Math.max(0.1, s.bulletSpeed);
        s.recoilMultiplier = Mth.clamp(s.recoilMultiplier, 0.1, 3.0);
        s.recoilRecovery = Mth.clamp(s.recoilRecovery, 0.2, 5.0);
    }

    public boolean isComplete() {
        return receiver != null && feed != null && supply != null;
    }
}