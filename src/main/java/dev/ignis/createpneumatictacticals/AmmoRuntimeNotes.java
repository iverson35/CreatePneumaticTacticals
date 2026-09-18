package dev.ignis.createpneumatictacticals;

import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;

import java.util.ArrayList;
import java.util.List;

/**
 * Design notes carried over from EnhancedPotatoCannon (EPC) recon.
 * Kept as a scratchpad for the runtime slice; not referenced by game code.
 */
public final class AmmoRuntimeNotes {
    private AmmoRuntimeNotes() {}

    // EPC recon summary:
    // - Create class: com.simibubi.create.content.equipment.potatoCannon.PotatoCannonProjectileType (registered by item)
    // - EPC did NOT hook into the type class; instead a static table keyed by item id
    //   loaded from data/<ns>/potato_cannon_projectile_types/*.json (same folder as Create's own ammo JSONs)
    // - Runtime logic via PotatoProjectileEntityMixin: explosion (AABB sweep, manual damage/knockback,
    //   3-ray cover check weighted 0.5/0.25/0.25), reflection (onHitBlock HEAD, mirror reflect),
    //   range falloff (BallisticInfo.getDamageRatio linear), headshot (trajectory nearest point above eyeY-0.25)
    // - Effects include pseudo-effect "minecraft:fire"
    // - Per-entity reflect state persisted via addAdditionalSaveData keys DoReflect/MaxReflect/SpeedDecay

    // New mod strategy (from plan_v2): our gun spawns the SAME Create PotatoProjectileEntity,
    // extension attributes consumed by our own entity subclass or event handlers.
    // AmmoExtension defaults mirror EPC: maxReflect=10, speedDecay=0.5, effectiveRange=256.

    public static List<String> openQuestions() {
        List<String> q = new ArrayList<>();
        q.add("Whether our gun reuses PotatoProjectileEntity directly or subclasses it");
        q.add("How Create's projectile spawn path sets velocity/damage for a custom shooter item");
        return q;
    }
}