package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.texture.AutoGlowingTexture;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;

/**
 * Emissive pass for any gun part whose texture has a sibling
 * {@code <name>_glowmask.png} (alpha marks what glows; color comes from the
 * base texture — see GeckoLib's GeoGlowingTextureMeta.createImageMask).
 * GeckoLib's AutoGlowingGeoLayer hardcodes fullbright (0xF000F0), which is
 * exactly what a laser beam needs; this layer applies the same recipe.
 *
 * <p>Receiver: registered on {@link GunHandsAwareRenderer}; fires after the
 * base entityCutout pass with the same baked model.
 *
 * <p>Modules: {@link GunModulesLayer} draws module models via reRender,
 * which renderer-registered layers never see, so it calls
 * {@link #renderForModule} directly after its base pass.
 *
 * <p>Overlay uses NO_OVERLAY (0) rather than the pass's packedOverlay so a
 * damage-tinted gun doesn't recolor the emissive pixels (same choice as
 * GeckoLib's AutoGlowingGeoLayer, which ignores overlay entirely).
 */
public final class GunGlowLayer extends GeoRenderLayer<GeoGunItem> {

    /** fullbright: sky + block light at maximum (what AutoGlowingGeoLayer uses) */
    private static final int FULLBRIGHT = 15728640;
    private static final int NO_OVERLAY = 0;

    /** glowmask existence per texture id (the resource-manager lookup
     * walks the whole pack chain, ~0.1ms per miss — times every module
     * of every rendered gun every frame); cleared on resource reload */
    private static final java.util.Map<ResourceLocation, Boolean> GLOWMASK = new java.util.HashMap<>();

    /** clears the glowmask cache; called by the client reload listener */
    public static void invalidateCaches() {
        GLOWMASK.clear();
    }

    public GunGlowLayer(GeoRenderer<GeoGunItem> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, GeoGunItem animatable, BakedGeoModel model,
                       RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                       float partialTick, int packedLight, int packedOverlay) {
        ResourceLocation texture = getRenderer().getGeoModel().getTextureResource(animatable);
        if (!hasGlowMask(texture)) return;
        RenderType glow = AutoGlowingTexture.getRenderType(texture);
        getRenderer().reRender(model, poseStack, bufferSource, animatable, glow,
                bufferSource.getBuffer(glow), partialTick, FULLBRIGHT, NO_OVERLAY, 1, 1, 1, 1);
    }

    /**
     * Second, fullbright pass over a module model — called by
     * GunModulesLayer.mount right after its entityCutoutNoCull pass, inside
     * the module's pushed pose (the caller pops it).
     */
    public static void renderForModule(BakedGeoModel model, GeoGunItem animatable,
                                       PoseStack poseStack, MultiBufferSource bufferSource,
                                       float partialTick, ResourceLocation textureId,
                                       GeoRenderer<GeoGunItem> renderer) {
        if (!hasGlowMask(textureId)) return;
        RenderType glow = AutoGlowingTexture.getRenderType(textureId);
        renderer.reRender(model, poseStack, bufferSource, animatable, glow,
                bufferSource.getBuffer(glow), partialTick, FULLBRIGHT, NO_OVERLAY, 1, 1, 1, 1);
    }

    /**
     * True if {@code <path>_glowmask.png} exists next to the given texture.
     * Cached per texture id; a resource reload clears the cache, so F3+T
     * swaps are still picked up.
     */
    public static boolean hasGlowMask(ResourceLocation textureId) {
        String path = textureId.getPath();
        int dot = path.lastIndexOf('.');
        if (dot < 0) return false;
        ResourceLocation maskId = new ResourceLocation(textureId.getNamespace(),
                path.substring(0, dot) + "_glowmask" + path.substring(dot));
        Boolean cached = GLOWMASK.get(textureId);
        if (cached != null) return cached;
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        boolean present = rm.getResource(maskId).isPresent();
        GLOWMASK.put(textureId, present);
        return present;
    }
}