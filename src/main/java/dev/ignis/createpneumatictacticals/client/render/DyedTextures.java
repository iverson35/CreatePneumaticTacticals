package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * Runtime bake of dye-region colors into module textures (plan_v2 配件染色,
 * the "bake into a dynamic texture" alternative to the shader route).
 *
 * <p>Each module texture may have a companion grayscale MASK
 * ({@code textures/gun/<id>_dye.png}). Mask brightness picks the dye region:
 * 0-63 -> region 1, 64-127 -> region 2, 128-191 -> region 3,
 * 192-255 -> untouched (keeps the original color). Region pixels are
 * recolored as {@code grayscale(base) * regionColor} (channelwise multiply,
 * so texture shading detail survives); the result is baked into a
 * DynamicTexture and cached. Baking only happens on a color change;
 * rendering reads the cached texture with zero per-frame cost.
 *
 * <p>Alpha is preserved as-is so entityCutout-style cutouts keep working.
 */
public final class DyedTextures {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DyedTextures() {}

    /** companion-mask convention: <id>.png -> <id>_dye.png */
    static ResourceLocation maskIdFor(ResourceLocation textureId) {
        String path = textureId.getPath();
        int dot = path.lastIndexOf('.');
        return new ResourceLocation(textureId.getNamespace(),
                path.substring(0, dot) + "_dye" + path.substring(dot));
    }

    /**
     * The texture to render: the dyed bake when the module has a companion
     * mask and non-default colors, otherwise the original texture id.
     * {@code argbRegions} = the module's 3 region colors (from item/gun NBT).
     */
    public static ResourceLocation resolve(ResourceLocation textureId, int[] argbRegions) {
        if (argbRegions == null || argbRegions.length < 3) return textureId;
        var rm = Minecraft.getInstance().getResourceManager();
        if (rm.getResource(textureId).isEmpty()) return textureId;
        Resource maskRes = rm.getResource(maskIdFor(textureId)).orElse(null);
        if (maskRes == null) return textureId; // module has no dye regions
        ResourceLocation dyedId = dyedId(textureId, argbRegions);
        var tm = Minecraft.getInstance().getTextureManager();
        if (tm.getTexture(dyedId, null) == null) {
            if (!bake(textureId, maskRes, dyedId, argbRegions)) return textureId;
        }
        return dyedId;
    }

    /**
     * Bake-cache id: one entry per (texture, color-triple). Colors are
     * hex-encoded into the path (ResourceLocation paths only allow
     * [a-z0-9/._-], so no '#' and nothing uppercase).
     */
    private static ResourceLocation dyedId(ResourceLocation textureId, int[] colors) {
        return new ResourceLocation("cptdyed",
                textureId.getNamespace() + "/" + textureId.getPath()
                        + String.format("/%08x_%08x_%08x", colors[0] & 0xFFFFFFFFL,
                        colors[1] & 0xFFFFFFFFL, colors[2] & 0xFFFFFFFFL));
    }

    /**
     * Bakes the dyed texture and registers it under {@code dyedId}.
     * Returns false when loading fails (caller falls back to the original).
     */
    private static boolean bake(ResourceLocation textureId, Resource maskRes,
                                ResourceLocation dyedId, int[] colors) {
        try (InputStream baseIn = Minecraft.getInstance().getResourceManager()
                .getResource(textureId).orElseThrow().open();
             InputStream maskIn = maskRes.open()) {
            NativeImage base = NativeImage.read(baseIn);
            NativeImage mask = NativeImage.read(maskIn);
            if (mask.getWidth() != base.getWidth() || mask.getHeight() != base.getHeight()) {
                LOGGER.warn("dye mask size mismatch for {}: mask {}x{}, base {}x{} — using original",
                        textureId, mask.getWidth(), mask.getHeight(), base.getWidth(), base.getHeight());
                return false;
            }
            int w = base.getWidth();
            int h = base.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int b = base.getPixelRGBA(x, y);
                    int m = mask.getPixelRGBA(x, y) & 0xFF; // mask red channel
                    if (m >= 192) continue; // untouched region
                    base.setPixelRGBA(x, y, dye(b, colors[m < 64 ? 0 : m < 128 ? 1 : 2]));
                }
            }
            Minecraft.getInstance().getTextureManager()
                    .register(dyedId, new DynamicTexture(base));
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("dye bake failed for {}: {}", textureId, e.toString());
            return false;
        }
    }

    /** grayscale(base) * color, preserving alpha */
    private static int dye(int base, int color) {
        int a = base & 0xFF000000;
        int r = (base >>> 16) & 0xFF;
        int g = (base >>> 8) & 0xFF;
        int bl = base & 0xFF;
        int lum = (r * 77 + g * 151 + bl * 28) >> 8;
        int cr = (color >>> 16) & 0xFF;
        int cg = (color >>> 8) & 0xFF;
        int cb = color & 0xFF;
        return a | ((lum * cr / 255) << 16) | ((lum * cg / 255) << 8) | (lum * cb / 255);
    }
}