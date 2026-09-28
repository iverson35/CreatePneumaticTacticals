package dev.ignis.createpneumatictacticals.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ignis.createpneumatictacticals.Config;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.module.FeedType;
import dev.ignis.createpneumatictacticals.module.FireMode;
import dev.ignis.createpneumatictacticals.module.SupplyType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Gun HUD (plan_v2): dynamic hipfire crosshair whose arm gap tracks the
 * current spread (pose penalty + bloom), an ammo widget (fire mode, ammo
 * type, clip / reserve, internal-tank air), and suppression of the vanilla
 * crosshair while a gun is held.
 *
 * <p>Spread degrees are mapped to pixels through the vertical FOV so the
 * crosshair gap matches the true angular dispersion at any zoom level.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class GunHudOverlay implements IGuiOverlay {

    private static long hitmarkerUntil = 0;

    /** called (client) when a fired projectile hits a living entity */
    public static void showHitmarker() {
        if (Config.hitmarkerEnabled) hitmarkerUntil = System.currentTimeMillis() + 300;
    }

    public static final GunHudOverlay INSTANCE = new GunHudOverlay();

    private static final int CROSSHAIR_COLOR = 0xCCFFFFFF;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int WARN_COLOR = 0xFF5555;
    private static final int ARM_LEN = 5;
    private static final int MIN_GAP = 2;
    private static final int MAX_GAP = 80;
    /** overlay registration (mod bus) lives in {@link MenuScreenBinding} */
    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("gun_hud", INSTANCE);
    }

    /** hide the vanilla crosshair while holding a gun */
    @SubscribeEvent
    public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay().id().equals(VanillaGuiOverlay.CROSSHAIR.id()) && heldGun() != null) {
            event.setCanceled(true);
        }
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) return;

        ItemStack gun = heldGun();
        if (gun == null) return;
        GunStats stats = GunStats.ofGun(gun);

        if (Config.gunCrosshairEnabled && mc.screen == null) {
            renderCrosshair(g, player, gun, stats, width, height);
        }
        if (System.currentTimeMillis() < hitmarkerUntil) {
            drawHitmarker(g, width / 2, height / 2);
        }
        if (Config.gunHudEnabled) {
            renderAmmoWidget(g, mc, player, gun, stats, width, height);
        }
    }

    // --- crosshair ---

    private void renderCrosshair(GuiGraphics g, LocalPlayer player, ItemStack gun, GunStats stats,
                                 int width, int height) {
        String ammoId = GunNbt.getAmmo(gun);
        AmmoExtension ext = AmmoExtension.get(ammoId == null ? "" : ammoId);
        double spreadDeg = SpreadModel.currentSpread(player, gun, ext, stats.hipfireAccuracyMultiplier);

        float p = AimHandler.aimProgress(Minecraft.getInstance().getFrameTime());
        p = p * p * (3f - 2f * p); // same easing as the FOV transition

        int cx = width / 2;
        int cy = height / 2;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // fully aimed: arms collapse into the dot
        double gapScale = 1.0 - p;
        if (gapScale > 0.02) {
            int gap = net.minecraft.util.Mth.clamp((int) Math.round(spreadToPixels(spreadDeg, height) * gapScale) + MIN_GAP,
                    MIN_GAP, MAX_GAP);
            // four arms
            g.fill(cx - gap - ARM_LEN, cy, cx - gap, cy + 1, CROSSHAIR_COLOR); // left
            g.fill(cx + gap + 1, cy, cx + gap + 1 + ARM_LEN, cy + 1, CROSSHAIR_COLOR); // right
            g.fill(cx, cy - gap - ARM_LEN, cx + 1, cy - gap, CROSSHAIR_COLOR); // top
            g.fill(cx, cy + gap + 1, cx + 1, cy + gap + 1 + ARM_LEN, CROSSHAIR_COLOR); // bottom
        }
        if (p < 1f) g.fill(cx, cy, cx + 1, cy + 1, CROSSHAIR_COLOR); // center dot; hidden when fully aimed
        RenderSystem.disableBlend();
    }

    /** angular spread (deg) -> screen pixels via the current vertical FOV */
    private static double spreadToPixels(double spreadDeg, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        double fovDeg = mc.options.fov().get() * AimHandler.aimViewScale();
        double focalPx = (screenHeight / 2.0) / Math.tan(Math.toRadians(fovDeg) / 2.0);
        return focalPx * Math.tan(Math.toRadians(spreadDeg));
    }

    // --- ammo widget ---

    private static final ResourceLocation FIRE_SEMI =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "textures/gui/fire_mode/semi.png");
    private static final ResourceLocation FIRE_AUTO =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "textures/gui/fire_mode/auto.png");
    private static final ResourceLocation FIRE_BURST =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "textures/gui/fire_mode/burst.png");
    private static final int AMMO_ICON_SIZE = 16;
    /** reserve-flash window after a dry R press, in wall-clock ms */
    private static final long RESERVE_FLASH_MS = 1200;
    private static long reserveFlashUntilMs = 0;

    /** Dry R press with no rounds anywhere: blink the reserve count red. */
    public static void flashReserve() {
        reserveFlashUntilMs = System.currentTimeMillis() + RESERVE_FLASH_MS;
    }

    /**
     * COD-style ammo block: the clip number large on top, the fire-mode
     * glyph right-aligned under the number's right edge, and the reserve
     * count right-aligned against the glyph's left edge; a vertical divider,
     * and the loaded ammo's item icon right of the divider. The internal-tank
     * air readout stays a text line above the block.
     */
    private void renderAmmoWidget(GuiGraphics g, Minecraft mc, LocalPlayer player, ItemStack gun,
                                  GunStats stats, int width, int height) {
        String ammoId = GunNbt.getAmmo(gun);
        boolean backpack = stats.feed != null && stats.feed.feedType == FeedType.BACKPACK;

        // the whole block (icon included) keeps the old corner anchor
        int anchorRight = width - 8 + Config.gunHudOffsetX;
        int blockBottom = height - 8 + Config.gunHudOffsetY;

        int clip = GunNbt.getAmmoCount(gun);
        // the deferred window (fire animation still blending after the last
        // shot) counts too, or the readout would flash a red 0 between the
        // trigger and the reload actually starting
        boolean reloading = ClientGunInput.isReloading() || ClientGunInput.isReloadPending();
        // creative: reserve is bottomless, show the infinity sign
        String reserveText = player.isCreative() ? "∞" : String.valueOf(reserveCount(player, stats, ammoId));
        // during a reload the magazine counts as out: show 0 (no localized
        // "reloading" word — it fits the block badly). Round feeds keep the
        // optimistic count, which grows batch by batch.
        int displayClip = (reloading && !ClientGunInput.isReloadingRoundMode()) ? 0 : clip;
        String clipText = backpack ? "∞" : String.valueOf(displayClip);
        int clipColor = (!backpack && clip == 0 && !reloading) ? WARN_COLOR : TEXT_COLOR;

        // 8px reserve row at the bottom, a 2x clip row above it, a 1px divider
        // spanning both rows, and a 16px ammo icon right of the divider
        int reserveTop = blockBottom - 8;
        int clipTop = reserveTop - 17;
        int dividerX = anchorRight - AMMO_ICON_SIZE - 4;
        int numbersRight = dividerX - 3;

        g.pose().pushPose();
        g.pose().scale(2f, 2f, 1f);
        g.drawString(mc.font, clipText,
                numbersRight / 2f - mc.font.width(clipText),
                clipTop / 2f, clipColor, true);
        g.pose().popPose();

        // reserve row: the fire glyph is right-aligned with the big clip
        // number (its right edge sits under the number's right edge), and the
        // reserve count is right-aligned against the glyph's left edge —
        // anchoring the glyph to the digits made it slide as the count
        // changed width
        int reserveW = mc.font.width(reserveText);
        int glyphX = numbersRight - 8;
        FireMode mode = GunNbt.getFireMode(gun);
        ResourceLocation glyph = switch (mode == null ? FireMode.SEMI : mode) {
            case SEMI -> FIRE_SEMI;
            case AUTO -> FIRE_AUTO;
            case BURST -> FIRE_BURST;
        };
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(glyph, glyphX, reserveTop, 8, 8, 0f, 0f, 16, 16, 16, 16);
        // dry-R flash: ~4 red blinks over the window, then back to white
        boolean flashing = System.currentTimeMillis() < reserveFlashUntilMs;
        int reserveColor = flashing && (System.currentTimeMillis() / 150) % 2 == 0
                ? WARN_COLOR : TEXT_COLOR;
        g.drawString(mc.font, reserveText, glyphX - 2 - reserveW, reserveTop, reserveColor, true);

        g.fill(dividerX, clipTop, dividerX + 1, blockBottom, CROSSHAIR_COLOR);

        ItemStack ammo = ammoStack(mc, ammoId);
        if (!ammo.isEmpty()) {
            g.renderFakeItem(ammo, anchorRight - AMMO_ICON_SIZE,
                    clipTop + (blockBottom - clipTop - AMMO_ICON_SIZE) / 2);
        }

        // air pressure above the block (internal-tank guns only)
        if (stats.supply != null && stats.supply.supplyType == SupplyType.INTERNAL_TANK) {
            int max = gun.getMaxDamage();
            int air = max > 0 ? max - gun.getDamageValue() : 0;
            int pct = max > 0 ? Math.round(100f * air / max) : 0;
            int color = pct <= 20 ? WARN_COLOR : TEXT_COLOR;
            drawRightAligned(g, mc, Component.translatable(
                    "gui." + CreatePneumaticTacticals.MODID + ".hud.air", pct).getString(),
                    anchorRight, clipTop - 9, color);
        }
    }

    private static ItemStack ammoStack(Minecraft mc, String ammoId) {
        if (ammoId == null || ammoId.isEmpty() || mc.level == null) return ItemStack.EMPTY;
        net.minecraft.world.item.Item item = AmmoExtension.contentItemFor(mc.level.registryAccess(), ammoId);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static int reserveCount(LocalPlayer player, GunStats stats, String ammoId) {
        if (ammoId == null || ammoId.isEmpty() || player.isCreative()) return 0;
        boolean cartridge = stats.supply != null && stats.supply.supplyType == SupplyType.CARTRIDGE;
        return ClientGunInput.countMatchingPods(player, cartridge, ammoId);
    }

    private static void drawRightAligned(GuiGraphics g, Minecraft mc, String text, int rightX, int y, int color) {
        g.drawString(mc.font, text, rightX - mc.font.width(text), y, color, true);
    }

    private static final ResourceLocation HIT_MARKER =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "textures/gui/hit_marker.png");

    /** four diagonal ticks in a 16x16 texture (own alpha); drawn 1:1, centered */
    private static void drawHitmarker(GuiGraphics g, int x, int y) {
        // the plain blit path does not enable blending itself; the texture
        // is semi-transparent, so blend is required (same pattern as the
        // crosshair fills above)
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(HIT_MARKER, x - 8, y - 8, 0f, 0f, 16, 16, 16, 16);
    }

    /** the held gun, or null */
    private static ItemStack heldGun() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        ItemStack stack = player.getMainHandItem();
        return stack.getItem() instanceof GeoGunItem ? stack : null;
    }

}
