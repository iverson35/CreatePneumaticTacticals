package dev.ignis.createpneumatictacticals.gun;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.sound.ModSoundEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * Spent-shell landing click for CARTRIDGE-supply guns: each successful shot
 * queues a simulated casing ballistics solve, and when the simulated casing
 * lands the server broadcasts a random shell_drop variant at the landing
 * spot (heard by everyone — the shot itself excluded the shooter client-side,
 * but the landing is a world event everyone hears).
 *
 * <p>The simulation mirrors vanilla {@code ItemEntity} physics: gravity
 * 0.04/tick, drag 0.98/tick, per-tick block collision via a
 * {@link ClipContext.Block#COLLIDER} ray. The eject port sits at the eye
 * +0.5 blocks toward the look direction, 0.3 below the eye line, ejected
 * rightward of the view with a soft upward bias. Solves whose fall exceeds
 * {@link #MAX_FALL} blocks never play (10-block rule — a casing falling off
 * a cliff stays silent).
 *
 * <p>Landing times are scheduled on a single global queue in whole ticks;
 * at typical ejection speeds the flight is tens of ticks, so tick
 * granularity is inaudible next to the shell's actual flight time.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID)
public final class ShellDropScheduler {

    /** maximum fall distance (blocks) for the landing click to play */
    private static final double MAX_FALL = 10;
    /** ejection port: 0.5 blocks toward look, 0.3 below the eye line */
    private static final double PORT_FORWARD = 0.5, PORT_DOWN = 0.3;
    /** casing kick: sideways magnitude, plus a soft upward bias */
    private static final double KICK_SIDE = 0.18, KICK_UP = 0.12;
    /** solve cap: beyond this the casing is treated as lost (void/cavern) */
    private static final int MAX_TICKS = 200;

    /** one pending landing: play in level, at game tick, at position */
    private record Pending(ServerLevel level, long atTick, Vec3 at) {}

    private static final Queue<Pending> QUEUE = new PriorityQueue<>(
            Comparator.comparingLong(Pending::atTick));

    private ShellDropScheduler() {}

    /** Called by GunFireHandler after a successful CARTRIDGE-supply shot. */
    public static void onCartridgeShot(ServerLevel level, Player shooter) {
        // ejection port: right of the view, slightly below the eye, forward
        Vec3 look = shooter.getLookAngle();
        // horizontal right; degenerate (looking straight up/down) falls
        // back to the player's yaw vector — never normalize a zero vector
        double rx = -look.z, rz = look.x;
        if (rx * rx + rz * rz < 1.0E-6) {
            double yawRad = Math.toRadians(shooter.getYRot());
            rx = -Math.sin(yawRad);
            rz = Math.cos(yawRad);
        }
        Vec3 right = new Vec3(rx, 0, rz).normalize();
        Vec3 origin = shooter.getEyePosition()
                .add(look.scale(PORT_FORWARD))
                .add(right.scale(0.15))
                .add(0, -PORT_DOWN, 0);
        // casing kick: rightward + soft up bias; the shot's own motion is
        // NOT inherited (a casing does not fly with the bullet)
        Vec3 vel = right.scale(KICK_SIDE).add(0, KICK_UP, 0);

        long tick = level.getGameTime();
        for (int i = 0; i < MAX_TICKS; i++) {
            vel = vel.scale(0.98);                       // item drag
            vel = vel.add(0, -0.04, 0);                  // item gravity
            Vec3 next = origin.add(vel);
            BlockHitResult hit = level.clip(new ClipContext(origin, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
            if (hit.getType() != BlockHitResult.Type.MISS) {
                double fall = shooter.getEyeY() - hit.getLocation().y;
                if (fall > MAX_FALL) return;              // 10-block rule: silent
                QUEUE.add(new Pending(level, tick + i + 1, hit.getLocation()));
                return;
            }
            origin = next;
        }
        // airborne past MAX_TICKS without a hit: casing left the loaded area
        // (void/large cavern) — silently drop it
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // iterate a COPY: PriorityQueue's iterator has no heap order and
        // its remove() is O(n); drain due entries via poll() instead
        while (!QUEUE.isEmpty() && QUEUE.peek().atTick <= QUEUE.peek().level.getGameTime()) {
            Pending p = QUEUE.poll();
            p.level.playSound(null, p.at.x, p.at.y, p.at.z,
                    ModSoundEvents.SHELL_DROP.get(), SoundSource.PLAYERS, 0.7f, 1.0f);
        }
    }
}