package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

import java.util.Map;

/**
 * The picture on the machine's monitor.
 *
 * <p>The part is drawn once into an offscreen target through an
 * orthographic camera — flat, evenly lit and free of the world's shading, fog
 * and depth, like an image on a panel rather than an object in the room — and
 * the plate then shows that texture as a single quad. The picture is re-rendered
 * only when the part itself changes, and both the picture and the quad are
 * skipped beyond {@link #MAX_VISIBLE_BLOCKS} blocks, so a screen nobody can read
 * costs nothing.
 *
 * <p>The picture is the part's own inventory icon: the item pass draws the
 * fitted 3/4 view, hides the laser beam bone and stands bottom-only attachments
 * upright. The ortho camera maps one unit to the same number of picture pixels
 * on both axes, so the icon keeps its shape on the plate; {@link #draw} maps the
 * quad's UVs onto that raster. The icon fills the plate's top three of four
 * pixels and is all the picture carries — the part's name is drawn into the
 * world instead, see {@link #drawName}.
 */
final class MachineScreenTexture {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** the screen is unreadable past this many blocks: skip its picture entirely */
    static final float MAX_VISIBLE_BLOCKS = 8f;

    /**
     * Picture resolution: the plate's own {@link WeaponModuleMachineRenderer#SCREEN_W_PX}
     * by {@link WeaponModuleMachineRenderer#SCREEN_H_PX} pixels at this many
     * picture pixels each. The icon is all the picture holds, so this is purely
     * how finely the part is sampled: the higher, the longer the icon stays
     * sharp while the player walks up to the plate.
     */
    private static final int SCALE = 32;
    private static final int W = (int) (WeaponModuleMachineRenderer.SCREEN_W_PX * SCALE);
    private static final int H = (int) (WeaponModuleMachineRenderer.SCREEN_H_PX * SCALE);
    /** the picture is lit flat: the world's light must not touch it */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** the plate is 4 model pixels tall: the icon gets the top 3, the name the bottom 1 */
    private static final float TEXT_BAND = 1f / 3f;
    /** a font pixel is a ninth of a plate pixel: the font's 9 px line fills the name band */
    private static final float FONT_PX_PER_PLATE_PX = 9f;
    /** names up to this many font pixels wide fit the plate; wider ones scroll */
    private static final int BAND_FONT_PX = Math.round(
            WeaponModuleMachineRenderer.SCREEN_W_PX * FONT_PX_PER_PLATE_PX);
    /** the name scrolls one font pixel every this many ticks when it does not fit */
    private static final float SCROLL_TICKS_PER_FONT_PX = 1f;
    /** blank font pixels between the scrolling name and its repeat */
    private static final int SCROLL_GAP_PX = 12;
    /** the name is drawn in this colour */
    private static final int NAME_COLOR = 0xFFD0D4D8;

    private final TextureTarget target = new TextureTarget(W, H, true, Minecraft.ON_OSX);
    /** our own quad batch: the shared Tesselator builder may be mid-batch in someone else's pass */
    private static final BufferBuilder QUAD = new BufferBuilder(256);
    /**
     * The name's own batch as well: it is flushed inside its scissor, so it can
     * neither be clipped late with the world's batch nor hold the world's
     * geometry hostage.
     */
    private static final OffscreenBuffer TEXT = new OffscreenBuffer();
    private ItemStack shown = ItemStack.EMPTY;
    private boolean failed;

    /** does the plate need a fresh picture for this part? */
    boolean stale(ItemStack part) {
        return failed || !ItemStack.matches(shown, part);
    }

    void destroy() {
        target.destroyBuffers();
    }

