package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Global runtime texture atlas for gun parts (docs/gun-atlas-design.md).
 *
 * <p>One lazily-filled canvas replaces the per-module textures so every
 * module of every gun on screen draws through a single RenderType —
 * {@code BufferSource} only flushes on type changes, so a fully equipped
 * gun drops from ~11 immediate draws to 1 (plus 1 glow). Slot placement
 * is a pure function of the texture key and session allocation history,
 * NEVER of any one gun loadout (shared GeckoLib models can only carry
 * one set of UVs); models get their quads rewritten into their slot on
 * demand and restored to the original when they fall back out.
 *
 * <p>Regions: the 1024px canvas is pre-partitioned into 64/32/16px size
 * classes (~1400 slots). A texture occupies the smallest class that fits;
 * slots may hold smaller images (the UV mapping is affine, the image does
 * not need to fill its slot). Oversized (&gt;64px) or overflowed textures
 * are remembered and stay on the legacy per-texture path.
 *
 * <p>Variants: a dyed (texture x colors) combo claims its OWN slot with
 * the dye baked into the pixels — invisible to slot allocation, which is
 * what makes full-RGB dyeing cheap. Variant slots are LRU-capped; base
 * slots live for the session. Models never cache slot pointers: the
 * registry is consulted per draw, so eviction is safe (the next draw
 * re-bakes into a fresh slot and rewrites the UVs).
 *
 * <p>Glow: a second canvas mirrors the layout. GeckoLib's glowmask
 * recipe is replicated per slot (glow pixel = base RGB + mask alpha,
 * base pixel holed out), so dyed modules keep their glow — the legacy
 * per-texture path loses it (the dyed bake has no glowmask sibling).
 */
public final class GunTextureAtlas {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** canvas edge in px; one constant bounds VRAM (base + glow = 8MB) */
    public static final int CANVAS = 1024;
    /** textures larger than this never enter the atlas (legacy path) */
    public static final int MAX_SLOT = 64;
    /** dyed (texture x colors) combos cached at once; base slots uncapped */
    private static final int VARIANT_LIMIT = 256;

    public static final ResourceLocation ATLAS_ID = new ResourceLocation("cptatlas", "gun");
    public static final ResourceLocation GLOW_ATLAS_ID = new ResourceLocation("cptatlas", "gun_glow");

    /** the shared draw types — identical states to the per-texture ones */
    public static final RenderType CUTOUT = RenderType.entityCutoutNoCull(ATLAS_ID);
    public static final RenderType TRANSLUCENT = RenderType.entityTranslucent(ATLAS_ID);

    /** glow pass type: GeckoLib geo_glowing_layer recipe bound to the glow canvas */
    public static final RenderType GLOW = Shards.GLOW;

    /** protected-state access: TRANSLUCENT_TRANSPARENCY and friends need a subclass */
    private static final class Shards extends RenderStateShard {
        Shards() { super("cpt_gun_atlas_shards", () -> {}, () -> {}); }

