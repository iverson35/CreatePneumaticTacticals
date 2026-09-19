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
    /** total muzzle gas suppression, clamped -10..10; smoke = (1 - v/20) * base */
    public double gasSuppression = 0;
    public double aimZoom = 1.25;
    public double tacticalAimZoom = 1.0;
    @Nullable public ModuleDefinition receiver;
    @Nullable public ModuleDefinition feed;
    @Nullable public ModuleDefinition supply;
    @Nullable public ModuleDefinition barrel;
    @Nullable public ModuleDefinition muzzle;

    /** aggregates singles + position-bound handguard attachments from the gun stack */
    public static GunStats ofGun(net.minecraft.world.item.ItemStack stack) {
        return of(GunNbt.readModules(stack), GunNbt.readHandguardAttachments(stack).values());
    }

    public static GunStats of(Map<ModuleType, ModuleDefinition> installed) {
        return of(installed, java.util.List.of());
    }

    public static GunStats of(Map<ModuleType, ModuleDefinition> installed,
                              java.util.Collection<ModuleDefinition> extras) {
        GunStats s = new GunStats();
        java.util.List<ModuleDefinition> all = new java.util.ArrayList<>(installed.values());
        all.addAll(extras);
        for (ModuleDefinition def : all) {
            s.reloadSpeed += def.reloadSpeed;
            s.damageMultiplier += def.damageMultiplier;
            s.fireRateMultiplier += def.fireRateMultiplier;
            s.hipfireAccuracyMultiplier += def.hipfireAccuracyMultiplier;
            s.ergonomics += def.ergonomics;
            s.bulletSpeed += def.bulletSpeed;
            s.recoilMultiplier += def.recoilMultiplier;
            s.recoilRecovery += def.recoilRecovery;
            s.gasSuppression += def.gasSuppression;
        }
        s.receiver = installed.get(ModuleType.RECEIVER);
        s.feed = installed.get(ModuleType.FEED);
        s.barrel = installed.get(ModuleType.BARREL);
        s.supply = installed.get(ModuleType.SUPPLY);
        s.muzzle = installed.get(ModuleType.MUZZLE);
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
        s.gasSuppression = Mth.clamp(s.gasSuppression, -10, 10);
    }

    public boolean isComplete() {
        return receiver != null && feed != null && supply != null && barrel != null;
    }
}