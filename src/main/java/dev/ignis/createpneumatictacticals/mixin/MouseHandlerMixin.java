package dev.ignis.createpneumatictacticals.mixin;

import dev.ignis.createpneumatictacticals.client.AimHandler;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Scales mouse sensitivity while aiming so screen-space turn speed stays
 * consistent under zoom (same eased factor as the FOV transition). Mirrors
 * pointblank's ClientSystem#modifyMouseSensitivity injection point.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Redirect(method = "turnPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
    private Object createpneumatictacticals$aimSensitivity(OptionInstance<?> self) {
        Object value = self.get();
        if (value instanceof Double d) {
            return d * AimHandler.aimViewScale();
        }
        return value;
    }
}
