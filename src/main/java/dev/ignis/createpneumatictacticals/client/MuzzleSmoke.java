package dev.ignis.createpneumatictacticals.client;

import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.client.render.MuzzleAnchor;
import dev.ignis.createpneumatictacticals.client.particle.ModParticles;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.network.MuzzleSmokePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * Client-side muzzle smoke, spawned locally on fire — never synced per-particle
 * over the network (Create's cannon does the same client-side).
 *
 * <p>Particle count baselines on the potato cannon: 4 puffs per shot at
 * reloadTicks 20 (1x), scaling linearly with the ammo's own fire rate — a
 * 10-tick ammo fires twice as often, so it emits half the puffs per shot to
 * keep steady-state smoke roughly constant. The gun's fire_rate_multiplier is
 * intentionally NOT part of the puff count: it changes the cadence, not the
 * shot's energy.
 *
 * <p>Other players' shots arrive as {@link MuzzleSmokePacket} and are spawned
 * by {@link #onRemoteFire}: same local data (ammo cadence + muzzle device gas
 * guides), but without the shooter's render-pass muzzle anchor.
 */
public final class MuzzleSmoke {
    /** reloadTicks that map to the 1x potato-cannon baseline */
    private static final double BASE_RELOAD_TICKS = 20;

    private static final Random RANDOM = new Random();

    private MuzzleSmoke() {}

    public static void onFire(Player player) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        int reloadTicks = ClientGunInput.currentAmmoReloadTicks();
        if (reloadTicks <= 0) return;
        GunStats stats = GunStats.ofGun(player.getMainHandItem());

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
        // live gun-frame UP in world space: the render pass captured the
        // model +Y direction through the full gun pose matrix (stance cant,
        // recoil, animations included); undo the camera rotation like above.
        // This carries the gun's ROLL for gas-guide ports — the plume axis
        // stays ballistic (view ray), but the port phase follows the gun.
        Vec3 barrelUp = null;
        float[] upSample = MuzzleAnchor.viewUp();
        if (upSample != null) {
            org.joml.Quaternionf inv = new org.joml.Quaternionf()
                    .rotationY((float) Math.toRadians(-(cam.getYRot() + 180.0f)))
                    .rotateX((float) Math.toRadians(-cam.getXRot()));
            org.joml.Vector3f u = new org.joml.Vector3f(upSample[0], upSample[1], upSample[2]).rotate(inv);
            barrelUp = new Vec3(u.x, u.y, u.z);
        }
        // smoke velocity follows the view direction (approximation: the
        // server's exact per-pellet dir is not known client-side)
        Vec3 dir = player.getLookAngle();
        spawn(level, location, dir, barrelUp, reloadTicks, stats.muzzle, stats.gasSuppression);
    }

    /**
     * Another player's shot, relayed by the server. No render-pass sample of
     * their gun exists on this client, so the plume starts at their eye (one
     * block along the look, the same fallback the local path uses when its own
     * anchor is missing) and the gas-guide ports fall back to the world-up
     * frame instead of the gun's roll.
     */
    public static void onRemoteFire(MuzzleSmokePacket msg) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        if (msg.shooterId == mc.player.getId()) return; // the shooter already puffed
        if (!(level.getEntity(msg.shooterId) instanceof Player shooter)) return;
        PotatoCannonProjectileType type = PotatoCannonProjectileType
                .getTypeForItem(level.registryAccess(),
                        AmmoExtension.contentItemFor(level.registryAccess(), msg.ammoId))
                .map(ref -> ref.value()).orElse(null);
        if (type == null) return;
        int reloadTicks = type.reloadTicks();
        if (reloadTicks <= 0) return;
        net.minecraft.resources.ResourceLocation muzzleId = msg.muzzleId == null ? null
                : net.minecraft.resources.ResourceLocation.tryParse(msg.muzzleId);
        ModuleDefinition muzzle = muzzleId == null ? null : ModuleManager.get(muzzleId);
        Vec3 dir = shooter.getLookAngle();
        spawn(level, shooter.getEyePosition().add(dir.scale(1.0)), dir, null,
                reloadTicks, muzzle, msg.gasSuppression);
    }

    /**
     * The plume itself, shared by both entry points. Puff count: linear in the
     * ammo's own cadence, anchored at the potato-cannon baseline (reload_ticks
     * 20 = 1x), clamped so fast ammo never fully starves the smoke; the muzzle
     * device's gas suppression scales it (1 - v, v clamped -5..1).
     */
    private static void spawn(ClientLevel level, Vec3 location, Vec3 dir, @Nullable Vec3 barrelUp,
                              int reloadTicks, @Nullable ModuleDefinition muzzle, double gasSuppression) {
        double scale = Mth.clamp(reloadTicks / BASE_RELOAD_TICKS, 0.25, 2.5) * (1.0 - Mth.clamp(gasSuppression, -5, 1));
        int puffs = (int) Math.round(4 * scale);
        for (int i = 0; i < puffs; i++) {
            // custom puff: uniform size, fixed lifetime. Spawn points fill a
            // forward CONE (radius grows with distance along the shot axis,
            // sqrt-sampled disc + uniform phi like the server spread) and
            // velocity biases forward with a slight outward radial push —
            // an even, forward-extending plume instead of a ball at the tip
            Vec3 axis = dir;
            double speedMult = 1;
            double spreadMult = 1;
            // gas guides: the installed muzzle device's ports redirect most
            // puffs — pick a guide by weight (pass-through fraction skips)
            ModuleDefinition.GasGuide guide = muzzle != null && !muzzle.gasGuides.isEmpty()
                    && RANDOM.nextDouble() >= muzzle.gasPassThrough
                    ? pickGuide(muzzle.gasGuides) : null;
            if (guide != null) {
                // Gas-guide ports are authored in the GUN frame, but the
                // plume itself follows the BALLISTIC axis (the bullet leaves
                // along the view ray — hip-fire smoke along the artistic
                // first-person barrel direction looks wrong). The port PHASE
                // must roll with the gun around that ballistic axis only:
                // project the captured gun up (model +Y through the live
                // pose matrix — carries stance cant and recoil roll) onto
                // the plane perpendicular to the view ray first. The
                // projection strips the hip-pose pitch component (gun
                // tipped down while the barrel stays on the view ray) which
                // otherwise fakes a roll phase; what survives IS the roll.
                // No capture -> null -> offsetDirection falls back to a
                // world-up approximation.
                Vec3 gunUp = null;
                if (barrelUp != null) {
                    Vec3 projected = barrelUp.subtract(dir.scale(barrelUp.dot(dir)));
                    if (projected.lengthSqr() > 1.0E-4) gunUp = projected.normalize();
                }
                axis = offsetDirection(dir, gunUp, guide.directionX(), guide.directionY());
                speedMult = guide.velocityMultiplier();
                spreadMult = guide.spreadMultiplier();
            }
            double halfAngle = Math.toRadians(18 + RANDOM.nextDouble() * 14) * spreadMult;
            double phi = RANDOM.nextDouble() * 2 * Math.PI;
            double rr = Math.sqrt(RANDOM.nextDouble()) * Math.tan(halfAngle);
            Vec3 up = Math.abs(axis.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 u = axis.cross(up).normalize();
            Vec3 w = axis.cross(u).normalize();
            Vec3 radial = u.scale(Math.cos(phi) * rr).add(w.scale(Math.sin(phi) * rr));
            double dist = 0.1 + RANDOM.nextDouble() * 0.45;
            Vec3 pos = location.add(axis.scale(dist)).add(radial.scale(dist));
            Vec3 vel = axis.scale(0.2 * speedMult).add(radial.scale(0.1 * speedMult));
            level.addParticle((net.minecraft.core.particles.SimpleParticleType) ModParticles.MUZZLE_SMOKE.get(),
                    pos.x, pos.y, pos.z, vel.x, vel.y, vel.z);
        }
    }

    /** weight-proportional pick among the muzzle device's guide ports */
    private static ModuleDefinition.GasGuide pickGuide(java.util.List<ModuleDefinition.GasGuide> guides) {
        double total = 0;
        for (ModuleDefinition.GasGuide g : guides) total += Math.max(0, g.weight());
        if (total <= 0) return null;
        double r = RANDOM.nextDouble() * total;
        for (ModuleDefinition.GasGuide g : guides) {
            r -= Math.max(0, g.weight());
            if (r <= 0) return g;
        }
        return guides.get(guides.size() - 1);
    }

    /**
     * Direction rotated by (yawOffsetDeg, pitchOffsetDeg) around {@code dir},
     * in the reference frame defined by {@code frameUp} (the GUN's up —
     * ports are authored in the gun's frame). Null frameUp falls back to a
     * world up approximation (legacy behavior).
     */
    private static Vec3 offsetDirection(Vec3 dir, Vec3 frameUp, double yawDeg, double pitchDeg) {
        Vec3 up = frameUp != null ? frameUp
                : (Math.abs(dir.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0));
        // right = up × forward? No: in this frame u is the horizontal-ish
        // right; keep the original cross order for the world-up case and
        // derive right from up for gun frames
        Vec3 u = dir.cross(up).normalize();      // right relative to the frame's up
        Vec3 w = dir.cross(u).normalize();       // frame up relative to dir
        // yaw: rotate around w (horizontal spread); pitch: around u
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        Vec3 d1 = dir.scale(Math.cos(yaw)).add(u.scale(Math.sin(yaw)));
        return d1.scale(Math.cos(pitch)).add(w.scale(Math.sin(pitch))).normalize();
    }

}