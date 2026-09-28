package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.item.PodItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.BakedModelWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pod icons show what is sealed inside them. The content item's icon is drawn
 * as a layer BEHIND the pod's own face: the pod art leaves a see-through slot,
 * the pod's opaque texels crop the icon to it, so the content reads as being
 * inside the capsule instead of pasted on top of it.
 *
 * <p>Layered through Forge's render-pass hook rather than the GUI-only
 * {@code IItemDecorator} Create's potato cannon uses, so the content also shows
 * on the in-hand, dropped and JEI copies of the item rather than only in the
 * inventory slot.
 */
public final class PodItemModel extends BakedModelWrapper<BakedModel> {

    /** The baked item models to layer: the two pod icons and nothing else. */
    private static final Set<ResourceLocation> TARGETS = Set.of(
            new ResourceLocation(CreatePneumaticTacticals.MODID, "pod"),
            new ResourceLocation(CreatePneumaticTacticals.MODID, "pressurized_pod"));

    /** One layer per content item; touched only while rendering. */
    private static final Map<Item, BakedModel> LAYERS = new ConcurrentHashMap<>();

    private PodItemModel(BakedModel original) {
        super(original);
    }

    /**
     * Replaces the baked pod icons with layered versions. Called from
     * {@code ModelEvent.ModifyBakingResult}, on the model-baking worker thread.
     */
    public static void wrap(Map<ResourceLocation, BakedModel> models) {
        for (ResourceLocation target : TARGETS) {
            ModelResourceLocation inventory = new ModelResourceLocation(target, "inventory");
            BakedModel baked = models.get(inventory);
            if (baked != null && !(baked instanceof PodItemModel)) {
                models.put(inventory, new PodItemModel(baked));
            }
        }
    }

    /** Drops the cached layers: their sprites belong to the unloaded atlas. */
    public static void invalidateLayers() {
        LAYERS.clear();
    }