    /**
     * Draw the part into the picture: exactly its inventory icon — the item pass
     * owns the fitted 3/4 view, hides the laser beam bone and stands bottom-only
     * attachments upright — in the plate's top three pixels, then put every bit
     * of render state this pass touched back the way the world renderer left it.
     */
    void render(WeaponModuleMachineRenderer.Workpiece wp) {
        Minecraft mc = Minecraft.getInstance();
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        float previousFogStart = RenderSystem.getShaderFogStart();
        float previousFogEnd = RenderSystem.getShaderFogEnd();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        MultiBufferSource.BufferSource offscreen = new OffscreenBuffer();
        boolean pushed = false;
        try {
            // The target carries its own clear colour and its clear() resets the
            // GL state to that, so the frame's colour (whatever the world
            // cleared last) never reaches it — the default is white. Cleared to
            // nothing at all: the icon is the whole picture, and the plate it is
            // shown on is the machine's own panel, so the quad lets it through.
            target.setClearColor(0f, 0f, 0f, 0f);
            // clear() binds the target only for the clear itself and unbinds
            // again, so the pass has to bind it before anything is drawn into it
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            // the ortho maps the picture's width to the plate's width and its
            // height to the plate's height, so one unit is the same number of
            // plate pixels on both axes and the icon keeps its shape
            float halfHeight = 0.5f * WeaponModuleMachineRenderer.SCREEN_H_PX
                    / WeaponModuleMachineRenderer.SCREEN_W_PX;
            RenderSystem.setProjectionMatrix(new Matrix4f()
                            .setOrtho(-0.5f, 0.5f, -halfHeight, halfHeight, -10f, 10f),
                    VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.getModelViewStack().pushPose();
            pushed = true;
            RenderSystem.getModelViewStack().setIdentity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderFogStart(1.0e6f);
            RenderSystem.setShaderFogEnd(2.0e6f);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

            // exactly the inventory icon: the item pass owns the 3/4 view, the
            // fit, the hidden laser beam bone and the upright bottom-only
            // attachments, so the monitor shows the part the player knows from
            // the slot. The pass assumes vanilla's outer translate(-0.5), which
            // here also puts the icon's centre on the ortho camera's origin —
            // then the icon is lifted half a model pixel so it sits in the top
            // three of the plate's four, above the name band.
            PoseStack icon = new PoseStack();
            icon.translate(0f, TEXT_BAND * 0.5f, 0f);
            icon.translate(-0.5f, -0.5f, -0.5f);
            ModuleDraw.drawIcon(wp.stack(), icon, offscreen, FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            offscreen.endBatch();
            shown = wp.stack().copy();
            failed = false;
        } catch (Throwable t) {
            // a broken picture must not take the machine's render down with it
            if (!failed) LOGGER.warn("Machine tool monitor picture failed to render", t);
            failed = true;
        } finally {
            // nothing may be left pending: a batch that outlives the pass would
            // be flushed into the world later, with the part floating in it
            try {
                offscreen.endBatch();
            } catch (Throwable ignored) {
                // the leftovers die with the pass
            }
            target.unbindWrite();
            mc.getMainRenderTarget().bindWrite(true);
            if (pushed) {
                RenderSystem.getModelViewStack().popPose();
                RenderSystem.applyModelViewMatrix();
            }
            RenderSystem.setProjectionMatrix(previousProjection, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderFogStart(previousFogStart);
            RenderSystem.setShaderFogEnd(previousFogEnd);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            restoreLevelLighting(mc);
        }
    }

    /**
     * GeckoLib's GUI item branch - the icon pass this picture is drawn with -
     * ends by setting the GUI item lighting for the GUI batch that follows it
     * there, and nothing puts the world's lighting back. In the world that
     * leaves every draw after this pass in the same frame - the block entity
     * batches the level flushes after its block entity pass, the held item and
     * the hand - lit like a GUI item on exactly the frames this pass runs, which
     * reads as flicker. The level sets its lighting once per frame, as the same
     * two directions rotated by the view rotation, so rebuilding that from the
     * inverse view rotation puts back exactly what the frame started with.
     */
    private static void restoreLevelLighting(Minecraft mc) {
        Matrix4f viewRotation = new Matrix4f(RenderSystem.getInverseViewRotationMatrix()).invert();
        if (mc.level != null && mc.level.effects().constantAmbientLight()) {
            Lighting.setupNetherLevel(viewRotation);
        } else {
            Lighting.setupLevel(viewRotation);
        }
    }

    /**
     * The part's name along the plate's bottom pixel, drawn in the world.
     *
     * <p>Not into the picture: a font pixel is a ninth of a plate pixel, so a
     * baked name is nine picture pixels per glyph and turns to mush the moment
     * the player walks up to the plate. Through the world's own text pipeline the
     * glyphs are rasterised at the screen's resolution instead and stay sharp at
     * any distance, the way the weapon workbench's own labels are.
     *
     * <p>A name wider than the plate scrolls right to left, wrapping through
     * {@link #SCROLL_GAP_PX} blank pixels; the offset is a function of the world
     * tick, so every client agrees. The band is the plate's bottom pixel: the
     * text is drawn in that pixel's own plane and clipped to the band's
     * rectangle projected onto the screen, so it can neither climb over the icon
     * nor leave the plate sideways — and the batch is flushed inside the
     * scissor, because a clip applies where a batch is drawn, not where it is
     * filled.
     */
    void drawName(PoseStack poseStack, CoreGeoBone screen, ItemStack part, float now) {
        Font font = Minecraft.getInstance().font;
        Component name = part.getHoverName();
        int nameWidth = font.width(name);
        boolean scrolling = nameWidth > BAND_FONT_PX;
        float offset = scrolling
                ? now / SCROLL_TICKS_PER_FONT_PX % (float) (nameWidth + SCROLL_GAP_PX)
                : (BAND_FONT_PX - nameWidth) * 0.5f;

        // the band: the plate's left edge (the picture's u = 0) and the top of
        // its bottom pixel, in the block's own frame
        float x = (screen.getPivotX() - WeaponModuleMachineRenderer.SCREEN_LIFT_PX) / 16f;
        float yTop = (screen.getPivotY() - WeaponModuleMachineRenderer.SCREEN_H_PX * 0.5f + 1f) / 16f;
        float zLeft = (screen.getPivotZ() - WeaponModuleMachineRenderer.SCREEN_W_PX * 0.5f) / 16f;
        // a font pixel is a ninth of a plate pixel, and the band is one plate
        // pixel tall: the font's own 9 px line fills it exactly
        float fontPx = 1f / (16f * FONT_PX_PER_PLATE_PX);
        Matrix4f matrix = new Matrix4f(poseStack.last().pose())
                .translate(x, yTop, zLeft)
                // the plate faces pose -X: this lands the text's own frame — +x
                // to the viewer's right, +z out of the face — on the plate
                .rotateY(-(float) Math.PI * 0.5f)
                // the font lays out for a y-down frame
                .scale(fontPx, -fontPx, fontPx);

        float zRight = (screen.getPivotZ() + WeaponModuleMachineRenderer.SCREEN_W_PX * 0.5f) / 16f;
        if (!clipToBand(poseStack, x, yTop - 1f / 16f, yTop, zLeft, zRight)) return;
        try {
            font.drawInBatch(name, -offset, 0f, NAME_COLOR, false, matrix, TEXT,
                    Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
            // the repeat is only worth drawing while its own start is still on the plate
            if (scrolling && offset > nameWidth + SCROLL_GAP_PX - BAND_FONT_PX) {
                font.drawInBatch(name, -offset + nameWidth + SCROLL_GAP_PX, 0f, NAME_COLOR, false,
                        matrix, TEXT, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
            }
        } finally {
            // nothing may be left pending: a batch that outlives the pass would
            // be flushed into the world later, with the name floating in it
            try {
                TEXT.endBatch();
            } catch (Throwable ignored) {
                // the leftovers die with the pass
            }
            RenderSystem.disableScissor();
        }
    }

    /**
     * Clip to the band's own rectangle, projected onto the screen through the
     * same matrices the glyph vertices go through — the plate is
     * {@link WeaponModuleMachineRenderer#SCREEN_W_PX} model pixels wide, so its
     * band is a strip a third of a model pixel tall and nothing about it can be
     * guessed from the bone alone. Returns false when the plate is off screen or
     * behind the camera, in which case there is nothing to draw at all; leaves
     * the scissor on when it returns true.
     */
    private static boolean clipToBand(PoseStack poseStack, float x, float yBottom, float yTop,
                                      float zLeft, float zRight) {
        Matrix4f clip = new Matrix4f(RenderSystem.getProjectionMatrix())
                .mul(RenderSystem.getModelViewMatrix())
                .mul(poseStack.last().pose());
        Window window = Minecraft.getInstance().getWindow();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float cy = (i & 1) == 0 ? yBottom : yTop;
            float cz = (i & 2) == 0 ? zLeft : zRight;
            Vector4f corner = new Vector4f(x, cy, cz, 1f).mul(clip);
            // behind the camera the projection says nothing: there is no band on screen
            if (corner.w <= 1.0e-4f) return false;
            float sx = (corner.x / corner.w * 0.5f + 0.5f) * window.getWidth();
            float sy = (corner.y / corner.w * 0.5f + 0.5f) * window.getHeight();
            minX = Math.min(minX, sx);
            maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy);
            maxY = Math.max(maxY, sy);
        }
        // the scissor box counts from the window's bottom left, like the NDC the projection just produced
        int x0 = Math.max((int) Math.floor(minX), 0);
        int y0 = Math.max((int) Math.floor(minY), 0);
        int x1 = Math.min((int) Math.ceil(maxX), window.getWidth());
        int y1 = Math.min((int) Math.ceil(maxY), window.getHeight());
        if (x1 <= x0 || y1 <= y0) return false;
        RenderSystem.enableScissor(x0, y0, x1 - x0, y1 - y0);
        return true;
    }

    /**
     * Show the picture on the plate: one quad across the plate's own
     * {@link WeaponModuleMachineRenderer#SCREEN_W_PX} by
     * {@link WeaponModuleMachineRenderer#SCREEN_H_PX} px face, just in front of
     * the drawn panel, unlit and blended so it reads the same in the dark as in
     * daylight.
     *
     * <p>UV mapping: the raster's top-left is the icon's top-left, and the plate
     * is seen from its own -X side, so {@code u} runs along model +z (the
     * viewer's right) and {@code v} along model +y (the viewer's up).
     */
    void draw(PoseStack poseStack, CoreGeoBone screen) {
        // the baked bone pivot is ALREADY mirrored (BakedModelFactory negates
        // pivot x at bake time), so the plate the model draws faces pose -X and
        // the quad belongs on that same side, lifted a little further out of the
        // panel: subtracting the lift pushes it along -X, away from the plate
        float x = (screen.getPivotX() - WeaponModuleMachineRenderer.SCREEN_LIFT_PX) / 16f;
        float y = screen.getPivotY() / 16f;
        float z = screen.getPivotZ() / 16f;
        float halfW = WeaponModuleMachineRenderer.SCREEN_W_PX / 32f;
        float halfH = WeaponModuleMachineRenderer.SCREEN_H_PX / 32f;
        Matrix4f matrix = poseStack.last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        // the picture is part of the world: it must be hidden by the machine's
        // own body and by walls like anything else. This quad is drawn with raw
        // render state instead of a RenderType, so the depth test has to be set
        // here — whatever the pass before it left behind (the offscreen picture
        // pass among them) must not decide whether the monitor shows through.
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        try {
            QUAD.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            // the raster's own axes on the plate: u (the picture's left..right)
            // runs along model +z — the viewer's right, since the plate faces
            // pose -X — and v (the picture's bottom..top, as the target's texture
            // has it) along model +y, so the icon lands the way the GUI shows it
            vertex(matrix, x, y + halfH, z - halfW, 0f, 1f);
            vertex(matrix, x, y + halfH, z + halfW, 1f, 1f);
            vertex(matrix, x, y - halfH, z + halfW, 1f, 0f);
            vertex(matrix, x, y - halfH, z - halfW, 0f, 0f);
            BufferUploader.drawWithShader(QUAD.end());
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static void vertex(Matrix4f matrix, float x, float y, float z, float u, float v) {
        QUAD.vertex(matrix, x, y, z).uv(u, v).endVertex();
    }

    /**
     * A batch of our own, so the offscreen pass can be flushed without dumping
     * whatever other geometry the world's shared buffer source is holding into
     * the picture (or losing it from the world).
     */
    private static final class OffscreenBuffer extends MultiBufferSource.BufferSource {
        private OffscreenBuffer() {
            super(Tesselator.getInstance().getBuilder(), Map.of());
        }
    }
}
