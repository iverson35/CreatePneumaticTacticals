package dev.ignis.createpneumatictacticals.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Three-layer recoil (plan_v2 后坐力系统):
 * 1. view: pitch up per shot, decays by recoilRecovery
 * 2. model: gun model kick via damped spring (consumed by renderer)
 * 3. screen shake: slight shake scaled by recoilMultiplier
 */
public final class RecoilModel {

    private static double pitchOffset = 0;   // degrees, positive = up
    private static double yawOffset = 0;     // degrees
    private static double springPos = 0;     // model kick, arbitrary units
    private static double springVel = 0;
    private static double shake = 0;

    private RecoilModel() {}

    public static void onShot(double basePitch, double baseYaw, double recoilMult, boolean aiming) {
        double scale = recoilMult * (aiming ? 0.7 : 1.0);
        pitchOffset += basePitch * scale;
        yawOffset += (Math.random() * 2 - 1) * baseYaw * scale;
        springVel += 40 * scale;
        shake += 1.5 * scale;
    }

    public static void tick(Player player) {
        // view recovery: exponential decay toward 0 based on recovery stat (per tick)
        double recovery = 0.15; // scaled by gun recoil_recovery elsewhere
        pitchOffset *= (1 - recovery);
        yawOffset *= (1 - recovery);
        // damped spring for model
        springVel += (-springPos * 300 - springVel * 20) * 0.05;
        springPos += springVel * 0.05;
        shake *= 0.8;
    }

    /** apply to camera pitch/radians helpers */
    public static double pitchDegrees() {
        return pitchOffset;
    }

    public static double yawDegrees() {
        return yawOffset;
    }

    public static double modelKick() {
        return springPos;
    }

    public static double shake() {
        return shake;
    }

    public static void clear() {
        pitchOffset = yawOffset = springPos = springVel = shake = 0;
    }
}