package dev.ignis.createpneumatictacticals.client.gui;

import dev.ignis.createpneumatictacticals.menu.DyePalette;
import dev.ignis.createpneumatictacticals.menu.ModuleWorkbenchMenu;
import dev.ignis.createpneumatictacticals.block.entity.ModuleWorkbenchBlockEntity;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.WorkbenchActionPacket;
import dev.ignis.createpneumatictacticals.recipe.ModuleCraftingRecipe;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import java.util.ArrayList;
import java.util.List;

/**
 * Accessory workbench GUI: two tabs sharing one window (256x203).
 * <pre>
 *   y2     window title + tab buttons (crafting | dyeing) top-right
 *   y16-112  tab content panel (full width)
 *   y113  inventory label
 *   y123+ player inventory
 * </pre>
 * Crafting tab: scrollable full-width recipe list (5 rows, hover shows the
 * ingredient tooltip, double-click crafts) + craft button.
 * Dyeing tab: module slot, 3 region rows, 16-color palette, apply button.
 * The dye module slot lives at the top-left of the dye panel (menu coordinate
 * 16,32 — keep ModuleWorkbenchMenu in sync).
 */
@OnlyIn(Dist.CLIENT)
public class ModuleWorkbenchScreen extends AbstractContainerScreen<ModuleWorkbenchMenu> {

    private static final int COLOR_SWATCH_SIZE = 12;

    // tab geometry (relative coords)
    private static final int TAB_CRAFT_X = 170, TAB_DYE_X = 212, TAB_Y = 2, TAB_W = 42, TAB_H = 13;
    // content panel
    private static final int PANEL_TOP = 16, PANEL_BOTTOM = 112;
    // recipe list
    private static final int LIST_ROWS = 5;
    private static final int LIST_ROW_HEIGHT = 13;
    private static final int LIST_TOP = 34;
    private static final int LIST_X = 8, LIST_W = 222, LIST_ICON_X = 232;
    // dye tab
    private static final int REGION_X = 48, REGION_BOX_X = 104, REGION_TOP = 32, REGION_STEP = 14;
    private static final int GRID_X = 140, GRID_Y = 32;
    // action buttons
    private static final int BTN_W = 84, BTN_H = 14, BTN_Y = 96, BTN_X = 164;
    private static final int STATUS_Y = 102;
    // visibility toggle (dye tab, header row right side)
    private static final int VIS_X = 160, VIS_Y = 16, VIS_W = 92, VIS_H = 13;

    private final List<ModuleCraftingRecipe> recipes = new ArrayList<>();
    private int selectedRecipe = -1;
    private int scrollOffset = 0;
    private int lastClickIndex = -1;
    private long lastClickTime;

    private boolean dyeTab;
    private Button craftButton;
    private Button dyeButton;
    private Button clearButton;
    private Button visibleButton;

    public ModuleWorkbenchScreen(ModuleWorkbenchMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 256;
        this.imageHeight = 203;
        // inventory slots occupy y123+; the label sits just above them, clear of the panel
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 113;
    }

