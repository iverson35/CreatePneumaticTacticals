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
        event.register(ModKeybinds.INTERACT);
    }

    /**
     * The board's connection sheet holes out every border the board does not run
     * across, and the plate 0.5 px behind shows its frame through those texels:
     * on the solid layer they would write depth and hide it, so the block needs
     * alpha test.
     */
    @SubscribeEvent
    public static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
                dev.ignis.createpneumatictacticals.block.ModBlocks.WEAPON_PEGBOARD.get(),
                net.minecraft.client.renderer.RenderType.cutout());
    }

    @SubscribeEvent
    public static void onRegisterBlockEntityRenderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity.GUN_WORKBENCH.get(),
                dev.ignis.createpneumatictacticals.client.render.GunWorkbenchRenderer::new);
        event.registerBlockEntityRenderer(
                dev.ignis.createpneumatictacticals.block.entity.ModBlockEntities.MODULE_TUNER.get(),
                dev.ignis.createpneumatictacticals.client.render.ModuleTunerRenderer::new);
        event.registerBlockEntityRenderer(
                dev.ignis.createpneumatictacticals.block.entity.ModBlockEntities.AMMO_BOX.get(),
                dev.ignis.createpneumatictacticals.client.render.AmmoBoxRenderer::new);
        event.registerBlockEntityRenderer(
                dev.ignis.createpneumatictacticals.block.entity.ModBlockEntities.WEAPON_MODULE_MACHINE.get(),
                dev.ignis.createpneumatictacticals.client.render.WeaponModuleMachineRenderer::new);
        event.registerBlockEntityRenderer(
                dev.ignis.createpneumatictacticals.block.entity.ModBlockEntities.WEAPON_PEGBOARD.get(),
                dev.ignis.createpneumatictacticals.client.render.WeaponPegboardRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new net.minecraft.server.packs.resources.ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
                dev.ignis.createpneumatictacticals.client.render.GunGlowLayer.invalidateCaches();
                dev.ignis.createpneumatictacticals.client.render.DyedTextures.invalidateCaches();
                dev.ignis.createpneumatictacticals.client.render.GunTextureAtlas.invalidate();
                dev.ignis.createpneumatictacticals.client.render.PodItemModel.invalidateLayers();
                dev.ignis.createpneumatictacticals.client.render.WeaponModuleMachineRenderer.invalidateCaches();
            }
        });
    }

    /** Layer the content item's icon into the pod icons
     * (see {@link dev.ignis.createpneumatictacticals.client.render.PodItemModel}). */
    @SubscribeEvent
    public static void onModifyBakingResult(net.minecraftforge.client.event.ModelEvent.ModifyBakingResult event) {
        dev.ignis.createpneumatictacticals.client.render.PodItemModel.wrap(event.getModels());
        dev.ignis.createpneumatictacticals.client.render.PegboardCTBehaviour.wrap(event.getModels());
    }

    /**
     * The machine's drive-shaft stub is a Flywheel partial model
     * ({@link dev.ignis.createpneumatictacticals.client.render.WeaponModuleMachineRenderer#SHAFT_STUB_MODEL}):
     * {@code PartialModel.of} only looks models up in the baked registry, it
     * never registers them for loading — an unregistered location bakes as the
     * missing model, a full-block purple cube where the shaft should be.
     */
    @SubscribeEvent
    public static void onRegisterAdditionalModels(net.minecraftforge.client.event.ModelEvent.RegisterAdditional event) {
        event.register(dev.ignis.createpneumatictacticals.client.render.WeaponModuleMachineRenderer.SHAFT_STUB_MODEL);
    }

    @SubscribeEvent
    public static void onRegisterParticles(net.minecraftforge.client.event.RegisterParticleProvidersEvent event) {
        // both types are registered as plain SimpleParticleType (see ModParticles),
        // which is what the sprite-set overload needs
        event.registerSpriteSet((net.minecraft.core.particles.SimpleParticleType)
                        dev.ignis.createpneumatictacticals.client.particle.ModParticles.MUZZLE_SMOKE.get(),
                dev.ignis.createpneumatictacticals.client.particle.MuzzleSmokeParticle.Provider::new);
        event.registerSpriteSet((net.minecraft.core.particles.SimpleParticleType)
                        dev.ignis.createpneumatictacticals.client.particle.ModParticles.TRAIL_SMOKE.get(),
                dev.ignis.createpneumatictacticals.client.particle.TrailSmokeParticle.Provider::new);
    }
}