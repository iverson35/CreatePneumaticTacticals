package dev.ignis.createpneumatictacticals.client;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic recoil pattern for one receiver (机匣), seeded by its id.
 *
 * <p>Every gun used to kick on a fresh coin flip per shot; now the horizontal
 * walk is a fixed curve — the same spray every string, learnable like any
 * shooter's pattern — and the vertical is a smooth ramp instead of a constant
 * per shot. {@link RecoilModel} layers a small random jitter on top, so two
 * strings of the same gun come out close but never identical.
 *
 * <p>Both axes return a multiplier around the receiver's authored
 * {@code base_recoil_pitch} / {@code base_recoil_yaw}: the pattern shapes the
 * curve, the stats keep setting its size (and the module multipliers still
 * scale it).
 *
 * <p>Seed = the receiver id, so a gunpack author gets a pattern for free and
 * no two receivers share one. The values are pure functions of (seed, shot
 * index) — no per-shot state, no per-shot allocation, identical on every
 * client (the seed is the Java-spec {@code String.hashCode}).
 */
final class RecoilPattern {

    /** one entry per receiver id seen (a gunpack-sized set, never evicted) */
    private static final Map<ResourceLocation, RecoilPattern> CACHE = new HashMap<>();

    static RecoilPattern of(ResourceLocation receiverId) {
        return CACHE.computeIfAbsent(receiverId, RecoilPattern::new);
    }

    private final long seed;
    /** horizontal drift: lattice spacing of the slow noise, in shots */
    private final double span;
    /** phase warp (the noise's own step size breathes — no period shows) */
    private final double warpAmp, warpFreq, warpPhase;
    /** decorrelates the fine texture noise from the drift noise */
    private final double textureOffset;
    /** vertical: offset of the small wobble riding the ramp */
    private final double pitchOffset;

    private RecoilPattern(ResourceLocation receiverId) {
        // String.hashCode is part of the Java spec (stable across runs, JVMs
        // and clients); the golden-ratio multiply keeps ids that differ in one
        // character from landing on neighbouring values.
        this.seed = receiverId.toString().hashCode() * 0x9E3779B97F4A7C15L;
        Random r = new Random(seed);
        // Drift is smooth NOISE, not a sine: a sine's period is what made the
        // pattern read as "obviously a wave". Noise is equally deterministic
        // and learnable, but its turns land at irregular intervals. The warp
        // makes the sampling step itself vary shot to shot (up to ±45%), so
        // even the noise's own lattice never shows.
        this.span = 2.2 + r.nextDouble() * 1.3;        // turn every ~2..4 shots
        this.warpAmp = 0.8 + r.nextDouble();           // 0.8..1.8 shots
        this.warpFreq = 0.12 + r.nextDouble() * 0.13;  // period 24..52 shots
        this.warpPhase = r.nextDouble() * Math.PI * 2;
        this.textureOffset = r.nextDouble() * 64.0;
        this.pitchOffset = r.nextDouble() * 64.0;
    }

    long seed() {
        return seed;
    }

    /**
     * Vertical multiplier for shot {@code shot} (1-based): a saturating ramp
     * from 0.80 to 1.15 (half of it by the 6th shot) with a ±5% noise wobble.
     * The climb grows smoothly — consecutive shots differ by a few percent,
     * never by a jump — and the whole range stays inside 0.75..1.20.
     */
    double vertical(int shot) {
        double u = shot - 1;
        double ramp = u / (u + 6.0);
        return 0.80 + 0.35 * ramp + 0.05 * noise(u / 2.5 + pitchOffset);
    }

    /**
     * Horizontal multiplier for shot {@code shot}, in -1..+1: smooth noise at
     * a warped sample rate — the walk drifts one way, turns, comes back and
     * settles, with no period to spot. It spins up over the first two shots
     * (the first shot of a string is straight up), damps to half amplitude as
     * the string runs on, and carries a little finer texture on top.
     */
    double horizontal(int shot) {
        double u = shot - 1;
        double spin = Math.min(1.0, u / 2.0);
        double settle = 1.0 - 0.5 * Math.min(1.0, Math.max(0.0, (u - 8.0) / 24.0));
        double warped = (u + warpAmp * Math.sin(warpFreq * u + warpPhase)) / span;
        // 1.15: value noise clusters near the middle where a sine spent its
        // time near the extremes — this keeps the average kick per shot the
        // same, so base_recoil_yaw still means what it used to
        double drift = 1.15 * noise(warped);
        double texture = noise(u / 1.0 + textureOffset);
        double v = spin * settle * (drift + 0.22 * texture);
        return Math.max(-1.0, Math.min(1.0, v));
    }

    /**
     * Catmull-Rom interpolation between seeded lattice values: C1-continuous
     * (unlike smoothstep, no flat spots at the knots) and stateless — four
     * hashes per call, no per-gun arrays, no allocation. Overshoot past the
     * lattice values is possible and intended (it is what keeps the curve
     * moving), the callers clamp.
     */
    private double noise(double x) {
        int i = (int) Math.floor(x);
        double f = x - i;
        double p0 = hash(i - 1), p1 = hash(i), p2 = hash(i + 1), p3 = hash(i + 2);
        return p1 + 0.5 * f * ((p2 - p0) + f * ((2 * p0 - 5 * p1 + 4 * p2 - p3)
                + f * (3 * (p1 - p2) + p3 - p0)));
    }

    /** splitmix64 finalizer: lattice index -> -1..+1 */
    private double hash(int i) {
        long h = seed + i * 0x9E3779B97F4A7C15L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53 * 2.0 - 1.0;
    }
}
