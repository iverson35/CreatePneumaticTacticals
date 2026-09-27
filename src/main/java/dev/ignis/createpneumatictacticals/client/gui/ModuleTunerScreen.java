package dev.ignis.createpneumatictacticals.client.gui;

import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.menu.ModuleTunerMenu;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.ModuleRoll;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Map;

/**
 * Tuning table GUI (176x140): the module sits in the header slot, 敲击 and
 * 校准 flank it, and every rolled attribute of its definition gets a row —
 * name and effective value on one line, the bar underneath.
 *
 * <p>A bar reads "how much of this attribute's authored extreme is still
 * there": the centre line is 0 (attribute neutralised), a full bar is the
 * value written in the module JSON. Side and colour come from the attribute
 * itself, never from the sign of the number — an attribute that helps the
 * shooter fills green to the right, one that hurts fills red to the left.
 * All rows share the centre line.
 */
@OnlyIn(Dist.CLIENT)
public class ModuleTunerScreen extends AbstractContainerScreen<ModuleTunerMenu> {

    // header: module slot flanked by the two buttons
    private static final int SLOT_X = 80, SLOT_Y = 17;
    private static final int BTN_Y = 18, BTN_W = 56, BTN_H = 14;
    private static final int KNOCK_X = 8, CALIBRATE_X = 112;
    // panel + rows
    private static final int PANEL_TOP = 36, PANEL_BOTTOM = 136;
    private static final int ROW_TOP = 42, ROW_STEP = 13, BAR_TOP_OFF = 8, BAR_H = 4;
    // bar geometry: fixed centre line with a little air left of the longest bar
    private static final int CENTER_X = 88, HALF = 76;
    // colours
    private static final int COLOR_GOOD = 0xFF57C45C;
    private static final int COLOR_BAD = 0xFFD05050;
    private static final int COLOR_TRACK = 0xFF1A1A1E;
    private static final int COLOR_AXIS = 0xFF9090A0;
    private static final int COLOR_LABEL = 0xFFE0E0E0;
    private static final int COLOR_HINT = 0xFF909090;

    private Button knockButton;
    private Button calibrateButton;

    public ModuleTunerScreen(ModuleTunerMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 140;
    }

    @Override
    protected void init() {
        super.init();
        knockButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createpneumatictacticals.tuner_knock"),
                        b -> send(ModuleTunerMenu.BTN_KNOCK))
                .bounds(this.leftPos + KNOCK_X, this.topPos + BTN_Y, BTN_W, BTN_H)
                .build());
        calibrateButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createpneumatictacticals.tuner_calibrate"),
                        b -> send(ModuleTunerMenu.BTN_CALIBRATE))
                .bounds(this.leftPos + CALIBRATE_X, this.topPos + BTN_Y, BTN_W, BTN_H)
                .build());
    }

    /** menu buttons are validated server-side (ModuleTunerMenu.clickMenuButton) */
    private void send(int button) {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, button);
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx);
        // the module is the authority; its fractions reach us through the slot
        // broadcast, so the button state follows the synced stack every frame
        ItemStack stack = this.menu.getSlot(ModuleTunerMenu.TUNER_SLOT).getItem();
        ModuleDefinition def = ModuleManager.definitionOf(stack);
        Map<ModuleRoll.Attr, Double> state = def == null ? null
                : ModuleRoll.state(def, ModuleItem.getRolls(stack));
        knockButton.active = def != null && ModuleRoll.canKnock(def, state);
        calibrateButton.active = def != null && ModuleRoll.canCalibrate(def, state);
        super.render(gfx, mouseX, mouseY, partialTick);
        this.renderTooltip(gfx, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        gfx.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth,
                this.topPos + this.imageHeight, 0xFF2A2A2E);
        gfx.fill(this.leftPos + 1, this.topPos + 1, this.leftPos + this.imageWidth - 1,
                this.topPos + this.imageHeight - 1, 0xFF3A3A40);
        gfx.fill(this.leftPos + 4, this.topPos + PANEL_TOP, this.leftPos + this.imageWidth - 4,
                this.topPos + PANEL_BOTTOM, 0xFF232326);
        // slot frame (the container draws the item itself)
        gfx.renderOutline(this.leftPos + SLOT_X - 1, this.topPos + SLOT_Y - 1, 18, 18, 0xFF8B8B8B);
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.drawString(this.font, this.title, 8, 6, 0xFFFFFF, false);
        ItemStack stack = this.menu.getSlot(ModuleTunerMenu.TUNER_SLOT).getItem();
        if (stack.isEmpty()) {
            hint(gfx, "gui.createpneumatictacticals.tuner_empty");
            return;
        }
        ModuleDefinition def = ModuleManager.definitionOf(stack);
        if (def == null) {
            hint(gfx, "gui.createpneumatictacticals.tuner_unknown");
            return;
        }
        List<ModuleRoll.Attr> attrs = ModuleRoll.rollable(def);
        if (attrs.isEmpty()) {
            hint(gfx, "gui.createpneumatictacticals.tuner_no_rolls");
            return;
        }
        Map<ModuleRoll.Attr, Double> state = ModuleRoll.state(def, ModuleItem.getRolls(stack));
        for (int i = 0; i < attrs.size(); i++) {
            ModuleRoll.Attr attr = attrs.get(i);
            int y = ROW_TOP + i * ROW_STEP;
            double fraction = state.getOrDefault(attr, 1.0);
            boolean good = attr.isGood(def);
            gfx.drawString(this.font,
                    Component.translatable("stat.createpneumatictacticals." + attr.key),
                    8, y, COLOR_LABEL, false);
            double value = attr.authored(def) * fraction;
            String text = (value > 0 ? "+" : "") + ModuleItem.formatStat(value);
            gfx.drawString(this.font, text, this.imageWidth - 8 - this.font.width(text), y,
                    good ? COLOR_GOOD : COLOR_BAD, false);
            int barY = y + BAR_TOP_OFF;
            gfx.fill(CENTER_X - HALF, barY, CENTER_X + HALF, barY + BAR_H, COLOR_TRACK);
            int len = (int) Math.round(fraction * HALF);
            if (len > 0) {
                if (good) {
                    gfx.fill(CENTER_X, barY, CENTER_X + len, barY + BAR_H, COLOR_GOOD);
                } else {
                    gfx.fill(CENTER_X - len, barY, CENTER_X, barY + BAR_H, COLOR_BAD);
                }
            }
            gfx.fill(CENTER_X - 1, barY - 1, CENTER_X, barY + BAR_H + 1, COLOR_AXIS);
        }
    }

    private void hint(GuiGraphics gfx, String key) {
        Component text = Component.translatable(key);
        gfx.drawString(this.font, text, (this.imageWidth - this.font.width(text)) / 2, 80, COLOR_HINT, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean consumed = super.mouseClicked(mouseX, mouseY, button);
        // vanilla ContainerEventHandler sets keyboard focus on the consumed
        // widget; clear it or the button keeps drawing the focus outline
        this.setFocused(null);
        return consumed;
    }
}
