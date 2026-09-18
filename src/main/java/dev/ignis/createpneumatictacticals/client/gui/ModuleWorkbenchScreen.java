package dev.ignis.createpneumatictacticals.client.gui;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.entity.ModuleWorkbenchBlockEntity;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.menu.ModuleWorkbenchMenu;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.WorkbenchActionPacket;
import dev.ignis.createpneumatictacticals.recipe.ModuleCraftingRecipe;
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
 * Accessory workbench GUI. Layout (256x186):
 * <pre>
 *   y4    window title
 *   y16-92   left: recipe list (x4-130) | right: dye panel (x134-252)
 *   y94   inventory label
 *   y103+  player inventory (x8-160)    | right-bottom: craft/dye buttons (x166-250)
 * </pre>
 * The dye module slot lives at the top of the dye panel (menu coordinate
 * 138,20 — keep ModuleWorkbenchMenu in sync).
 */
@OnlyIn(Dist.CLIENT)
public class ModuleWorkbenchScreen extends AbstractContainerScreen<ModuleWorkbenchMenu> {

    private static final int COLOR_SWATCH_SIZE = 10;

    /** rows visible in the recipe list */
    private static final int LIST_ROWS = 4;
    private static final int LIST_ROW_HEIGHT = 17;
    private static final int LIST_TOP = 24;

    // recipe list (relative coords)
    private static final int LIST_X = 6, LIST_W = 122;
    // dye panel
    private static final int REGION_X = 162, REGION_BOX_X = 236, REGION_TOP = 20, REGION_STEP = 14;
    private static final int GRID_X = 134, GRID_Y = 62;
    // right-bottom action area
    private static final int BTN_X = 166, BTN_W = 84, CRAFT_Y = 108, DYE_Y = 130, STATUS_Y = 154;

    private final List<ModuleCraftingRecipe> recipes = new ArrayList<>();
    private int selectedRecipe = -1;
    private int scrollOffset = 0;

    public ModuleWorkbenchScreen(ModuleWorkbenchMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 256;
        this.imageHeight = 186;
        // inventory slots occupy y103+; the label sits just above them, clear of the panels
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 94;
    }