    @Override
    protected void init() {
        super.init();
        refreshRecipes();
        addRenderableWidget(Button.builder(Component.translatable("gui.createpneumatictacticals.tab_craft"),
                        b -> setDyeTab(false))
                .bounds(this.leftPos + TAB_CRAFT_X, this.topPos + TAB_Y, TAB_W, TAB_H)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.createpneumatictacticals.tab_dye"),
                        b -> setDyeTab(true))
                .bounds(this.leftPos + TAB_DYE_X, this.topPos + TAB_Y, TAB_W, TAB_H)
                .build());
        craftButton = addRenderableWidget(Button.builder(Component.translatable("gui.createpneumatictacticals.craft"),
                        b -> craftSelected())
                .bounds(this.leftPos + BTN_X, this.topPos + BTN_Y, BTN_W, BTN_H)
                .build());
        dyeButton = addRenderableWidget(Button.builder(Component.translatable("gui.createpneumatictacticals.dye"),
                        b -> dyeConfirm())
                .bounds(this.leftPos + BTN_X, this.topPos + BTN_Y, BTN_W, BTN_H)
                .build());
        clearButton = addRenderableWidget(Button.builder(Component.translatable("gui.createpneumatictacticals.clear_dye"),
                        b -> sendClearDye())
                .bounds(this.leftPos + BTN_X, this.topPos + BTN_Y + BTN_H + 2, BTN_W, BTN_H)
                .build());
        // visibility toggle (dye tab): label refreshes each frame from the
        // synced slot stack, click sends TOGGLE_VISIBLE
        visibleButton = addRenderableWidget(Button.builder(visibleButtonLabel(), b -> sendToggleVisible())
                .bounds(this.leftPos + VIS_X, this.topPos + VIS_Y, VIS_W, VIS_H)
                .build());
        // scroll arrows in the recipe panel header
        addRenderableWidget(Button.builder(Component.literal("<"),
                        b -> scrollOffset = Math.max(0, scrollOffset - 1))
                .bounds(this.leftPos + 196, this.topPos + 17, 12, 12)
                .build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                        b -> scrollOffset = Math.min(maxScroll(), scrollOffset + 1))
                .bounds(this.leftPos + 238, this.topPos + 17, 12, 12)
                .build());
        applyTab();
    }

    private void setDyeTab(boolean dye) {
        if (this.dyeTab == dye) return;
        this.dyeTab = dye;
        applyTab();
    }

    private void sendClearDye() {
        CptNetwork.CHANNEL.sendToServer(new WorkbenchActionPacket(
                WorkbenchActionPacket.Action.DYE_CLEAR, null, -1));
    }

    private void sendToggleVisible() {
        CptNetwork.CHANNEL.sendToServer(new WorkbenchActionPacket(
                WorkbenchActionPacket.Action.TOGGLE_VISIBLE, null, -1));
    }

    /** the toggle reflects the module item's own Hidden NBT (synced through
     *  the dye slot) — the item is the authority, exactly like dye colors */
    private Component visibleButtonLabel() {
        ItemStack slotStack = this.menu.getSlot(ModuleWorkbenchMenu.DYE_SLOT).getItem();
        if (slotStack.isEmpty()) {
            return Component.translatable("gui.createpneumatictacticals.visible");
        }
        return ModuleItem.isHidden(slotStack)
                ? Component.translatable("gui.createpneumatictacticals.show_module")
                : Component.translatable("gui.createpneumatictacticals.visible");
    }

    private void applyTab() {
        craftButton.visible = !dyeTab;
        clearButton.visible = dyeTab;
        dyeButton.visible = dyeTab;
        visibleButton.visible = dyeTab;
    }

    private void refreshRecipes() {
        recipes.clear();
        if (this.minecraft != null && this.minecraft.player != null
                && this.minecraft.level != null) {
            recipes.addAll(this.menu.allRecipes(this.minecraft.level));
        }
        scrollOffset = Math.min(scrollOffset, maxScroll());
        if (selectedRecipe >= recipes.size()) selectedRecipe = recipes.isEmpty() ? -1 : 0;
    }

    private int maxScroll() {
        return Math.max(0, recipes.size() - LIST_ROWS);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);
        // keep the visibility toggle in step with the synced slot stack
        // (server writes the Hidden NBT, the slot broadcast carries it back)
        boolean hasModule = !this.menu.getSlot(ModuleWorkbenchMenu.DYE_SLOT).getItem().isEmpty();
        visibleButton.active = hasModule;
        visibleButton.setMessage(visibleButtonLabel());
        this.renderTooltip(gfx, mouseX, mouseY);
        if (!dyeTab) {
            int hover = hoveredRow(mouseX, mouseY);
            if (hover >= 0 && hover < recipes.size()) {
                gfx.renderComponentTooltip(this.font, ingredientTooltip(recipes.get(hover)), mouseX, mouseY);
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        gfx.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth,
                this.topPos + this.imageHeight, 0xFF2A2A2E);
        gfx.fill(this.leftPos + 1, this.topPos + 1, this.leftPos + this.imageWidth - 1,
                this.topPos + this.imageHeight - 1, 0xFF3A3A40);
        // tab content panel (full width)
        gfx.fill(this.leftPos + 4, this.topPos + PANEL_TOP, this.leftPos + 252,
                this.topPos + PANEL_BOTTOM, 0xFF232326);
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.drawString(this.font, this.title, 6, 4, 0xFFFFFF, false);
        super.renderLabels(gfx, mouseX, mouseY); // inventory label
        // active tab marker
        int activeX = dyeTab ? TAB_DYE_X : TAB_CRAFT_X;
        gfx.renderOutline(activeX - 1, TAB_Y - 1, TAB_W + 2, TAB_H + 2, 0xFFD080);

        if (dyeTab) {
            renderDyeTab(gfx);
        } else {
            renderCraftTab(gfx, mouseX, mouseY);
        }
    }

    private void renderCraftTab(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.drawString(this.font,
                Component.translatable("gui.createpneumatictacticals.recipes"), 8, 20, 0xFFD080, false);
        gfx.drawString(this.font, (scrollOffset + 1) + "/" + (maxScroll() + 1), 210, 20, 0xFF909090, false);
        for (int row = 0; row < LIST_ROWS; row++) {
            int index = scrollOffset + row;
            if (index >= recipes.size()) break;
            ModuleCraftingRecipe recipe = recipes.get(index);
            int y = LIST_TOP + row * LIST_ROW_HEIGHT;
            if (index == selectedRecipe) {
                gfx.fill(LIST_X, y - 2, LIST_X + LIST_W, y + 11, 0x805A5A8A);
            }
            boolean canCraft = this.minecraft != null && this.minecraft.player != null
                    && this.menu.canCraft(this.minecraft.player, recipe);
            gfx.drawString(this.font, trim(moduleDisplayName(recipe), 30), 12, y,
                    canCraft ? 0x80FF80 : 0x909090, false);
            gfx.renderItem(recipe.resultStack(), LIST_ICON_X, y - 2);
        }
    }

    private void renderDyeTab(GuiGraphics gfx) {
        gfx.drawString(this.font,
                Component.translatable("gui.createpneumatictacticals.dyeing"), 8, 20, 0xFFD080, false);
        ModuleWorkbenchBlockEntity be = this.menu.getBlockEntity();
        // read the synced MENU SLOT stack, not the client BE field — the
        // server writes dye NBT and broadcasts slot contents; the client
        // BE's dyeModule field never syncs, so the old path showed a
        // stale (pre-dye) module
        ItemStack slotStack = this.menu.getSlot(ModuleWorkbenchMenu.DYE_SLOT).getItem();
        ModuleDefinition def = definitionOf(slotStack);
        // module slot box (the item itself is drawn by the container at 16,32)
        gfx.renderOutline(15, 31, 18, 18, 0xFF8B8B8B);
        // region rows
        for (int r = 0; r < 3; r++) {
            int y = REGION_TOP + r * REGION_STEP;
            gfx.drawString(this.font,
                    Component.translatable("gui.createpneumatictacticals.region", r + 1),
                    REGION_X, y + 2, 0xE0E0E0, false);
            int cur = currentColor(def, slotStack, r);
            gfx.fill(REGION_BOX_X, y, REGION_BOX_X + 10, y + 10, 0xFF000000 | cur);
            if (be.getRegion() == r) {
                gfx.renderOutline(REGION_BOX_X - 1, y - 1, 12, 12, 0xFFFFFFFF);
            }
        }
        // 16-color swatch grid (8 x 2)
        for (int i = 0; i < DyePalette.SIZE; i++) {
            int col = i % 8, row = i / 8;
            int x = GRID_X + col * (COLOR_SWATCH_SIZE + 2);
            int y = GRID_Y + row * (COLOR_SWATCH_SIZE + 2);
            gfx.fill(x, y, x + COLOR_SWATCH_SIZE, y + COLOR_SWATCH_SIZE, DyePalette.argbOf(i));
            if (be.getChosenColor() == i) {
                gfx.renderOutline(x - 1, y - 1,
                        COLOR_SWATCH_SIZE + 2, COLOR_SWATCH_SIZE + 2, 0xFFFFFFFF);
            }
        }
        // status line
        String status = dyeStatus(be);
        if (!status.isEmpty()) {
            gfx.drawString(this.font, trim(status, 18), 12, STATUS_Y, 0xFF6060, false);
        }
    }

    /** Ingredient lines for the hovered recipe (or the selected one on the button). */
    private List<Component> ingredientTooltip(ModuleCraftingRecipe recipe) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("module." + recipe.getModuleId().getNamespace()
                + "." + recipe.getModuleId().getPath()).withStyle(ChatFormatting.AQUA));
        for (var ingredient : recipe.getIngredientList()) {
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length == 0) continue;
            lines.add(Component.literal("- ")
                    .append(stacks[0].getHoverName())
                    .withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private int hoveredRow(double mouseX, double mouseY) {
        for (int row = 0; row < LIST_ROWS; row++) {
            int index = scrollOffset + row;
            if (index >= recipes.size()) break;
            int y = LIST_TOP + row * LIST_ROW_HEIGHT;
            if (mouseX >= this.leftPos + LIST_X && mouseX < this.leftPos + LIST_X + LIST_W
                    && mouseY >= this.topPos + y - 2 && mouseY < this.topPos + y + 11) {
                return index;
            }
        }
        return -1;
    }

    private int currentColor(ModuleDefinition def, ItemStack slotStack, int region) {
        if (!slotStack.isEmpty()) {
            int[] colors = GunNbtColorAccess.getColors(slotStack, ModuleItem.getModuleId(slotStack));
            if (colors != null && colors.length >= 3 && colors[region] != -1) return colors[region];
        }
        return 0xFF8B8B8B; // neutral placeholder for undyed slots
    }

    private ModuleDefinition definitionOf(ItemStack stack) {
        ResourceLocation id = ModuleItem.getModuleId(stack);
        return id != null ? ModuleManager.get(id) : null;
    }

    private String moduleDisplayName(ModuleCraftingRecipe recipe) {
        ResourceLocation id = recipe.getModuleId();
        String key = "module." + id.getNamespace() + "." + id.getPath();
        Component c = Component.translatable(key);
        String s = c.getString();
        if (!s.equals(key)) return s;
        return id.getPath();
    }

    private String trim(String s, int maxChars) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 1) + "…";
    }

    private String dyeStatus(ModuleWorkbenchBlockEntity be) {
        if (!be.hasModule()) return Component.translatable("gui.createpneumatictacticals.no_module").getString();
        if (be.getRegion() < 0) return Component.translatable("gui.createpneumatictacticals.pick_region").getString();
        if (be.getChosenColor() < 0) return Component.translatable("gui.createpneumatictacticals.pick_color").getString();
        return "";
    }

    private void craftSelected() {
        if (selectedRecipe < 0 || selectedRecipe >= recipes.size()) return;
        ModuleCraftingRecipe recipe = recipes.get(selectedRecipe);
        CptNetwork.CHANNEL.sendToServer(new WorkbenchActionPacket(
                WorkbenchActionPacket.Action.CRAFT, recipe.getId(), -1));
    }

    private void dyeConfirm() {
        CptNetwork.CHANNEL.sendToServer(new WorkbenchActionPacket(
                WorkbenchActionPacket.Action.DYE_CONFIRM, null, -1));
    }

    /** small helper mirroring GunNbt color schema */
    private static final class GunNbtColorAccess {
        static int[] getColors(ItemStack stack, ResourceLocation moduleId) {
            if (moduleId == null) return null;
            net.minecraft.nbt.CompoundTag root = stack.getTag();
            if (root == null) return null;
            net.minecraft.nbt.CompoundTag colors = root.getCompound("Colors");
            if (!colors.contains(moduleId.toString())) return null;
            return colors.getIntArray(moduleId.toString());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (dyeTab) return super.mouseScrolled(mouseX, mouseY, delta);
        if (delta > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        } else {
            scrollOffset = Math.min(maxScroll(), scrollOffset + 1);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dyeTab) {
            ModuleWorkbenchBlockEntity be = this.menu.getBlockEntity();
            if (be.hasModule()) {
                // region rows
                for (int r = 0; r < 3; r++) {
                    int y = REGION_TOP + r * REGION_STEP;
                    if (mouseX >= this.leftPos + REGION_BOX_X && mouseX < this.leftPos + REGION_BOX_X + 10
                            && mouseY >= this.topPos + y && mouseY < this.topPos + y + 10) {
                        CptNetwork.CHANNEL.sendToServer(new WorkbenchActionPacket(
                                WorkbenchActionPacket.Action.SET_REGION, null, r));
                        be.setRegion(r);
                        return true;
                    }
                }
                // color grid
                for (int i = 0; i < DyePalette.SIZE; i++) {
                    int col = i % 8, row = i / 8;
                    int x = GRID_X + col * (COLOR_SWATCH_SIZE + 2);
                    int y = GRID_Y + row * (COLOR_SWATCH_SIZE + 2);
                    if (mouseX >= this.leftPos + x && mouseX < this.leftPos + x + COLOR_SWATCH_SIZE
                            && mouseY >= this.topPos + y && mouseY < this.topPos + y + COLOR_SWATCH_SIZE) {
                        CptNetwork.CHANNEL.sendToServer(new WorkbenchActionPacket(
                                WorkbenchActionPacket.Action.SET_COLOR, null, i));
                        be.setChosenColor(i);
                        return true;
                    }
                }
            }
            boolean consumed = super.mouseClicked(mouseX, mouseY, button);
            // vanilla ContainerEventHandler.mouseClicked sets keyboard focus on the
            // consumed widget; clear it or the button keeps drawing the white
            // focus outline forever (isHoveredOrFocused includes focus)
            this.setFocused(null);
            return consumed;
        }
        // crafting tab: click selects, double-click crafts
        int index = hoveredRow(mouseX, mouseY);
        if (index >= 0) {
            long now = Util.getMillis();
            if (index == lastClickIndex && now - lastClickTime < 400 && index == selectedRecipe) {
                craftSelected();
            } else {
                selectedRecipe = index;
            }
            lastClickIndex = index;
            lastClickTime = now;
            return true;
        }
        boolean consumed = super.mouseClicked(mouseX, mouseY, button);
        this.setFocused(null);
        return consumed;
    }
}
