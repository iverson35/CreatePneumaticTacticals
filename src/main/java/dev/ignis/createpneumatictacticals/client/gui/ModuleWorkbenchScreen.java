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
 * Accessory workbench GUI. Left half: scrollable recipe list; right half:
 * dyeing (region 1/2/3 selectors + 16-color swatch grid + confirm).
 */
@OnlyIn(Dist.CLIENT)
public class ModuleWorkbenchScreen extends AbstractContainerScreen<ModuleWorkbenchMenu> {

    private static final ResourceLocation BG =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "textures/gui/module_workbench.png");

    private static final int COLOR_SWATCH_SIZE = 10;
    private static final int COLOR_SWATCH_GAP = 2;

    /** rows visible in the recipe list */
    private static final int LIST_ROWS = 5;
    private static final int LIST_ROW_HEIGHT = 20;

    private final List<ModuleCraftingRecipe> recipes = new ArrayList<>();
    private int selectedRecipe = -1;
    private int scrollOffset = 0;

    public ModuleWorkbenchScreen(ModuleWorkbenchMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 256;
        this.imageHeight = 186;
    }

    @Override
    protected void init() {
        super.init();
        refreshRecipes();
        // craft button
        addRenderableWidget(Button.builder(Component.translatable("gui.createpneumatictacticals.craft"),
                        b -> craftSelected())
                .bounds(this.leftPos + 10, this.topPos + 108, 60, 18)
                .build());
        // dye confirm button
        addRenderableWidget(Button.builder(Component.translatable("gui.createpneumatictacticals.dye"),
                        b -> dyeConfirm())
                .bounds(this.leftPos + 134, this.topPos + 148, 60, 18)
                .build());
        addRenderableWidget(Button.builder(Component.literal("<"),
                        b -> { scrollOffset = Math.max(0, scrollOffset - 1); })
                .bounds(this.leftPos + 8, this.topPos + 18, 12, 12)
                .build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                        b -> { scrollOffset = Math.min(maxScroll(), scrollOffset + 1); })
                .bounds(this.leftPos + 118, this.topPos + 18, 12, 12)
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
        // panel background (flat, no texture dependency for now)
        gfx.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth,
                this.topPos + this.imageHeight, 0xFF2A2A2E);
        gfx.fill(this.leftPos + 1, this.topPos + 1, this.leftPos + this.imageWidth - 1,
                this.topPos + this.imageHeight - 1, 0xFF3A3A40);
        // left panel (recipes)
        gfx.fill(this.leftPos + 4, this.topPos + 14, this.leftPos + 130, this.topPos + 106, 0xFF232326);
        // right panel (dye)
        gfx.fill(this.leftPos + 134, this.topPos + 14, this.leftPos + 252, this.topPos + 144, 0xFF232326);
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.drawString(this.font, this.title, 6, 4, 0xFFFFFF, false);

        // --- recipe list ---
        gfx.drawString(this.font,
                Component.translatable("gui.createpneumatictacticals.recipes"), 8, 7, 0xFFD080, false);
        for (int row = 0; row < LIST_ROWS; row++) {
            int index = scrollOffset + row;
            if (index >= recipes.size()) break;
            ModuleCraftingRecipe recipe = recipes.get(index);
            int y = 30 + row * LIST_ROW_HEIGHT;
            boolean selected = index == selectedRecipe;
            if (selected) {
                gfx.fill(this.leftPos + 6, this.topPos + y - 3, this.leftPos + 128, this.topPos + y + 15, 0x805A5A8A);
            }
            // highlight affordable recipes
            boolean canCraft = this.minecraft != null && this.minecraft.player != null
                    && this.menu.canCraft(this.minecraft.player, recipe);
            int textColor = canCraft ? 0x80FF80 : 0x909090;
            String label = moduleDisplayName(recipe);
            gfx.drawString(this.font, trim(label, 17), 24, y, textColor, false);
            ItemStack result = recipe.resultStack();
            gfx.renderItem(result, 108, y - 2);
        }

        // --- dye panel ---
        gfx.drawString(this.font,
                Component.translatable("gui.createpneumatictacticals.dyeing"), 134, 7, 0xFFD080, false);
        ModuleWorkbenchBlockEntity be = this.menu.getBlockEntity();
        ItemStack module = be.getDyeModule();
        gfx.renderItem(module, 134, 22);
        ModuleDefinition def = definitionOf(module);
        // region rows
        for (int r = 0; r < 3; r++) {
            int y = 22 + r * 14;
            gfx.drawString(this.font,
                    Component.translatable("gui.createpneumatictacticals.region", r + 1), 158, y + 2, 0xE0E0E0, false);
            int cur = currentColor(be, def, r);
            gfx.fill(this.leftPos + 236, this.topPos + y, this.leftPos + 246, this.topPos + y + 10, 0xFF000000 | cur);
            if (be.getRegion() == r) {
                gfx.renderOutline(this.leftPos + 235, this.topPos + y - 1, 12, 12, 0xFFFFFFFF);
            }
        }
        // 16-color swatch grid
        int gridX = 134, gridY = 68;
        for (int i = 0; i < dev.ignis.createpneumatictacticals.menu.DyePalette.SIZE; i++) {
            int col = i % 8, row = i / 8;
            int x = gridX + col * (COLOR_SWATCH_SIZE + 2);
            int y = gridY + row * (COLOR_SWATCH_SIZE + 2);
            gfx.fill(this.leftPos + x, this.topPos + y,
                    this.leftPos + x + COLOR_SWATCH_SIZE, this.topPos + y + COLOR_SWATCH_SIZE,
                    dev.ignis.createpneumatictacticals.menu.DyePalette.argbOf(i));
            if (be.getChosenColor() == i) {
                gfx.renderOutline(this.leftPos + x - 1, this.topPos + y - 1,
                        COLOR_SWATCH_SIZE + 2, COLOR_SWATCH_SIZE + 2, 0xFFFFFFFF);
            }
        }
        // status line
        String status = dyeStatus(be);
        if (!status.isEmpty()) {
            gfx.drawString(this.font, status, 134, 96, 0xFF6060, false);
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
        @SuppressWarnings("unused")
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
            int y = 30 + row * LIST_ROW_HEIGHT;
            if (mouseX >= this.leftPos + 6 && mouseX < this.leftPos + 128
                    && mouseY >= this.topPos + y - 3 && mouseY < this.topPos + y + 17) {
                selectedRecipe = index;
                return true;
            }
        }
        // region rows
        ModuleWorkbenchBlockEntity be = this.menu.getBlockEntity();
        if (be.hasModule()) {
            for (int r = 0; r < 3; r++) {
                int y = 22 + r * 14;
                if (mouseX >= this.leftPos + 236 && mouseX < this.leftPos + 246
                        && mouseY >= this.topPos + y && mouseY < this.topPos + y + 10) {
                    CptNetwork.CHANNEL.sendToServer(new WorkbenchActionPacket(
                            WorkbenchActionPacket.Action.SET_REGION, null, r));
                    be.setRegion(r);
                    return true;
                }
            }
            // color grid
            int gridX = 134, gridY = 68;
            for (int i = 0; i < dev.ignis.createpneumatictacticals.menu.DyePalette.SIZE; i++) {
                int col = i % 8, row = i / 8;
                int x = gridX + col * (COLOR_SWATCH_SIZE + 2);
                int y = gridY + row * (COLOR_SWATCH_SIZE + 2);
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