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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import java.util.ArrayList;
import java.util.List;

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

        // 3D workbench: stats panel while looking at the [▼] take marker
        renderBenchStats(g, mc, width, height);

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

    // --- 3D workbench stats panel (hovering the [▼] take marker) ---

    private static final int HEADER_COLOR = 0xFF90C890;
    private static final int STAT_COLOR = 0xFFD0D0D0;
    private static final int HINT_COLOR = 0xFF909090;
    /** panel line height (the font's 9px line plus a little air) */
    private static final int LINE_H = 11;

    /**
     * While the crosshair hovers the bench's take marker, draw the staged
     * gun's stats: caliber + the core multipliers, and with Shift the full
     * spec (every aggregate stat plus the feed/supply module data). The gun
     * item's tooltip uses the same default/full split.
     */
    private static void renderBenchStats(GuiGraphics g, Minecraft mc, int width, int height) {
        dev.ignis.createpneumatictacticals.client.render.BenchTargetPicker.Hover hover =
                dev.ignis.createpneumatictacticals.client.render.BenchTargetPicker.currentHover();
        if (hover == null || !hover.marker().isTake()) return;
        // the staged gun lives on the bench BE (marker carries no stack)
        if (!(mc.level.getBlockEntity(hover.benchPos())
                instanceof dev.ignis.createpneumatictacticals.block.entity.GunWorkbenchBlockEntity bench)) {
            return;
        }
        ItemStack gun = bench.getGunSlot().getItem(0);
        if (!(gun.getItem() instanceof GunItem)) return; // nothing staged
        GunStats stats = GunStats.ofGun(gun);
        boolean full = Screen.hasShiftDown();

        Component header = null;
        if (stats.isComplete() && stats.receiver != null && stats.receiver.gunType != null) {
            header = Component.translatable(statKey("gun_type")).append(": ")
                    .append(Component.translatable("gun_type." + CreatePneumaticTacticals.MODID
                            + "." + stats.receiver.gunType.getSerializedName()));
        }
        List<Component> lines = new ArrayList<>();
        if (full) {
            addFeedLine(lines, stats);
            addSupplyLine(lines, stats);
            lines.add(fmt("reload_speed", stats.reloadSpeed));
            lines.add(fmt("damage_multiplier", stats.damageMultiplier));
            lines.add(fmt("fire_rate_multiplier", stats.fireRateMultiplier));
            lines.add(fmt("hipfire_accuracy_multiplier", stats.hipfireAccuracyMultiplier));
            lines.add(fmt("ergonomics", stats.ergonomics));
            lines.add(fmt("aim_zoom", stats.aimZoom));
            lines.add(fmt("tactical_aim_zoom", stats.tacticalAimZoom));
            lines.add(fmt("bullet_speed", stats.bulletSpeed));
            lines.add(fmt("recoil_vertical_multiplier", stats.recoilVerticalMultiplier));
            lines.add(fmt("recoil_horizontal_multiplier", stats.recoilHorizontalMultiplier));
            lines.add(fmt("recoil_recovery", stats.recoilRecovery));
            lines.add(fmt("gravity_multiplier", stats.gravityMultiplier));
            lines.add(fmt("drag_multiplier", stats.dragMultiplier));
            lines.add(fmt("gas_suppression", stats.gasSuppression));
        } else {
            lines.add(fmt("damage_multiplier", stats.damageMultiplier));
            lines.add(fmt("fire_rate_multiplier", stats.fireRateMultiplier));
            lines.add(fmt("ergonomics", stats.ergonomics));
            lines.add(fmt("recoil_vertical_multiplier", stats.recoilVerticalMultiplier));
            lines.add(fmt("recoil_horizontal_multiplier", stats.recoilHorizontalMultiplier));
            lines.add(fmt("gravity_multiplier", stats.gravityMultiplier));
            lines.add(fmt("drag_multiplier", stats.dragMultiplier));
        }

        // block centred on the crosshair row, just right of it; the hint row
        // only exists while the full spec is still one Shift away
        int rows = lines.size() + (header != null ? 1 : 0) + (full ? 0 : 1);
        int x = width / 2 + 12;
        int y = height / 2 - rows * LINE_H / 2;
        if (header != null) {
            g.drawString(mc.font, header, x, y, HEADER_COLOR);
            y += LINE_H;
        }
        for (Component line : lines) {
            g.drawString(mc.font, line, x, y, STAT_COLOR);
            y += LINE_H;
        }
        if (!full) {
            g.drawString(mc.font, Component.translatable(
                    "gui." + CreatePneumaticTacticals.MODID + ".stats_full_hint"), x, y, HINT_COLOR);
        }
    }

    /** "Feed Type: Magazine  (Capacity: 30)" — same shape as the module tooltip */
    private static void addFeedLine(List<Component> lines, GunStats stats) {
        if (stats.feed == null || stats.feed.feedType == null) return;
        MutableComponent line = Component.translatable(statKey("feed_type")).append(": ")
                .append(Component.translatable("feed_type." + CreatePneumaticTacticals.MODID
                        + "." + stats.feed.feedType.getSerializedName()));
        if (stats.feed.clipSize > 0) {
            line.append(Component.literal("  (").append(Component.translatable(statKey("clip_capacity")))
                    .append(": " + stats.feed.clipSize + ")"));
        }
        lines.add(line);
    }

    private static void addSupplyLine(List<Component> lines, GunStats stats) {
        if (stats.supply == null || stats.supply.supplyType == null) return;
        lines.add(Component.translatable(statKey("supply_type")).append(": ")
                .append(Component.translatable("supply_type." + CreatePneumaticTacticals.MODID
                        + "." + stats.supply.supplyType.getSerializedName())));
    }

    private static Component fmt(String statKey, double value) {
        return Component.translatable(statKey(statKey))
                .append(": ").append(String.format("%.2f", value));
    }

    private static String statKey(String statKey) {
        return "stat." + CreatePneumaticTacticals.MODID + "." + statKey;
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

    private void renderAmmoWidget(GuiGraphics g, Minecraft mc, LocalPlayer player, ItemStack gun,
                                  GunStats stats, int width, int height) {
        int x = width - 8 + Config.gunHudOffsetX;
        int y = height - 8 + Config.gunHudOffsetY;

        String ammoId = GunNbt.getAmmo(gun);
        boolean backpack = stats.feed != null && stats.feed.feedType == FeedType.BACKPACK;

        // line 1 (bottom): clip / reserve
        String countText;
        int countColor = TEXT_COLOR;
        // creative: reserve is bottomless, show the infinity sign
        String reserveText = player.isCreative() ? "∞" : String.valueOf(reserveCount(player, stats, ammoId));
        if (backpack) {
            // backpack feed has no clip; creative reserve is already "∞", don't double it
            countText = player.isCreative() ? "∞" : "∞ " + reserveText;
        } else {
            int clip = GunNbt.getAmmoCount(gun);
            String clipText = ClientGunInput.isReloading()
                    ? Component.translatable("gui." + CreatePneumaticTacticals.MODID + ".hud.reloading").getString()
                    : String.valueOf(clip);
            countText = clipText + " / " + reserveText;
            if (clip == 0 && !ClientGunInput.isReloading()) countColor = WARN_COLOR;
        }
        drawRightAligned(g, mc, countText, x, y - 9, countColor);

        // line 2: fire mode — the only readout of the state the fire-mode key
        // (V by default) cycles, and the only one that matters while aiming.
        // The loaded ammo type is not repeated here: the wheel shows it on
        // selection and the gun's tooltip always has it.
        FireMode mode = GunNbt.getFireMode(gun);
        drawRightAligned(g, mc, Component.translatable(
                        "fire_mode." + CreatePneumaticTacticals.MODID + "."
                                + (mode == null ? "semi" : mode.getSerializedName())).getString(),
                x, y - 19, TEXT_COLOR);

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
