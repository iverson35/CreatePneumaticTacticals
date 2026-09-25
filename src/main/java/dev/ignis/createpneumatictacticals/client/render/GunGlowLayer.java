package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;

import java.io.IOException;

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
    static final int FULLBRIGHT = 15728640;
    static final int NO_OVERLAY = 0;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** glowmask existence per texture id (the resource-manager lookup
     * walks the whole pack chain, ~0.1ms per miss — times every module
     * of every rendered gun every frame); cleared on resource reload */
    private static final java.util.Map<ResourceLocation, Boolean> GLOWMASK = new java.util.HashMap<>();

    /** per-texture emissive type for the legacy (non-atlas) path; a
     * RenderType is identity-compared by the buffer source, so one instance
     * per texture matters (a fresh one per frame would split the batch) */
    private static final java.util.Map<ResourceLocation, RenderType> LEGACY_GLOW = new java.util.HashMap<>();

    /** clears the glowmask cache; called by the client reload listener */
    public static void invalidateCaches() {
        GLOWMASK.clear();
        LEGACY_GLOW.clear();
    }

    public GunGlowLayer(GeoRenderer<GeoGunItem> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, GeoGunItem animatable, BakedGeoModel model,
                       RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                       float partialTick, int packedLight, int packedOverlay) {
        ResourceLocation texture = getRenderer().getGeoModel().getTextureResource(animatable);
        // atlas route: same slot the base pass just retargeted the model to
        GunTextureAtlas.Slot slot = GunTextureAtlas.acquire(texture, null);
        if (slot != null && GunTextureAtlas.retarget(model, slot)) {
            if (slot.glow()) {
                getRenderer().reRender(model, poseStack, bufferSource, animatable, GunTextureAtlas.GLOW,
                        bufferSource.getBuffer(GunTextureAtlas.GLOW), partialTick, FULLBRIGHT, NO_OVERLAY, 1, 1, 1, 1);
            }
            return;
        }
        if (!hasGlowMask(texture)) return;
        RenderType glow = legacyGlow(texture);
        if (glow == null) return;
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
        RenderType glow = legacyGlow(textureId);
        if (glow == null) return;
        renderer.reRender(model, poseStack, bufferSource, animatable, glow,
                bufferSource.getBuffer(glow), partialTick, FULLBRIGHT, NO_OVERLAY, 1, 1, 1, 1);
    }

    /**
     * Emissive type for a texture that is NOT in the atlas: reads the base
     * and glowmask RESOURCES, bakes the glow image (glow pixel = base RGB +
     * mask alpha, GeckoLib's createImageMask recipe) and registers it as its
     * own texture. The base texture is never touched, so every other pass
     * that binds it — most visibly the module item icon — keeps its pixels.
     * The glow image keeps the base RGB, so blending it over the base pass
     * reproduces the holed-base look.
     */
    private static @org.jetbrains.annotations.Nullable RenderType legacyGlow(ResourceLocation texture) {
        RenderType cached = LEGACY_GLOW.get(texture);
        if (cached != null) return cached;
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        var baseRes = rm.getResource(texture);
        var maskRes = rm.getResource(glowmaskIdFor(texture));
        if (baseRes.isEmpty() || maskRes.isEmpty()) return null;
        try (var baseIn = baseRes.get().open(); var maskIn = maskRes.get().open()) {
            NativeImage base = NativeImage.read(baseIn);
            NativeImage mask = NativeImage.read(maskIn);
            int w = base.getWidth(), h = base.getHeight();
            if (mask.getWidth() != w || mask.getHeight() != h) {
                LOGGER.warn("glowmask size mismatch for {}: mask {}x{}, base {}x{} — glow skipped",
                        texture, mask.getWidth(), mask.getHeight(), w, h);
                base.close();
                mask.close();
                return null;
            }
            NativeImage glow = new NativeImage(w, h, true);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int m = mask.getPixelRGBA(x, y);
                    if (m == 0) continue; // GeckoLib skips fully-black mask pixels
                    int alpha = (m >>> 24) & 0xFF;
                    int p = base.getPixelRGBA(x, y);
                    // ABGR: base channels kept, alpha swapped for the mask's
                    glow.setPixelRGBA(x, y, alpha > 0 ? (alpha << 24) | (p & 0xFFFFFF) : p);
                }
            }
            base.close();
            mask.close();
            ResourceLocation id = new ResourceLocation(texture.getNamespace(),
                    texture.getPath() + "_cptglow");
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(glow));
            RenderType type = GunTextureAtlas.glowTypeFor(id);
            LEGACY_GLOW.put(texture, type);
            return type;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("legacy glow bake failed for {}: {}", texture, e.toString());
            return null;
        }
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
        ResourceLocation maskId = glowmaskIdFor(textureId);
        Boolean cached = GLOWMASK.get(textureId);
        if (cached != null) return cached;
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        boolean present = rm.getResource(maskId).isPresent();
        GLOWMASK.put(textureId, present);
        return present;
    }

    /** sibling glowmask id: <id>.png -> <id>_glowmask.png */
    static ResourceLocation glowmaskIdFor(ResourceLocation textureId) {
        String path = textureId.getPath();
        int dot = path.lastIndexOf('.');
        return new ResourceLocation(textureId.getNamespace(),
                path.substring(0, dot) + "_glowmask" + path.substring(dot));
    }
}