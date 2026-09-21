package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.Config;
import dev.ignis.createpneumatictacticals.client.particle.BloodParticle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Client-side blood spray for gun hits: quarter-sprite patches of the mature
 * nether-wart texture - mostly transparent, so it reads as torn, half-there
 * spatter far better than a solid crimson block does. The drops carry vanilla
 * terrain-particle physics (gravity 1.0, terminal fall ~2 blocks/tick) and arc
 * down on their own.
 *
 * <p>Drops are {@link BloodParticle}s - a custom particle, because the vanilla
 * terrain particle normalises whatever velocity it is handed (see that class).
 *
 * <p>Two lobes leave the impact point, which is what makes the spray read as
 * directional:
 * <ul>
 *   <li><b>forward</b> - a cone around the round's flight path, fast: the bulk
 *       of the blood leaves the way the bullet was going</li>
 *   <li><b>backward</b> - a few slow drops kicked straight back at the shooter</li>
 * </ul>
 *
 * <p>Gravity adds 0.04 blocks/tick^2, so over the jet's capped 6-10 tick life
 * it sags about a block: the cone leaves the impact as a straight streak and
 * bends into a short arc instead of drifting down into a cloud, while the gap
 * to the slow rear sputter stays unmistakable at a glance.
 */
public final class HitBlood {
    /** share of the drops thrown backwards; the rest make up the forward cone */
    private static final double BACK_SHARE = 0.2;
    /** forward cone half-angle, as a fraction of the forward speed */
    private static final double CONE = 0.15;
    /**
     * Forward jet speed, blocks/tick - a couple of times vanilla block-break
     * spatter (0.1-0.2), enough to read as a streak out of the impact while the
     * capped lifetime keeps gravity from bending it into a cloud.
     */
    private static final double FORWARD_MIN = 0.30;
    private static final double FORWARD_MAX = 0.60;
    /** backward sputter speed, blocks/tick */
    private static final double BACK_MIN = 0.04;
    private static final double BACK_MAX = 0.15;
    /** lifetime window, ticks - capped so nothing drifts down into a cloud */
    private static final int FORWARD_LIFE_MIN = 6;
    private static final int FORWARD_LIFE_MAX = 10;
    private static final int BACK_LIFE_MIN = 8;
    private static final int BACK_LIFE_MAX = 16;

    private static final Random RANDOM = new Random();

    private HitBlood() {
    }

    public static void spawn(Vec3 pos, Vec3 dir, int count) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || count <= 0) return;
        Vec3 axis = dir.lengthSqr() > 1.0E-6 ? dir.normalize() : new Vec3(0.0, 1.0, 0.0);

        int back = (int) Math.round(count * BACK_SHARE);
        for (int i = 0; i < count; i++) {
            boolean rear = i < back;
            double speed = rear
                    ? BACK_MIN + RANDOM.nextDouble() * (BACK_MAX - BACK_MIN)
                    : FORWARD_MIN + RANDOM.nextDouble() * (FORWARD_MAX - FORWARD_MIN);
            // the rear sputter is allowed a wider angle than the jet
            Vec3 v = cone(axis, rear ? -speed : speed, speed * (rear ? CONE * 2.5 : CONE));
            int life = rear
                    ? BACK_LIFE_MIN + RANDOM.nextInt(BACK_LIFE_MAX - BACK_LIFE_MIN + 1)
                    : FORWARD_LIFE_MIN + RANDOM.nextInt(FORWARD_LIFE_MAX - FORWARD_LIFE_MIN + 1);
            mc.particleEngine.add(new BloodParticle(level, pos.x, pos.y, pos.z,
                    v.x, v.y, v.z, life, Config.bloodParticles));
        }
    }

    /**
     * Velocity of {@code speed} along the axis plus a uniform disc of jitter of
     * radius {@code spread} across it.
     */
    private static Vec3 cone(Vec3 axis, double speed, double spread) {
        Vec3 side = axis.cross(new Vec3(0.0, 1.0, 0.0));
        if (side.lengthSqr() < 1.0E-6) side = axis.cross(new Vec3(1.0, 0.0, 0.0));
        side = side.normalize();
        Vec3 up = axis.cross(side).normalize();
        double angle = RANDOM.nextDouble() * Math.PI * 2.0;
        double radius = Math.sqrt(RANDOM.nextDouble()) * spread; // uniform over the disc
        return axis.scale(speed)
                .add(side.scale(Math.cos(angle) * radius))
                .add(up.scale(Math.sin(angle) * radius));
    }
}
