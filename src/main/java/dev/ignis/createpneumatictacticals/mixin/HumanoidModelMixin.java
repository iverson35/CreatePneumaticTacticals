package dev.ignis.createpneumatictacticals.mixin;

import dev.ignis.createpneumatictacticals.client.ReadyArmPoseTuning;
import dev.ignis.createpneumatictacticals.network.ClientPoses;
import dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third-person gun poses (plan_v2 第三人称表现), applied additively on top of
 * the CROSSBOW_HOLD base pose from IClientItemExtensions:
 *  - RELOADING: procedural left-arm swing
 *  - LOW/HIGH_READY: arm offsets from ReadyArmPoseTuning, blended in/out over
 *    ~150ms so stance changes don't snap. Additive only: composes with
 *    whatever other mods did to the arms.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {

    @Shadow
    public ModelPart leftArm;

    @Shadow
    public ModelPart rightArm;

    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void cpt$gunPoses(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                              float netHeadYaw, float headPitch, CallbackInfo ci) {
        PoseBroadcastPacket.Pose pose = ClientPoses.get(entity.getId());
        if (pose == PoseBroadcastPacket.Pose.RELOADING) {
            // procedural swing: ~2 Hz X-axis oscillation, amplitude 25°
            leftArm.xRot += (float) Math.sin(ageInTicks * 0.4f) * 0.45f;
        }

        boolean ready = pose == PoseBroadcastPacket.Pose.LOW_READY || pose == PoseBroadcastPacket.Pose.HIGH_READY;
        ReadyArmPoseTuning.BlendResult blend =
                ReadyArmPoseTuning.update(entity.getId(), ready, pose == PoseBroadcastPacket.Pose.HIGH_READY);
        if (blend.ease() > 0) {
            ReadyArmPoseTuning.ArmOffset r = blend.high() ? ReadyArmPoseTuning.HIGH_RIGHT : ReadyArmPoseTuning.LOW_RIGHT;
            ReadyArmPoseTuning.ArmOffset l = blend.high() ? ReadyArmPoseTuning.HIGH_LEFT : ReadyArmPoseTuning.LOW_LEFT;
            cpt$apply(rightArm, r, blend.ease());
            cpt$apply(leftArm, l, blend.ease());
        }
        boolean ads = pose == PoseBroadcastPacket.Pose.ADS || pose == PoseBroadcastPacket.Pose.TACTICAL;
        float adsEase = ReadyArmPoseTuning.updateAds(entity.getId(), ads, entity.getMainHandItem());
        if (adsEase > 0) {
            cpt$apply(rightArm, ReadyArmPoseTuning.ADS_RIGHT, adsEase);
            cpt$apply(leftArm, ReadyArmPoseTuning.ADS_LEFT, adsEase);
        }

    }

    private static void cpt$apply(ModelPart arm, ReadyArmPoseTuning.ArmOffset o, float e) {
        arm.xRot += o.xRot * e;
        arm.yRot += o.yRot * e;
        arm.zRot += o.zRot * e;
        arm.x += o.x * e;
        arm.y += o.y * e;
        arm.z += o.z * e;
    }
}
