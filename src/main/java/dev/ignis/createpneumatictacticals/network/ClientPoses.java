package dev.ignis.createpneumatictacticals.network;

import dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of tracked players' gun poses (from PoseBroadcastPacket).
 */
public final class ClientPoses {

    private static final Map<Integer, Pose> POSES = new ConcurrentHashMap<>();

    private ClientPoses() {}

    public static void set(int entityId, Pose pose) {
        POSES.put(entityId, pose);
    }

    public static Pose get(int entityId) {
        return POSES.getOrDefault(entityId, Pose.HIP);
    }

    public static void remove(int entityId) {
        POSES.remove(entityId);
    }
}