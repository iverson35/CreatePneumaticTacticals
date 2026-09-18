package dev.ignis.createpneumatictacticals.mixin;

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
 * Appends a procedural left-arm swing to players in RELOADING pose (plan_v2
 * 第三人称表现). Base pose is CROSSBOW_HOLD from IClientItemExtensions.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {

    @Shadow
    public ModelPart leftArm;

    @Inject(method = "setupAnim", at = @At("TAIL"))
    private void cpt$reloadSwing(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                 float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (ClientPoses.get(entity.getId()) != PoseBroadcastPacket.Pose.RELOADING) return;
        // procedural swing: ~2 Hz X-axis oscillation, amplitude 25°
        float swing = (float) Math.sin(ageInTicks * 0.4f) * 0.45f;
        leftArm.xRot += swing;
    }
}