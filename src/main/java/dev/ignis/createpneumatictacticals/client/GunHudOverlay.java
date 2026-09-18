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
        g.fill(cx, cy, cx + 1, cy + 1, CROSSHAIR_COLOR); // center dot
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

    private void renderAmmoWidget(GuiGraphics g, Minecraft mc, LocalPlayer player, ItemStack gun,
                                  GunStats stats, int width, int height) {
        int x = width - 8 + Config.gunHudOffsetX;
        int y = height - 8 + Config.gunHudOffsetY;

        String ammoId = GunNbt.getAmmo(gun);
        boolean selected = ammoId != null && !ammoId.isEmpty();
        boolean backpack = stats.feed != null && stats.feed.feedType == FeedType.BACKPACK;

        // line 1 (bottom): clip / reserve
        String countText;
        int countColor = TEXT_COLOR;
        if (backpack) {
            int reserve = reserveCount(player, stats, ammoId);
            countText = "∞ " + reserve; // backpack feed has no clip
        } else {
            int clip = GunNbt.getAmmoCount(gun);
            int reserve = reserveCount(player, stats, ammoId);
            countText = clip + " / " + reserve;
            if (clip == 0) countColor = WARN_COLOR;
        }
        drawRightAligned(g, mc, countText, x, y - 9, countColor);

        // line 2: fire mode + ammo name
        FireMode mode = GunNbt.getFireMode(gun);
        Component modeText = Component.translatable(
                "fire_mode." + CreatePneumaticTacticals.MODID + "." + (mode == null ? "semi" : mode.getSerializedName()));
        Component ammoName = selected
                ? GunItem.ammoDisplayName(gun, player.level(), ammoId)
                : Component.translatable("gui." + CreatePneumaticTacticals.MODID + ".hud.no_ammo");
        drawRightAligned(g, mc, modeText.getString() + " · " + ammoName.getString(), x, y - 19, TEXT_COLOR);

        // line 3 (top): internal tank air pressure
        if (stats.supply != null && stats.supply.supplyType == SupplyType.INTERNAL_TANK) {
            int max = gun.getMaxDamage();
            int air = max > 0 ? max - gun.getDamageValue() : 0;
            int pct = max > 0 ? Math.round(100f * air / max) : 0;
            int color = pct <= 20 ? WARN_COLOR : TEXT_COLOR;
            drawRightAligned(g, mc, Component.translatable(
                    "gui." + CreatePneumaticTacticals.MODID + ".hud.air", pct).getString(), x, y - 29, color);
        }
    }

    private static int reserveCount(LocalPlayer player, GunStats stats, String ammoId) {
        if (ammoId == null || ammoId.isEmpty() || player.isCreative()) return 0;
        boolean cartridge = stats.supply != null && stats.supply.supplyType == SupplyType.CARTRIDGE;
        return ClientGunInput.countMatchingPods(player, cartridge, ammoId);
    }

    private static void drawRightAligned(GuiGraphics g, Minecraft mc, String text, int rightX, int y, int color) {
        g.drawString(mc.font, text, rightX - mc.font.width(text), y, color, true);
    }

    private static void drawHitmarker(GuiGraphics g, int x, int y) {
        int c = 0xFFFFFFFF;
        int d = 4;
        int l = 3;
        g.fill(x - d - l, y - d - l, x - d, y - d, c);
        g.fill(x + d, y - d - l, x + d + l, y - d, c);
        g.fill(x - d - l, y + d, x - d, y + d + l, c);
        g.fill(x + d, y + d, x + d + l, y + d + l, c);
    }

    /** the held gun, or null */
    private static ItemStack heldGun() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        ItemStack stack = player.getMainHandItem();
        return stack.getItem() instanceof GeoGunItem ? stack : null;
    }

}