    /**
     * The pod's own face plus, when the stack carries content, the content icon
     * behind it.
     */
    @Override
    public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
        BakedModel layer = contentLayer(stack);
        return layer == null ? List.of(originalModel) : List.of(originalModel, layer);
    }

    /**
     * {@code BakedModelWrapper} hands back the model it wraps, and
     * {@code ForgeHooksClient.handleCameraTransforms} replaces the model it was
     * given with that return value — which would drop the layered passes before
     * they ever render. Run the transform (it only moves the pose) and stay.
     */
    @Override
    public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack,
                                     boolean applyLeftHandTransform) {
        originalModel.applyTransform(context, poseStack, applyLeftHandTransform);
        return this;
    }

    @Nullable
    private static BakedModel contentLayer(ItemStack stack) {
        if (!(stack.getItem() instanceof PodItem)) return null;
        Item content = PodItem.contentItem(stack);
        // a pod inside a pod would recurse the model lookup below
        if (content == null || content instanceof PodItem) return null;
        BakedModel cached = LAYERS.get(content);
        if (cached != null) return cached;
        BakedModel layer = bakeLayer(content);
        if (layer != null) LAYERS.put(content, layer);
        return layer;
    }

    @Nullable
    private static BakedModel bakeLayer(Item content) {
        ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
        if (itemRenderer == null) return null; // no renderer yet (early JEI/model pass)
        // The content item's own inventory model: its particle icon IS the item
        // icon (layer0 for a flat item, the block's particle sprite for a block
        // item) — same source the ammo box's content plate draws from.
        BakedModel model = itemRenderer.getModel(new ItemStack(content), null, null, 0);
        return model == null ? null : new ContentLayer(model, model.getParticleIcon());
    }

    /**
     * The content icon: a flat quad pair the width of the pod art's slot,
     * sharing the pod's own model space (the 0..1 box the item display
     * transform expects, y up).
     */
    private static final class ContentLayer extends BakedModelWrapper<BakedModel> {

        /** The pod sprite's see-through slot, in sprite pixels (y grows down). */
        private static final float SLOT_MIN_X = 5f;
        private static final float SLOT_MAX_X = 11f;
        private static final float SLOT_MIN_Y = 5f;
        private static final float SLOT_MAX_Y = 10f;

        /** sprite pixels -> item model units ({@code FaceBakery} bakes quads in 0..1) */
        private static final float PX = 1f / 16f;

        /** the icon covers the slot's longer side, centred on the slot */
        private static final float SIDE = Math.max(SLOT_MAX_X - SLOT_MIN_X, SLOT_MAX_Y - SLOT_MIN_Y) * PX;
        private static final float CENTER_X = (SLOT_MIN_X + SLOT_MAX_X) / 2f * PX;
        /** sprite y runs down, model y up */
        private static final float CENTER_Y = (16f - (SLOT_MIN_Y + SLOT_MAX_Y) / 2f) * PX;

        /**
         * Either side of the pod's mid-slab: the pod's own faces sit at z=7.5/16
         * (facing -z) and z=8.5/16 (facing +z), so the layer stays inside the
         * capsule, and the depth test picks the copy facing the viewer.
         */
        private static final float FRONT_Z = 8.02f * PX;
        private static final float BACK_Z = 7.98f * PX;

        /** byte-packed (x,y,z) normals; putBulkData divides the bytes by 127 */
        private static final int NORMAL_POS_Z = 127 << 16;
        private static final int NORMAL_NEG_Z = (-127 & 0xFF) << 16;

        private final List<BakedQuad> quads;

        ContentLayer(BakedModel content, TextureAtlasSprite sprite) {
            super(content);
            float x0 = CENTER_X - SIDE / 2f;
            float x1 = CENTER_X + SIDE / 2f;
            float y0 = CENTER_Y - SIDE / 2f;
            float y1 = CENTER_Y + SIDE / 2f;
            float u0 = sprite.getU0();
            float u1 = sprite.getU1();
            float v0 = sprite.getV0();
            float v1 = sprite.getV1();
            // front: top-left first, counter-clockwise seen from +z
            int[] front = new int[32];
            vertex(front, 0, x0, y1, FRONT_Z, u0, v0, NORMAL_POS_Z);
            vertex(front, 1, x0, y0, FRONT_Z, u0, v1, NORMAL_POS_Z);
            vertex(front, 2, x1, y0, FRONT_Z, u1, v1, NORMAL_POS_Z);
            vertex(front, 3, x1, y1, FRONT_Z, u1, v0, NORMAL_POS_Z);
            // back: same picture read the other way round (vanilla item models
            // mirror the back face's u rather than showing it flipped)
            int[] back = new int[32];
            vertex(back, 0, x1, y1, BACK_Z, u0, v0, NORMAL_NEG_Z);
            vertex(back, 1, x1, y0, BACK_Z, u0, v1, NORMAL_NEG_Z);
            vertex(back, 2, x0, y0, BACK_Z, u1, v1, NORMAL_NEG_Z);
            vertex(back, 3, x0, y1, BACK_Z, u1, v0, NORMAL_NEG_Z);
            this.quads = List.of(
                    new BakedQuad(front, -1, Direction.SOUTH, sprite, true),
                    new BakedQuad(back, -1, Direction.NORTH, sprite, true));
        }

        /**
         * One vertex in the baked-quad layout: position, tint, uv, light and
         * normal. Light stays 0 — {@code putBulkData} takes the brighter of it
         * and the light the item is rendered with.
         */
        private static void vertex(int[] data, int index, float x, float y, float z,
                                   float u, float v, int normal) {
            int i = index * 8;
            data[i] = Float.floatToRawIntBits(x);
            data[i + 1] = Float.floatToRawIntBits(y);
            data[i + 2] = Float.floatToRawIntBits(z);
            data[i + 3] = -1; // white: the icon keeps its own colours
            data[i + 4] = Float.floatToRawIntBits(u);
            data[i + 5] = Float.floatToRawIntBits(v);
            data[i + 6] = 0;
            data[i + 7] = normal;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                        RandomSource random) {
            // item models expose everything in the unculled bucket
            return side == null ? quads : List.of();
        }
    }
}
