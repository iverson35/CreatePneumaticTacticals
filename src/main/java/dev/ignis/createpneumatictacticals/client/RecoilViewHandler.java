package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Applies the recoil model to the camera (plan_v2 后坐力 view 层):
 * pitch/yaw offsets accumulate per shot and decay via RecoilModel.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class RecoilViewHandler {

    private RecoilViewHandler() {}

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        double pitch = RecoilModel.pitchDegrees();
        double yaw = RecoilModel.yawDegrees();
        if (pitch == 0 && yaw == 0) return;
        event.setPitch((float) (event.getPitch() - pitch)); // recoil pushes up
        event.setYaw((float) (event.getYaw() - yaw));
    }
}
