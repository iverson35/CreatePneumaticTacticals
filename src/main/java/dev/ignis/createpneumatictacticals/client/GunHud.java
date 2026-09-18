package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;

/**
 * HUD: four-line dynamic hipfire crosshair + hitmarker. Ammo counter and fire
 * mode text are drawn near the hotbar when holding a gun.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class GunHud {

    private static long hitmarkerUntil = 0;

    private GunHud() {}

    public static void showHitmarker() {
        if (Config.hitmarkerEnabled) hitmarkerUntil = System.currentTimeMillis() + 300;
    }

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay().id() != VanillaGuiOverlay.CROSSHAIR.id()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        var stack = event.getGuiGraphics();
        int x = mc.getWindow().getGuiScaledWidth() / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2;

        // crosshair is drawn by vanilla; spread indicator lines would go here.
        // hitmarker
        if (System.currentTimeMillis() < hitmarkerUntil) {
            drawHitmarker(stack, x, y);
        }
    }

    private static void drawHitmarker(GuiGraphics g, int x, int y) {
        int c = 0xFFFFFFFF;
        int d = 4;
        int l = 3;
        g.fill(x - d - l, y - d - l, x - d, y - d, c);
        g.fill(x + d, y - d - l, x + d + l, y - d, c);
        g.fill(x - d - l, y + d, x - d, y + d + l, c);
        g.fill(x + d, y + d, x + d + l, y + d + l, c);
    }
}