        private static final RenderType GLOW = RenderType.create("cpt_gun_atlas_glow",
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, true,
                RenderType.CompositeState.builder()
                        .setShaderState(new ShaderStateShard(GameRenderer::getRendertypeEntityTranslucentEmissiveShader))
                        .setTextureState(new TextureStateShard(GLOW_ATLAS_ID, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .createCompositeState(false));
    }

    /** atlas slot: canvas origin, the texture's true pixel size, its size class and glow */
    public record Slot(int x, int y, int w, int h, int size, boolean glow) {}

    /** registry key: base textures use the -1 sentinel colors */
    private record TexKey(ResourceLocation tex, int c0, int c1, int c2) {}

    /** one size-class band of the canvas */
    private static final class Region {
        final int size, cols, y0;
        final ArrayDeque<Integer> free = new ArrayDeque<>();
        int next = 0;

        Region(int size, int cols, int rows, int y0) {
            this.size = size;
            this.cols = cols;
            this.y0 = y0;
        }

        int capacity() {
            return cols * ((CANVAS - y0) / size);
        }

        /** @return {x, y} slot origin, or null when the band is full */
        @Nullable int[] take() {
            Integer idx = free.pollFirst();
            if (idx == null) {
                if (next >= capacity()) return null;
                idx = next++;
            }
            return new int[]{(idx % cols) * size, y0 + (idx / cols) * size};
        }

        void release(Slot slot) {
            free.addFirst(((slot.y() - y0) / size) * cols + slot.x() / size);
        }

        void reset() {
            free.clear();
            next = 0;
        }
    }

    // 1024px canvas bands: 64px (y 0-511, 128 slots), 32px (y 512-767,
    // 256 slots), 16px (y 768-1023, 1024 slots)
    private static final Region[] REGIONS = {
            new Region(16, 64, 16, 768),
            new Region(32, 32, 8, 512),
            new Region(64, 16, 8, 0),
    };

    /** the registry: texture key -> slot (session-stable, reload-cleared) */
    private static final Map<TexKey, Slot> SLOTS = new HashMap<>();
    /** dyed keys only, access-ordered for LRU; values share SLOTS instances */
    private static final LinkedHashMap<TexKey, Slot> VARIANTS = new LinkedHashMap<>(64, 0.75f, true);
    /** keys proven ineligible (missing/oversized/overflowed) — don't retry */
    private static final HashSet<TexKey> NO_SLOT = new HashSet<>();
    /** decoded PNGs, shared by every slot bake; Optional.empty = missing */
    private static final Map<ResourceLocation, Optional<NativeImage>> DECODED = new HashMap<>();

    /** per-model UV state; identity keys so a GeckoLib rebake self-invalidates */
    private static final IdentityHashMap<BakedGeoModel, ModelState> STATES = new IdentityHashMap<>();

    private static final class ModelState {
        /** null arrays = ineligible (UVs outside [0,1]; atlas cannot emulate wrap) */
        final @Nullable GeoVertex[][] arrays;
        final float[] u, v;
        Slot current; // slot instance the UVs currently point at (null = original)

        ModelState(@Nullable GeoVertex[][] arrays, float[] u, float[] v) {
            this.arrays = arrays;
            this.u = u;
            this.v = v;
        }
    }

    private static NativeImage canvas, glowCanvas;
    private static DynamicTexture canvasTex, glowCanvasTex;

    private GunTextureAtlas() {}

    // ---------------------------------------------------------------- api

    /**
     * Slot for a (base texture, colors) pair, baking it into the atlas on
     * first sight. Null = stay on the legacy per-texture path (config off,
     * missing resource, oversized, or every fitting band full).
     */
    public static @Nullable Slot acquire(ResourceLocation baseTex, @Nullable int[] colors) {
        if (!Config.gunAtlas) return null;
        boolean dyed = colors != null && colors.length >= 3
                && !(colors[0] == -1 && colors[1] == -1 && colors[2] == -1);
        TexKey key = dyed ? new TexKey(baseTex, colors[0], colors[1], colors[2])
                : new TexKey(baseTex, -1, -1, -1);
        Slot slot = SLOTS.get(key);
        if (slot != null) {
            if (dyed) VARIANTS.get(key); // LRU touch
            return slot;
        }
        if (NO_SLOT.contains(key)) return null;
        slot = bake(key, dyed);
        if (slot == null) {
            NO_SLOT.add(key);
            return null;
        }
        return slot;
    }

    /**
     * Points the model's quads at the slot (or back at the original texture
     * when slot == null). Returns false when the model can't live in the
     * atlas — caller must use the legacy path (UVs are left untouched).
     *
     * <p>Baked models are SHARED between consumers (GeckoLib's cache; the
     * module item renderer and the on-gun layers resolve the same geo
     * path to the same instance) and this class rewrites their UVs in
     * place. The rewrite persists after the draw — batching only needs
     * the UVs at vertex-emit time — so any consumer that binds the
     * ORIGINAL per-texture path instead of the atlas MUST consult the
     * registry before drawing, with the slot it intends to use:
     * typically {@code retarget(model, null)} first.
     */
    public static boolean retarget(BakedGeoModel model, @Nullable Slot slot) {
        ModelState st = STATES.get(model);
        if (st == null) {
            st = snapshot(model);
            STATES.put(model, st);
        }
        if (st.arrays == null) return false;
        if (st.current == slot) return true; // identity: slot instances are registry-stable
        rewrite(st, slot);
        st.current = slot;
        return true;
    }

    /** clears every registry and the canvases; called by the client reload listener */
    public static void invalidate() {
        SLOTS.clear();
        VARIANTS.clear();
        NO_SLOT.clear();
        for (Optional<NativeImage> img : DECODED.values()) img.ifPresent(NativeImage::close);
        DECODED.clear();
        for (Region r : REGIONS) r.reset();
        STATES.clear();
        if (canvas != null) {
            com.mojang.blaze3d.pipeline.RenderCall blank = () -> {
                if (canvas == null) return;
                fillTransparent(canvas);
                fillTransparent(glowCanvas);
                canvasTex.upload();
                glowCanvasTex.upload();
            };
            if (RenderSystem.isOnRenderThreadOrInit()) blank.execute();
            else RenderSystem.recordRenderCall(blank);
        }
    }

    // ------------------------------------------------------------- baking

    private static @Nullable Slot bake(TexKey key, boolean dyed) {
        NativeImage base = decode(key.tex());
        if (base == null) return null;
        int w = base.getWidth(), h = base.getHeight();
        if (Math.max(w, h) > MAX_SLOT) return null;

        int[] origin = null;
        int usedSize = 0;
        int need = Math.max(w, h);
        for (Region r : REGIONS) {
            if (r.size < need) continue;
            origin = r.take();
            if (origin != null) {
                usedSize = r.size;
                break;
            }
        }
        if (origin == null) return null;

        ensureCanvas();
        NativeImage pixels = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                pixels.setPixelRGBA(x, y, base.getPixelRGBA(x, y));

        if (dyed) {
            // recolor a copy — the decoded originals stay pristine
            NativeImage dyeMask = decode(DyedTextures.maskIdFor(key.tex()));
            if (dyeMask != null && (dyeMask.getWidth() != w || dyeMask.getHeight() != h)) dyeMask = null;
            DyedTextures.dyeInPlace(pixels, dyeMask, new int[]{key.c0(), key.c1(), key.c2()});
        }

        boolean glow = false;
        if (GunGlowLayer.hasGlowMask(key.tex())) {
            NativeImage glowMask = decode(GunGlowLayer.glowmaskIdFor(key.tex()));
            if (glowMask != null && glowMask.getWidth() == w && glowMask.getHeight() == h) {
                NativeImage glowImg = new NativeImage(w, h, true);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int mask = glowMask.getPixelRGBA(x, y);
                        if (mask == 0) continue; // GeckoLib skips fully-black mask pixels
                        int alpha = (mask >>> 24) & 0xFF;
                        int p = pixels.getPixelRGBA(x, y);
                        // ABGR: keeping the base channels in place, alpha swapped
                        // for the mask one (GeoGlowingTextureMeta.createImageMask)
                        glowImg.setPixelRGBA(x, y, alpha > 0 ? (alpha << 24) | (p & 0xFFFFFF) : p);
                        pixels.setPixelRGBA(x, y, 0); // hole the base where it glows
                    }
                }
                blit(glowCanvas, glowImg, origin[0], origin[1]);
                glow = true;
            }
        }

