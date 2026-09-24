package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only mod event bus listeners.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {

    private ClientModEvents() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(ModKeybinds.RELOAD);
        event.register(ModKeybinds.FIRE_MODE);
        event.register(ModKeybinds.AIM_STANCE);
        event.register(ModKeybinds.CYCLE_AMMO);
    }

    @SubscribeEvent
    public static void onRegisterBlockEntityRenderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity.GUN_WORKBENCH.get(),
                dev.ignis.createpneumatictacticals.client.render.GunWorkbenchRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new net.minecraft.server.packs.resources.ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
                dev.ignis.createpneumatictacticals.client.render.GunGlowLayer.invalidateCaches();
                dev.ignis.createpneumatictacticals.client.render.DyedTextures.invalidateCaches();
                dev.ignis.createpneumatictacticals.client.render.GunTextureAtlas.invalidate();
            }
        });
    }

    @SubscribeEvent
    public static void onRegisterParticles(net.minecraftforge.client.event.RegisterParticleProvidersEvent event) {
        net.minecraft.core.particles.ParticleType<?> type = dev.ignis.createpneumatictacticals.client.particle.ModParticles.MUZZLE_SMOKE.get();
        event.registerSpriteSet((net.minecraft.core.particles.SimpleParticleType) type,
                dev.ignis.createpneumatictacticals.client.particle.MuzzleSmokeParticle.Provider::new);
    }
}