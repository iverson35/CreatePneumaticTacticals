package dev.ignis.createpneumatictacticals.mixin;

import dev.ignis.createpneumatictacticals.client.ReadyModel;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side: a sluggish gun out of its ready pose denies sprinting.
 *
 * <p>{@code ReadyModel} decides the pose (see its "sluggish guns" note): the
 * firing stance — aiming, attack held, or a shot within the last second —
 * pins the shooter to a walk, and the ready pose comes back after the very
 * delay that raises the gun. Vanilla's own sprint start sits in
 * {@code LocalPlayer.aiStep} and only consults the movement input (forward
 * impulse >= 0.8), so the only way to deny a sprint WITHOUT also slowing the
 * walk is to clear the flag after that block ran: the flag then reads false
 * for the movement, the FOV and the render of the next tick, and the sprint
 * state sync (which runs after {@code aiStep} in {@code tick()}) never sees a
 * change to report — no START_SPRINTING spam, and the server's flag follows
 * the client as usual.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSprintMixin {

    @Inject(method = "aiStep", at = @At("RETURN"))
    private void createpneumatictacticals$denySprintWhileGunRaised(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.isSprinting() && ReadyModel.blocksSprint(self)) {
            self.setSprinting(false);
        }
    }
}