        blit(canvas, pixels, origin[0], origin[1]);
        canvasTex.upload();
        if (glow) glowCanvasTex.upload();

        Slot slot = new Slot(origin[0], origin[1], w, h, usedSize, glow);
        SLOTS.put(key, slot);
        if (dyed) {
            VARIANTS.put(key, slot);
            while (VARIANTS.size() > VARIANT_LIMIT) {
                Iterator<Map.Entry<TexKey, Slot>> it = VARIANTS.entrySet().iterator();
                Map.Entry<TexKey, Slot> eldest = it.next();
                it.remove();
                SLOTS.remove(eldest.getKey());
                regionFor(eldest.getValue().size()).release(eldest.getValue());
            }
        }
        return slot;
    }

    private static Region regionFor(int size) {
        for (Region r : REGIONS) if (r.size == size) return r;
        throw new IllegalStateException("no region of size " + size);
    }

    private static void ensureCanvas() {
        if (canvas != null) return;
        canvas = new NativeImage(CANVAS, CANVAS, true);
        glowCanvas = new NativeImage(CANVAS, CANVAS, true);
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        canvasTex = new DynamicTexture(canvas);
        glowCanvasTex = new DynamicTexture(glowCanvas);
        tm.register(ATLAS_ID, canvasTex);
        tm.register(GLOW_ATLAS_ID, glowCanvasTex);
    }

