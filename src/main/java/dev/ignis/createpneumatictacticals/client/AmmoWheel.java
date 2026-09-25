package dev.ignis.createpneumatictacticals.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.AmmoTypes;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import dev.ignis.createpneumatictacticals.item.GunItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Ammo wheel: holding the ammo key with a gun in hand opens a wheel of every
 * ammo type the player can load — inventory pods whose projectile type the
 * receiver accepts, or, in creative, every compatible registered type. Each
 * entry is drawn as its content item (what the pod is filled with, not the pod
 * itself); the centre keeps the current type. Releasing applies the pick; a
 * short tap still cycles to the next type like before. Either pick swaps the
 * magazine when rounds of the loaded type are still in it: they go back to the
 * backpack (overflow drops at the player's feet) and the reload for the new
 * type starts at once.
 *
 * <p>Selection is by mouse offset from the position the wheel opened at
 * (delta, not absolute), so it starts on "keep current" and the camera can be
 * turned freely while picking — same feel as the pocket laser pointer's marker
 * wheel in AirstrikePointers.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class AmmoWheel implements IGuiOverlay {

    public static final AmmoWheel INSTANCE = new AmmoWheel();

    /** ticks the key must be held before the wheel replaces the plain cycle */
    private static final int HOLD_THRESHOLD_TICKS = 4;
    /** radius of the entry ring, in GUI pixels */
    private static final float RADIUS = 62f;
    /** mouse offset below which the centre (keep current) is picked */
    private static final float CENTER_DEAD_ZONE = 12f;
    private static final int ICON_SIZE = 16;
    private static final float SELECTED_SCALE = 1.5f;
    private static final int SLOT_COLOR = 0x80101010;
    private static final int SELECT_COLOR = 0xFFFFFFFF;
    private static final int LABEL_COLOR = 0xFFFFFF;

    private static boolean wasDown = false;
    private static int heldTicks = 0;
    private static boolean active = false;
    /** -1 = centre (keep current), else index into {@link #entries} */
    private static int selected = -1;
    private static double originX = 0;
    private static double originY = 0;
    private static List<String> entries = List.of();

    private AmmoWheel() {}

    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("ammo_wheel", INSTANCE);
    }

    // --- input ---

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        boolean down = ModKeybinds.CYCLE_AMMO.isDown();
        // a screen (or losing the gun) mid-hold aborts without acting on release
        if (mc.player == null || mc.screen != null
                || !(mc.player.getMainHandItem().getItem() instanceof GeoGunItem)) {
            reset();
            wasDown = down;
            return;
        }
        if (down && !wasDown) {
            heldTicks = 0;
            active = false;
            selected = -1;
            originX = mc.mouseHandler.xpos();
            originY = mc.mouseHandler.ypos();
        }
        if (down) {
            heldTicks++;
            if (heldTicks >= HOLD_THRESHOLD_TICKS) {
                if (!active) {
                    active = true;
                    // list the held gun's ammo at activation, not at press: the
                    // player may have drawn the gun during the hold. The loaded
                    // type is excluded: the centre is the pick that keeps it,
                    // so the ring only offers actual switches
                    String current = ClientGunInput.effectiveAmmo(mc.player.getMainHandItem());
                    entries = AmmoTypes.compatibleFor(mc.player, GunStats.ofGun(mc.player.getMainHandItem()))
                            .stream().filter(id -> !id.equals(current)).toList();
                    mc.player.playSound(SoundEvents.LEVER_CLICK, 0.6f, 1.2f);
                }
                updateSelection(mc);
            }
        } else if (wasDown) {
            ItemStack gun = mc.player.getMainHandItem();
            GunStats stats = GunStats.ofGun(gun);
            if (active) {
                if (selected >= 0 && selected < entries.size()) {
                    ClientGunInput.switchAmmo(mc.player, gun, stats, entries.get(selected));
                }
                // centre: keep the current type, send nothing
            } else {
                // short tap: the plain cycle, as before the wheel existed
                ClientGunInput.cycleAmmo(mc.player, gun, stats);
            }
            reset();
        }
        wasDown = down;
    }

    /** Mouse offset -> sector; the first entry sits straight up, going clockwise. */
    private static void updateSelection(Minecraft mc) {
        double dx = mc.mouseHandler.xpos() - originX;
        double dy = mc.mouseHandler.ypos() - originY;
        if (entries.isEmpty() || Math.sqrt(dx * dx + dy * dy) < CENTER_DEAD_ZONE) {
            selected = -1;
            return;
        }
        double angle = Math.toDegrees(Math.atan2(-dy, dx));
        angle = 90 - angle;
        if (angle < 0) angle += 360;
        if (angle >= 360) angle -= 360;
        float sector = 360f / entries.size();
        int index = (int) (((angle + sector / 2) % 360) / sector);
        selected = Math.min(index, entries.size() - 1);
    }

    // --- rendering ---

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        ItemStack gun = mc.player.getMainHandItem();
        int cx = width / 2;
        int cy = height / 2;

        // the wheel reads the mouse as an offset from where it opened: draw
        // that position (clamped to the rim) so the pick is predictable
        double dx = mc.mouseHandler.xpos() - originX;
        double dy = mc.mouseHandler.ypos() - originY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        float maxDist = RADIUS + 14f;
        if (dist > maxDist) {
            dx = dx / dist * maxDist;
            dy = dy / dist * maxDist;
        }
        g.fill(cx + (int) dx - 2, cy + (int) dy - 2, cx + (int) dx + 2, cy + (int) dy + 2, SELECT_COLOR);

        String currentId = ClientGunInput.effectiveAmmo(gun);
        float sector = 360f / Math.max(1, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            String id = entries.get(i);
            double angle = Math.toRadians(i * sector - 90);
            int ix = cx + (int) (Math.cos(angle) * RADIUS);
            int iy = cy + (int) (Math.sin(angle) * RADIUS);
            boolean isSelected = i == selected;
            slot(g, ix, iy, isSelected ? 12 : 10);
            drawIcon(g, contentStack(mc, id), ix, iy, isSelected ? SELECTED_SCALE : 1f);
            if (isSelected) {
                frame(g, ix, iy, 12, SELECT_COLOR);
            }
        }

        slot(g, cx, cy, selected == -1 ? 12 : 10);
        drawIcon(g, contentStack(mc, currentId), cx, cy, selected == -1 ? SELECTED_SCALE : 1f);
        if (selected == -1) frame(g, cx, cy, 12, SELECT_COLOR);

        g.drawCenteredString(mc.font, label(mc, gun, currentId), cx, cy + (int) RADIUS + 16, LABEL_COLOR);
    }

    private static Component label(Minecraft mc, ItemStack gun, String currentId) {
        if (selected >= 0 && selected < entries.size()) {
            return GunItem.ammoDisplayName(gun, mc.level, entries.get(selected));
        }
        // current type first: with the loaded type excluded from the ring,
        // a single-type gun has an empty ring while still having ammo —
        // that reads "keep <type>", not "no ammo"
        if (currentId == null || currentId.isEmpty()) {
            return Component.translatable("gui." + CreatePneumaticTacticals.MODID + ".ammo_wheel.empty");
        }
        return Component.translatable("gui." + CreatePneumaticTacticals.MODID + ".ammo_wheel.keep",
                GunItem.ammoDisplayName(gun, mc.level, currentId));
    }

    private static void drawIcon(GuiGraphics g, ItemStack stack, int x, int y, float scale) {
        if (stack.isEmpty()) return;
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1f);
        g.renderItem(stack, -ICON_SIZE / 2, -ICON_SIZE / 2);
        pose.popPose();
    }

    private static void slot(GuiGraphics g, int x, int y, int half) {
        g.fill(x - half, y - half, x + half, y + half, SLOT_COLOR);
    }

    private static void frame(GuiGraphics g, int x, int y, int half, int color) {
        g.fill(x - half, y - half, x + half, y - half + 2, color);
        g.fill(x - half, y + half - 2, x + half, y + half, color);
        g.fill(x - half, y - half + 2, x - half + 2, y + half - 2, color);
        g.fill(x + half - 2, y - half + 2, x + half, y + half - 2, color);
    }

    // --- helpers ---

    private static ItemStack contentStack(Minecraft mc, String ammoId) {
        if (ammoId == null || ammoId.isEmpty() || mc.level == null) return ItemStack.EMPTY;
        Item item = AmmoExtension.contentItemFor(mc.level.registryAccess(), ammoId);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static void reset() {
        active = false;
        selected = -1;
        heldTicks = 0;
        entries = List.of();
    }
}
