package dev.ignis.createpneumatictacticals.client;

import com.simibubi.create.foundation.particle.AirParticleData;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.client.render.MuzzleAnchor;
import dev.ignis.createpneumatictacticals.client.particle.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Camera;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Client-side muzzle smoke, spawned locally on fire — never synced per-particle
 * over the network (Create's cannon does the same client-side).
 *
 * <p>Particle count baselines on the potato cannon: 2 item puffs + 2 air puffs
 * per shot at reloadTicks 20 (1x), scaling linearly with the ammo's own fire
 * rate — a 10-tick ammo fires twice as often, so it emits half the puffs per
 * shot to keep steady-state smoke roughly constant. The gun's
 * fire_rate_multiplier is intentionally NOT part of the puff count: it changes
 * the cadence, not the shot's energy.
 */
public final class MuzzleSmoke {
    /** reloadTicks that map to the 1x potato-cannon baseline */
    private static final double BASE_RELOAD_TICKS = 20;

    private static final Random RANDOM = new Random();

    private MuzzleSmoke() {}

    public static void onFire(Player player, ItemStack ammoContent) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        int reloadTicks = ClientGunInput.currentAmmoReloadTicks();
        if (reloadTicks <= 0) return;
        // puff count: linear in the ammo's own cadence, anchored at the
        // potato-cannon baseline (reload_ticks 20 = 1x); clamp so fast ammo
        // never fully starves the smoke
        // muzzle module gas suppression: -10 = double smoke, +10 = none
        // (smoke = (1 - v/20) * base puffs)
        GunStats stats = GunStats.ofGun(player.getMainHandItem());
        double suppression = Mth.clamp(stats.gasSuppression, -10, 10);
        double smokeScale = 1.0 - suppression / 20.0;
        double scale = Mth.clamp(reloadTicks / BASE_RELOAD_TICKS, 0.25, 2.5) * smokeScale;
        int puffs = (int) Math.round(4 * scale);

        // smoke anchors at the actual muzzle tip when a render-pass sample
        // exists (first AND third person); otherwise falls back to the eye
        Vec3 location;
        float[] anchor = MuzzleAnchor.viewSample();
        Camera cam = mc.gameRenderer.getMainCamera();
        if (anchor != null) {
            // both FP and TP render passes produce the same view space
            // (v = R·(world − camPos), R = camera rotation composed as
            // mulPose(XP(pitch))·mulPose(YP(yaw+180))). Undo R for world axes:
            // R⁻¹ = YP(−(yaw+180))·XP(−pitch), then shift by the camera pos.
            org.joml.Quaternionf inv = new org.joml.Quaternionf()
                    .rotationY((float) Math.toRadians(-(cam.getYRot() + 180.0f)))
                    .rotateX((float) Math.toRadians(-cam.getXRot()));
            org.joml.Vector3f v = new org.joml.Vector3f(anchor[0], anchor[1], anchor[2]).rotate(inv);
            Vec3 cp = cam.getPosition();
            location = new Vec3(cp.x + v.x, cp.y + v.y, cp.z + v.z);
        } else {
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getLookAngle();
            location = eye.add(look.scale(1.0));
        }
        // smoke velocity follows the view direction (approximation: the
        // server's exact per-pellet dir is not known client-side)
        Vec3 dir = player.getLookAngle();
        for (int i = 0; i < puffs; i++) {
            if (!ammoContent.isEmpty()) {
                Vec3 m = offsetRandomly(dir.scale(0.1), 0.025);
                level.addParticle(new ItemParticleOption(ParticleTypes.ITEM, ammoContent),
                        location.x, location.y, location.z, m.x, m.y, m.z);
            }
            // custom puff: uniform size, fixed lifetime. Spawn points fill a
            // forward CONE (radius grows with distance along the shot axis,
            // sqrt-sampled disc + uniform phi like the server spread) and
            // velocity biases forward with a slight outward radial push —
            // an even, forward-extending plume instead of a ball at the tip
            double halfAngle = Math.toRadians(18 + RANDOM.nextDouble() * 14);
            double phi = RANDOM.nextDouble() * 2 * Math.PI;
            double rr = Math.sqrt(RANDOM.nextDouble()) * Math.tan(halfAngle);
            Vec3 up = Math.abs(dir.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 u = dir.cross(up).normalize();
            Vec3 w = dir.cross(u).normalize();
            Vec3 radial = u.scale(Math.cos(phi) * rr).add(w.scale(Math.sin(phi) * rr));
            double dist = 0.1 + RANDOM.nextDouble() * 0.45;
            Vec3 pos = location.add(dir.scale(dist)).add(radial.scale(dist));
            Vec3 vel = dir.scale(0.2).add(radial.scale(0.1));
            level.addParticle((net.minecraft.core.particles.SimpleParticleType) ModParticles.MUZZLE_SMOKE.get(),
                    pos.x, pos.y, pos.z, vel.x, vel.y, vel.z);
        }
    }

    /** VecHelper.offsetRandomly equivalent (catnip is not on the classpath here) */
    private static Vec3 offsetRandomly(Vec3 vec, double radius) {
        return vec.add(
                (RANDOM.nextDouble() - 0.5) * 2 * radius,
                (RANDOM.nextDouble() - 0.5) * 2 * radius,
                (RANDOM.nextDouble() - 0.5) * 2 * radius);
    }
}