    private static void fillTransparent(NativeImage img) {
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                img.setPixelRGBA(x, y, 0);
    }

    private static void blit(NativeImage dst, NativeImage src, int x, int y) {
        for (int yy = 0; yy < src.getHeight(); yy++)
            for (int xx = 0; xx < src.getWidth(); xx++)
                dst.setPixelRGBA(x + xx, y + yy, src.getPixelRGBA(xx, yy));
    }

    /** decoded-PNG cache; Optional.empty marks missing (one failed probe) */
    private static @Nullable NativeImage decode(ResourceLocation id) {
        Optional<NativeImage> hit = DECODED.get(id);
        if (hit != null) return hit.orElse(null);
        NativeImage img = null;
        var res = Minecraft.getInstance().getResourceManager().getResource(id);
        if (res.isPresent()) {
            try (InputStream in = res.get().open()) {
                img = NativeImage.read(in);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("atlas decode failed for {}: {}", id, e.toString());
            }
        }
        DECODED.put(id, Optional.ofNullable(img));
        return img;
    }

    // ----------------------------------------------------------- uv state

    private static ModelState snapshot(BakedGeoModel model) {
        ArrayList<GeoVertex[]> arrays = new ArrayList<>();
        ArrayList<Float> us = new ArrayList<>(), vs = new ArrayList<>();
        boolean ok = walk(model, arrays, us, vs);
        if (!ok) return new ModelState(null, new float[0], new float[0]);
        float[] u = new float[us.size()], v = new float[vs.size()];
        for (int i = 0; i < u.length; i++) {
            u[i] = us.get(i);
            v[i] = vs.get(i);
        }
        return new ModelState(arrays.toArray(new GeoVertex[0][]), u, v);
    }

    /** collects every quad vertex array; false when any UV leaves [0,1] */
    private static boolean walk(BakedGeoModel model, ArrayList<GeoVertex[]> arrays,
                                ArrayList<Float> us, ArrayList<Float> vs) {
        for (GeoBone bone : model.topLevelBones())
            if (!walkBone(bone, arrays, us, vs)) return false;
        return true;
    }

    private static boolean walkBone(GeoBone bone, ArrayList<GeoVertex[]> arrays,
                                    ArrayList<Float> us, ArrayList<Float> vs) {
        for (GeoCube cube : bone.getCubes()) {
            for (GeoQuad quad : cube.quads()) {
                arrays.add(quad.vertices());
                for (GeoVertex vertex : quad.vertices()) {
                    float u = vertex.texU(), v = vertex.texV();
                    // wrap sampling (UV beyond the texture) cannot be emulated
                    // inside a slot — those models stay on the legacy path
                    if (u < -1e-4f || u > 1 + 1e-4f || v < -1e-4f || v > 1 + 1e-4f) return false;
                    us.add(u);
                    vs.add(v);
                }
            }
        }
        for (GeoBone child : bone.getChildBones())
            if (!walkBone(child, arrays, us, vs)) return false;
        return true;
    }

    private static void rewrite(ModelState st, @Nullable Slot slot) {
        int i = 0;
        for (GeoVertex[] array : st.arrays) {
            for (int j = 0; j < array.length; j++, i++) {
                float u = st.u[i], v = st.v[i];
                if (slot != null) {
                    u = (slot.x() + u * slot.w()) / CANVAS;
                    v = (slot.y() + v * slot.h()) / CANVAS;
                }
                array[j] = array[j].withUVs(u, v);
            }
        }
    }
}
