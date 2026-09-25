package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * ImmediatelyFast compatibility for GeckoLib's GUI item path.
 *
 * <p>Root cause of the "hotbar icons turn translucent in modpacks" bug
 * (RIAFst-rc3): GeckoLib's {@code GeoItemRenderer.renderByItem} GUI branch
 * ends with {@code defaultBufferSource.endBatch()} plus
 * {@code RenderSystem.enableDepthTest()} and {@code Lighting.setupFor3DItems()}.
 * Under ImmediatelyFast HUD batching the incoming bufferSource is a
 * {@code BatchableBufferSource} that ACCUMULATES every hotbar icon without
 * drawing (only the final endHudBatching draws, with full GL state save/
 * restore), so that mid-batch endBatch() flushes early: the icons queued
 * so far — this slot plus every slot to its left — are drawn while depth
 * test is active against the world's leftover depth buffer, so near world
 * geometry rejects their pixels (reads as translucency), and the depth/
 * lighting flips leak state into the still-queued icons. Containers and
 * backpack screens are unaffected because IF's screen batching is off
 * there, so they run the vanilla mid-flush path harmlessly.
 *
 * <p>Fix: when IF HUD batching is active, replicate GeckoLib's GUI render
 * but leave the vertices queued and skip the three state flips. Without
 * IF (dev runtime, vanilla), fall through to GeckoLib's own behavior.
 */
public final class GeoGuiBatchCompat {

    private static java.lang.reflect.Method isHudBatching;

    static {
        try {
            isHudBatching = Class.forName(
                            "net.raphimc.immediatelyfast.feature.batching.BatchingBuffers")
                    .getDeclaredMethod("isHudBatching");
        } catch (ReflectiveOperationException e) {
            isHudBatching = null; // ImmediatelyFast not installed — vanilla path
        }
    }

    private GeoGuiBatchCompat() {}

    /** true while ImmediatelyFast's HUD batch is collecting vertices */
    public static boolean isHudBatching() {
        if (isHudBatching == null) return false;
        try {
            return (Boolean) isHudBatching.invoke(null);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * GeckoLib's {@code renderInGui} body, minus the mid-batch endBatch and
     * the depth/lighting flips that corrupt ImmediatelyFast's HUD batch.
     * Callers only invoke this while {@link #isHudBatching()} is true.
     */
    public static <T extends net.minecraft.world.item.Item & GeoAnimatable> void renderQueued(
            GeoItemRenderer<T> renderer, T animatable, ItemStack stack,
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        renderer.setupLightingForGuiRender();
        MultiBufferSource.BufferSource defaultBufferSource =
                bufferSource instanceof MultiBufferSource.BufferSource bs ? bs
                        : Minecraft.getInstance().renderBuffers().bufferSource();
        float partialTick = Minecraft.getInstance().getFrameTime();
        RenderType renderType = renderer.getRenderType(animatable,
                renderer.getTextureLocation(animatable), defaultBufferSource, partialTick);
        VertexConsumer buffer = ItemRenderer.getFoilBufferDirect(
                bufferSource, renderType, true, stack.hasFoil());
        poseStack.pushPose();
        renderer.defaultRender(poseStack, animatable, defaultBufferSource,
                renderType, buffer, 0.0f, partialTick, packedLight);
        poseStack.popPose();
    }
}