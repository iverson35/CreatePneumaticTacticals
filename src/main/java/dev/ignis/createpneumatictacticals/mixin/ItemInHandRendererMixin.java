package dev.ignis.createpneumatictacticals.mixin;

import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Guns skip the vanilla item "raise" (the equip animation).
 *
 * <p>{@code ItemInHandRenderer} keeps a {@code mainHandHeight} that dips to 0
 * whenever the main-hand stack instance changes (hotbar switch, picking the
 * gun up, login) and climbs back with the attack-cooldown scale — so every
 * item rises from below the screen over a few ticks. For a gun that raise is
 * a stutter stacked on top of our own first-person placement: the draw pose
 * (low ready), the ready -> hipfire transition, ADS and recoil own where the
 * gun sits. This mixin pins the rendered raise offset to 0 for gun stacks, so
 * a gun appears exactly where the pose system puts it. Every other item keeps
 * the vanilla animation untouched, and the vanilla state
 * ({@code mainHandHeight}) itself is left alone — only the value handed to the
 * arm transform for a gun is replaced.
 *
 * <p>{@code require = 0}: if another mod moves the injection point, guns just
 * get the vanilla raise back. No state, no crash.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

    /**
     * @param equipRaise vanilla 0..1 raise offset for this frame
     *                   (0 = settled, 1 = fully raised from below)
     */
    @ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 3, require = 0)
    private float createpneumatictacticals$skipGunEquipRaise(float equipRaise,
            AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand,
            float swingProgress, ItemStack stack) {
        return stack.getItem() instanceof GeoGunItem ? 0.0F : equipRaise;
    }
}