    @Override
    protected void init() {
        super.init();
        refreshRecipes();
        addRenderableWidget(Button.builder(Component.translatable("gui.createpneumatictacticals.craft"),
                        b -> craftSelected())
                .bounds(this.leftPos + BTN_X, this.topPos + CRAFT_Y, BTN_W, 18)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.createpneumatictacticals.dye"),
                        b -> dyeConfirm())
                .bounds(this.leftPos + BTN_X, this.topPos + DYE_Y, BTN_W, 18)
                .build());
        // scroll arrows in the recipe panel header
        addRenderableWidget(Button.builder(Component.literal("<"),
                        b -> { scrollOffset = Math.max(0, scrollOffset - 1); })
                .bounds(this.leftPos + 104, this.topPos + 17, 12, 12)
                .build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                        b -> { scrollOffset = Math.min(maxScroll(), scrollOffset + 1); })
                .bounds(this.leftPos + 117, this.topPos + 17, 12, 12)
                .build());
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
        this.renderTooltip(gfx, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        gfx.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth,
                this.topPos + this.imageHeight, 0xFF2A2A2E);
        gfx.fill(this.leftPos + 1, this.topPos + 1, this.leftPos + this.imageWidth - 1,
                this.topPos + this.imageHeight - 1, 0xFF3A3A40);
        // left panel (recipes)
        gfx.fill(this.leftPos + 4, this.topPos + 16, this.leftPos + 130, this.topPos + 92, 0xFF232326);
        // right panel (dye)
        gfx.fill(this.leftPos + 134, this.topPos + 16, this.leftPos + 252, this.topPos + 92, 0xFF232326);
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.drawString(this.font, this.title, 6, 4, 0xFFFFFF, false);
        super.renderLabels(gfx, mouseX, mouseY); // inventory label

        // --- recipe list ---
        gfx.drawString(this.font,
                Component.translatable("gui.createpneumatictacticals.recipes"), 8, 19, 0xFFD080, false);
        for (int row = 0; row < LIST_ROWS; row++) {
            int index = scrollOffset + row;
            if (index >= recipes.size()) break;
            ModuleCraftingRecipe recipe = recipes.get(index);
            int y = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean selected = index == selectedRecipe;
            if (selected) {
                gfx.fill(LIST_X, y - 3, LIST_X + LIST_W, y + 12, 0x805A5A8A);
            }
            boolean canCraft = this.minecraft != null && this.minecraft.player != null
                    && this.menu.canCraft(this.minecraft.player, recipe);
            int textColor = canCraft ? 0x80FF80 : 0x909090;
            String label = moduleDisplayName(recipe);
            gfx.drawString(this.font, trim(label, 15), 10, y, textColor, false);
            ItemStack result = recipe.resultStack();
            gfx.renderItem(result, 108, y - 3);
        }

        // --- dye panel ---
        gfx.drawString(this.font,
                Component.translatable("gui.createpneumatictacticals.dyeing"), 138, 19, 0xFFD080, false);
        ModuleWorkbenchBlockEntity be = this.menu.getBlockEntity();
        ModuleDefinition def = definitionOf(be.getDyeModule());
        // region rows (the module slot itself is drawn by the container at 138,20)
        for (int r = 0; r < 3; r++) {
            int y = REGION_TOP + r * REGION_STEP;
            gfx.drawString(this.font,
                    Component.translatable("gui.createpneumatictacticals.region", r + 1),
                    REGION_X, y + 3, 0xE0E0E0, false);
            int cur = currentColor(be, def, r);
            gfx.fill(REGION_BOX_X, y, REGION_BOX_X + 10, y + 10, 0xFF000000 | cur);
            if (be.getRegion() == r) {
                gfx.renderOutline(REGION_BOX_X - 1, y - 1, 12, 12, 0xFFFFFFFF);
            }
        }
        // 16-color swatch grid
        for (int i = 0; i < dev.ignis.createpneumatictacticals.menu.DyePalette.SIZE; i++) {
            int col = i % 8, row = i / 8;
            int x = GRID_X + col * (COLOR_SWATCH_SIZE + 2);
            int y = GRID_Y + row * (COLOR_SWATCH_SIZE + 2);
            gfx.fill(x, y, x + COLOR_SWATCH_SIZE, y + COLOR_SWATCH_SIZE,
                    dev.ignis.createpneumatictacticals.menu.DyePalette.argbOf(i));
            if (be.getChosenColor() == i) {
                gfx.renderOutline(x - 1, y - 1,
                        COLOR_SWATCH_SIZE + 2, COLOR_SWATCH_SIZE + 2, 0xFFFFFFFF);
            }
        }
        // status line in the action area
        String status = dyeStatus(be);
        if (!status.isEmpty()) {
            gfx.drawString(this.font, trim(status, 20), BTN_X, STATUS_Y, 0xFF6060, false);
        }
    }

    private int currentColor(ModuleWorkbenchBlockEntity be, ModuleDefinition def, int region) {
        ItemStack module = be.getDyeModule();
        if (!module.isEmpty()) {
            int[] colors = GunNbtColorAccess.getColors(module, ModuleItem.getModuleId(module));
            if (colors != null && colors.length >= 3) return colors[region];
        }
        if (def != null && def.defaultColors != null && def.defaultColors.length >= 3) {
            return def.defaultColors[region];
        }
        return 0xFF8B8B8B;
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
        if (delta > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        } else {
            scrollOffset = Math.min(maxScroll(), scrollOffset + 1);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // recipe list selection
        for (int row = 0; row < LIST_ROWS; row++) {
            int index = scrollOffset + row;
            if (index >= recipes.size()) break;
            int y = LIST_TOP + row * LIST_ROW_HEIGHT;
            if (mouseX >= this.leftPos + LIST_X && mouseX < this.leftPos + LIST_X + LIST_W
                    && mouseY >= this.topPos + y - 3 && mouseY < this.topPos + y + 12) {
                selectedRecipe = index;
                return true;
            }
        }
        // region rows
        ModuleWorkbenchBlockEntity be = this.menu.getBlockEntity();
        if (be.hasModule()) {
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
            for (int i = 0; i < dev.ignis.createpneumatictacticals.menu.DyePalette.SIZE; i++) {
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
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
