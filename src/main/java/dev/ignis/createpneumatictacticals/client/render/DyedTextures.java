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
     * The texture to render: the dyed bake when the module has region
     * colors, otherwise the original texture id. {@code argbRegions} = the
     * module's 3 region colors (item/gun NBT or pack defaults).
     *
     * <p>A module WITHOUT a companion mask dyes its ENTIRE texture as
     * region 1 (mask == null -> every pixel is region 1; fully transparent
     * pixels still keep their alpha, so cutouts are unaffected).
     */
    public static ResourceLocation resolve(ResourceLocation textureId, int[] argbRegions) {
        if (argbRegions == null || argbRegions.length < 3) return textureId;
        // -1 = undyed slot (no defaults anymore); all-undyed = original
        if (argbRegions[0] < 0 && argbRegions[1] < 0 && argbRegions[2] < 0) return textureId;
        var rm = Minecraft.getInstance().getResourceManager();
        if (rm.getResource(textureId).isEmpty()) return textureId;
        Resource maskRes = rm.getResource(maskIdFor(textureId)).orElse(null);
        ResourceLocation dyedId = dyedId(textureId, argbRegions);
        var tm = Minecraft.getInstance().getTextureManager();
        if (tm.getTexture(dyedId, null) == null) {
            if (!bake(textureId, maskRes, dyedId, argbRegions)) return textureId;
        }
        return dyedId;
    }

    private static boolean bake(ResourceLocation textureId, Resource maskRes,
                                ResourceLocation dyedId, int[] colors) {
        try (InputStream baseIn = Minecraft.getInstance().getResourceManager()
                        .getResource(textureId).orElseThrow().open();
             InputStream maskIn = maskRes == null ? null : maskRes.open()) {
            NativeImage base = NativeImage.read(baseIn);
            NativeImage mask = maskIn == null ? null : NativeImage.read(maskIn);
            if (mask != null
                    && (mask.getWidth() != base.getWidth() || mask.getHeight() != base.getHeight())) {
                LOGGER.warn("dye mask size mismatch for {}: mask {}x{}, base {}x{} — treating as no mask",
                        textureId, mask.getWidth(), mask.getHeight(), base.getWidth(), base.getHeight());
                mask = null;
            }
            int w = base.getWidth();
            int h = base.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int b = base.getPixelRGBA(x, y);
                    int region = regionOf(mask, x, y); // -1 = keep original
                    if (region >= 0 && colors[region] >= 0) {
                        base.setPixelRGBA(x, y, dye(b, colors[region]));
                    }
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

    /**
     * Dye region for a pixel: no mask -> the WHOLE texture is region 1
     * (design rule: maskless modules dye entirely with the region-1
     * color); with a mask, brightness picks the region and 192+ keeps
     * the original color.
     */
    private static int regionOf(@org.jetbrains.annotations.Nullable NativeImage mask, int x, int y) {
        if (mask == null) return 0;
        int m = mask.getPixelRGBA(x, y) & 0xFF;
        if (m >= 192) return -1;
        return m < 64 ? 0 : m < 128 ? 1 : 2;
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
     * grayscale(base) * color, preserving alpha.
     *
     * <p>CHANNEL ORDER MISMATCH IS REAL: NativeImage pixels are ABGR
     * (bit0=R, bit16=B), while the stored dye colors are ARGB (bit16=R,
     * bit0=B). Swapping them dyed #9C7C4C brown into a blue-gray — decode
     * the base in ABGR, apply the color in ARGB, re-encode ABGR.
     */
    private static int dye(int baseAbgr, int colorArgb) {
        int a = baseAbgr & 0xFF000000;
        int r = baseAbgr & 0xFF;               // ABGR: red is the LOW byte
        int g = (baseAbgr >>> 8) & 0xFF;
        int b = (baseAbgr >>> 16) & 0xFF;
        int lum = (r * 77 + g * 151 + b * 28) >> 8;
        int cr = (colorArgb >>> 16) & 0xFF;     // ARGB: red is bits 16-23
        int cg = (colorArgb >>> 8) & 0xFF;
        int cb = colorArgb & 0xFF;
        // re-encode ABGR: red back to the low byte, blue to bits 16-23
        return a | (lum * cr / 255) | ((lum * cg / 255) << 8) | ((lum * cb / 255) << 16);
    }
}