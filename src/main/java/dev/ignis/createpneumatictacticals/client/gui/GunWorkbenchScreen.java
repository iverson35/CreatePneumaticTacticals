package dev.ignis.createpneumatictacticals.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import dev.ignis.createpneumatictacticals.menu.GunWorkbenchMenu;

import java.util.Map;

/**
 * Pneumatic gun assembly bench screen. Left: gun slot area. Middle: module
 * slots (rendered as bordered boxes; the container background is drawn by
 * hand since no texture asset exists yet). Right: live stats panel from
 * GunStats aggregation.
 */
public class GunWorkbenchScreen extends AbstractContainerScreen<GunWorkbenchMenu> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "textures/gui/gun_workbench.png");
    private static final boolean HAS_TEXTURE = false; // flip when the texture asset lands

    private static final int IMAGE_WIDTH = 256;
    private static final int IMAGE_HEIGHT = 200;

    /** stats panel: always-on core stats, secondary block behind the toggle */
    private boolean showAllStats = false;
    private Button statsToggle;

    public GunWorkbenchScreen(GunWorkbenchMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = IMAGE_WIDTH;
        this.imageHeight = IMAGE_HEIGHT;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = IMAGE_HEIGHT - 92;
        this.titleLabelX = 8;
        this.titleLabelY = 4;
    }

    @Override
    protected void init() {
        super.init();
        // compact "+" toggle next to the stats header: shows/hides secondary stats
        statsToggle = addRenderableWidget(Button.builder(Component.literal("+"),
                        b -> {
                            showAllStats = !showAllStats;
                            statsToggle.setMessage(Component.literal(showAllStats ? "-" : "+"));
                        })
                .bounds(this.leftPos + 240, this.topPos + 3, 12, 12)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderStatsPanel(graphics, mouseX, mouseY);
        // reject reason when carrying a module over a module slot
        ItemStack carried = this.menu.getCarried();
        if (!carried.isEmpty() && this.hoveredSlot instanceof GunWorkbenchMenu.ModuleSlot moduleSlot) {
            String reason = this.menu.rejectReason(moduleSlot.moduleIndex, carried);
            if (reason != null) {
                graphics.drawString(this.font, Component.translatable(
                                "gui." + CreatePneumaticTacticals.MODID + ".reject." + reason),
                        this.leftPos + 8, this.topPos + this.imageHeight - 102, 0xFFFF6060, false);
            }
        }
        this.renderTooltip(graphics, mouseX, mouseY);
    }
    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        if (HAS_TEXTURE) {
            RenderSystem.enableBlend();
            graphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
            RenderSystem.disableBlend();
        } else {
            // procedural background: panel + slot boxes, so the screen is usable
            // before a texture asset is authored
            graphics.fill(this.leftPos, this.topPos,
                    this.leftPos + this.imageWidth, this.topPos + this.imageHeight, 0xFF2B2B2B);
            graphics.renderOutline(this.leftPos, this.topPos, this.imageWidth, this.imageHeight, 0xFF555555);
            // gun slot
            drawSlotBox(graphics, this.menu.slots.get(GunWorkbenchMenu.SLOT_GUN));
            // module slots
            for (int i = 1; i <= GunWorkbenchMenu.MODULE_COUNT; i++) {
                drawSlotBox(graphics, this.menu.slots.get(i));
            }
            // player inventory slots
            for (int i = GunWorkbenchMenu.INV_START; i < GunWorkbenchMenu.INV_START + 36; i++) {
                drawSlotBox(graphics, this.menu.slots.get(i));
            }
        }
        drawSlotTypeLabels(graphics);
    }

    /** Half-scale type label under every module slot so players see what fits where. */
    private void drawSlotTypeLabels(GuiGraphics graphics) {
        graphics.pose().pushPose();
        graphics.pose().scale(0.5f, 0.5f, 1.0f);
        for (int i = 1; i <= GunWorkbenchMenu.MODULE_COUNT; i++) {
            net.minecraft.world.inventory.Slot slot = this.menu.slots.get(i);
            if (!slot.isActive()) continue;
            if (!(slot instanceof GunWorkbenchMenu.ModuleSlot moduleSlot)) continue;
            Component label = Component.translatable(
                    "module_type." + CreatePneumaticTacticals.MODID + "." + moduleSlot.type.getSerializedName());
            if (moduleSlot.hgPosition() != null) {
                label = label.copy().append(Component.translatable(
                        "hg_pos." + CreatePneumaticTacticals.MODID + "." + moduleSlot.hgPosition().getSerializedName()));
            }
            float scale = 2.0f; // inverse of pose scale: coords in scaled space
            int w = this.font.width(label);
            int x = (int) ((this.leftPos + slot.x + 9) * scale - w / 2.0f);
            int y = (int) ((this.topPos + slot.y + 19) * scale);
            graphics.drawString(this.font, label, x, y, 0xFF777777, false);
        }
        graphics.pose().popPose();
    }

    private void drawSlotBox(GuiGraphics graphics, net.minecraft.world.inventory.Slot slot) {
        if (!slot.isActive()) return;
        int x = this.leftPos + slot.x;
        int y = this.topPos + slot.y;
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF1E1E1E);
        graphics.renderOutline(x - 1, y - 1, 18, 18, 0xFF8B8B8B);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xFFE0E0E0, false);
        graphics.drawString(this.font, this.playerInventoryTitle,
                this.inventoryLabelX, this.inventoryLabelY, 0xFFE0E0E0, false);
        graphics.drawString(this.font, Component.translatable(
                        "container." + CreatePneumaticTacticals.MODID + ".gun_workbench.stats"),
                190, 6, 0xFFAAAAAA, false);
    }

    /** Right-side live stats panel (simple text lines). */
    private void renderStatsPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        ItemStack gun = this.menu.getGunStack();
        if (!(gun.getItem() instanceof GunItem)) return;
        GunStats stats = GunStats.ofGun(gun);

        int x = this.leftPos + 190;
        int y = this.topPos + 24;
        // core stats (always visible)
        graphics.drawString(this.font, fmt("damage_multiplier", stats.damageMultiplier), x, y, 0xFFD0D0D0, false);
        graphics.drawString(this.font, fmt("fire_rate_multiplier", stats.fireRateMultiplier), x, y + 12, 0xFFD0D0D0, false);
        graphics.drawString(this.font, fmt("ergonomics", stats.ergonomics), x, y + 24, 0xFFD0D0D0, false);
        graphics.drawString(this.font, fmt("recoil_multiplier", stats.recoilMultiplier), x, y + 36, 0xFFD0D0D0, false);
        // secondary stats ("+" toggle); zoom stats are never shown here —
        // they belong to the equipped sight and are visible in its tooltip
        if (showAllStats) {
            graphics.drawString(this.font, fmt("reload_speed", stats.reloadSpeed), x, y + 48, 0xFF909090, false);
            graphics.drawString(this.font, fmt("hipfire_accuracy_multiplier", stats.hipfireAccuracyMultiplier), x, y + 60, 0xFF909090, false);
            graphics.drawString(this.font, fmt("bullet_speed", stats.bulletSpeed), x, y + 72, 0xFF909090, false);
            graphics.drawString(this.font, fmt("recoil_recovery", stats.recoilRecovery), x, y + 84, 0xFF909090, false);
        }

        Map<ModuleType, ModuleDefinition> modules = GunNbt.readModules(gun);
        ModuleDefinition receiver = modules.get(ModuleType.RECEIVER);
        if (receiver != null && receiver.gunType != null) {
            graphics.drawString(this.font,
                    Component.translatable("stat." + CreatePneumaticTacticals.MODID + ".gun_type")
                            .append(": ").append(Component.translatable("gun_type." + CreatePneumaticTacticals.MODID + "." + receiver.gunType.getSerializedName())),
                    x, y - 10, 0xFF90C890, false);
        }
    }

    private static Component fmt(String statKey, double value) {
        return Component.translatable("stat." + CreatePneumaticTacticals.MODID + "." + statKey)
                .append(": ").append(String.format("%.2f", value));
    }

}