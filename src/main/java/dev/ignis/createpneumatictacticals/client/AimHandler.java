package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Aim state (hold right mouse with a gun): FOV zoom by the installed sight,
 * movement slowed, sprint cancelled. Read via {@link #isAiming()}.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class AimHandler {

    private AimHandler() {}

    /** True while holding a gun and holding right mouse. */
    public static boolean isAiming() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        return player != null && mc.screen == null
                && player.getMainHandItem().getItem() instanceof GeoGunItem
                && mc.options.keyUse.isDown();
    }

    /** Current zoom factor for the held gun (aim stance aware). */
    public static double zoom() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return 1.0;
        ItemStack gun = player.getMainHandItem();
        GunStats stats = GunStats.of(GunNbt.readModules(gun));
        return "tactical".equals(GunNbt.getAimStance(gun)) ? stats.tacticalAimZoom : stats.aimZoom;
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!isAiming()) return;
        event.setFOV(event.getFOV() / zoom());
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!isAiming()) return;
        // aim walk: 40% speed, sprint cancelled
        event.getInput().leftImpulse *= 0.4f;
        event.getInput().forwardImpulse *= 0.4f;
        event.getEntity().setSprinting(false);
    }
}