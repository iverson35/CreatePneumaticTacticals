package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.client.gui.ModuleWorkbenchScreen;
import dev.ignis.createpneumatictacticals.menu.CptMenuTypes;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Binds container screens to their menu types. Forge 1.20.1: MenuScreens
 * .register within FMLClientSetupEvent (enqueued to run on the render thread).
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MenuScreenBinding {

    private MenuScreenBinding() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(CptMenuTypes.MODULE_WORKBENCH.get(), ModuleWorkbenchScreen::new);
        });
    }

    @SubscribeEvent
    public static void onRegisterOverlays(net.minecraftforge.client.event.RegisterGuiOverlaysEvent event) {
        GunHudOverlay.register(event);
    }
}