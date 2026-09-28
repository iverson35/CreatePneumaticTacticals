package dev.ignis.createpneumatictacticals.client;

import net.minecraft.resources.ResourceLocation;

/**
 * Recoil prediction for the workbench HUD: the aim path a held trigger would
 * trace on the staged gun, one point per shot.
 *
 * <p>The points come from the same numbers the live camera kick uses — the
 * receiver's {@link RecoilPattern} curve times
 * {@link RecoilModel#viewKickScale} — with the per-shot jitter left out: the
 * chart is the learnable curve, and it must read identically every time the
 * player looks at the bench.
 *
 * <p>ADS stance is assumed (the pattern shape is the same either way, and ADS
 * is the one a player learns). The string ends when the accumulated vertical
 * kick passes {@link #MAX_VERTICAL_DEGREES} degrees or after
 * {@link #MAX_SHOTS} shots, whichever comes first.
 *
 * <p>The result lives in a fixed static buffer (x, y pairs in degrees, x =
 * the aim's horizontal drift with right positive, y = pitch up) that the HUD
 * reads right after {@link #compute} — nothing is allocated per frame.
 */
public final class RecoilPreview {

    /** the simulated climb stops once the accumulated vertical kick passes
     *  this many degrees (or at {@link #MAX_SHOTS}, whichever comes first).
     *  ~8 shots of the default receiver: the first few turns of the curve,
     *  at a px-per-degree the dots can still be told apart */
    public static final double MAX_VERTICAL_DEGREES = 30.0;
    /** hard shot limit for the simulation */
    public static final int MAX_SHOTS = 50;

    private static final double[] POINTS = new double[MAX_SHOTS * 2];
    private static int count;

    private RecoilPreview() {}

    /** rebuild the buffer for one receiver + the gun's aggregate multipliers */
    public static void compute(ResourceLocation receiverId, double basePitch, double baseYaw,
                               double verticalMult, double horizontalMult, boolean aiming) {
        count = 0;
        if (receiverId == null) return;
        RecoilPattern pattern = RecoilPattern.of(receiverId);
        double vView = RecoilModel.viewKickScale(verticalMult, aiming);
        double hView = RecoilModel.viewKickScale(horizontalMult, aiming);
        double x = 0;
        double y = 0;
        for (int shot = 1; shot <= MAX_SHOTS; shot++) {
            // same per-shot amplitudes onShot() feeds the camera: the aim
            // keeps the full kick (the leftover of the previous lift is baked
            // into the real rotation), so the path is the running sum.
            // The camera runs at playerYaw - yawOffset and MC yaw grows to the
            // RIGHT, so a positive offset turns the aim LEFT: negate it, the
            // chart's +x is where the shots actually walk
            x -= baseYaw * pattern.horizontal(shot) * hView;
            y += basePitch * pattern.vertical(shot) * vView;
            POINTS[count * 2] = x;
            POINTS[count * 2 + 1] = y;
            count++;
            if (y >= MAX_VERTICAL_DEGREES) break;
        }
    }

    /** points in the buffer, 1..{@link #MAX_SHOTS} */
    public static int count() {
        return count;
    }

    /** horizontal drift of point {@code i} in degrees, positive = aim right */
    public static double x(int i) {
        return POINTS[i * 2];
    }

    /** pitch offset of point {@code i} in degrees, positive = up */
    public static double y(int i) {
        return POINTS[i * 2 + 1];
    }
}
