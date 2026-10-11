package dev.ignis.createpneumatictacticals.client.gui;

import dev.ignis.createpneumatictacticals.block.entity.WeaponModuleMachineBlockEntity;
import dev.ignis.createpneumatictacticals.menu.WeaponModuleMachineMenu;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.SetMachineRecipePacket;
import dev.ignis.createpneumatictacticals.recipe.ModRecipes;
import dev.ignis.createpneumatictacticals.recipe.ModuleCraftingRecipe;
import net.minecraft.ChatFormatting;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The machine tool's screen: pick which module to build. Filters are the
 * {@link ModuleType}s that actually occur among the module-crafting recipes;
 * clicking a row sends the recipe id to the server, which validates and stores
 * it on the block entity.
 *
 * <p>Purely a browser: the player's inventory is not involved, nothing is
 * crafted here. The machine's own current recipe is outlined (that state comes
 * from the block entity, so two players looking at the same machine agree), and
 * the screen stays open after a pick so the result of the click is visible.
 */
@OnlyIn(Dist.CLIENT)
public class WeaponModuleMachineScreen extends AbstractContainerScreen<WeaponModuleMachineMenu> {

    // --- geometry ---
    private static final int FILTER_X = 5, FILTER_Y = 16, FILTER_W = 40, FILTER_H = 13;
    private static final int FILTER_STEP = 41, FILTER_PER_ROW = 6;
    private static final int LIST_TOP = 62, LIST_ROW_HEIGHT = 13, LIST_ROWS = 5;
    private static final int LIST_X = 8, LIST_W = 222, LIST_ICON_X = 232;
    private static final int STATUS_Y = 136;

    private final List<ModuleCraftingRecipe> recipes = new ArrayList<>();
    private final List<Button> filterButtons = new ArrayList<>();
    /** filter of each button, index-aligned with {@link #filterButtons}; null = "all" */
    private final List<ModuleType> filterValues = new ArrayList<>();

    /** null = no filter (every recipe) */
    @org.jetbrains.annotations.Nullable
    private ModuleType filter;

    private int selectedRecipe = -1;
    private int scrollOffset = 0;

    public WeaponModuleMachineScreen(WeaponModuleMachineMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 256;
        this.imageHeight = 148;
    }

    @Override
    protected void init() {
        super.init();
        buildFilterButtons();
        refreshRecipes();
        // switching recipes is the only action: no slots, so no other widget
        addRenderableWidget(Button.builder(Component.literal("<"),
                        b -> scrollOffset = Math.max(0, scrollOffset - 1))
                .bounds(this.leftPos + 196, this.topPos + 45, 12, 12).build());
        addRenderableWidget(Button.builder(Component.literal(">"),
                        b -> scrollOffset = Math.min(maxScroll(), scrollOffset + 1))
                .bounds(this.leftPos + 238, this.topPos + 45, 12, 12).build());
    }

    /** "all" plus every type that occurs in the recipe table, in enum order */
    private void buildFilterButtons() {
        filterButtons.clear();
        filterValues.clear();
        addFilterButton(null, Component.translatable("gui.createpneumatictacticals.machine_filter_all"));
        Set<ModuleType> present = EnumSet.noneOf(ModuleType.class);
        for (ModuleCraftingRecipe recipe : allRecipes()) {
            ModuleDefinition def = ModuleManager.get(recipe.getModuleId());
            if (def != null) present.add(def.type);
        }
        for (ModuleType type : ModuleType.values()) {
            if (!present.contains(type)) continue;
            addFilterButton(type, Component.translatable(
                    "module_type." + dev.ignis.createpneumatictacticals.CreatePneumaticTacticals.MODID
                            + "." + type.getSerializedName()));
        }
    }

    private void addFilterButton(@org.jetbrains.annotations.Nullable ModuleType value, Component label) {
        int index = filterButtons.size();
        int x = FILTER_X + (index % FILTER_PER_ROW) * FILTER_STEP;
        int y = FILTER_Y + (index / FILTER_PER_ROW) * 15;
        Button button = this.addRenderableWidget(Button.builder(label, b -> {
            this.filter = value;
            this.scrollOffset = 0;
            refreshRecipes();
        }).bounds(this.leftPos + x, this.topPos + y, FILTER_W, FILTER_H).build());
        filterButtons.add(button);
        filterValues.add(value);
    }

    private void refreshRecipes() {
        recipes.clear();
        for (ModuleCraftingRecipe recipe : allRecipes()) {
            if (filter != null) {
                ModuleDefinition def = ModuleManager.get(recipe.getModuleId());
                if (def == null || def.type != filter) continue;
            }
            recipes.add(recipe);
        }
        scrollOffset = Math.min(scrollOffset, maxScroll());
        if (selectedRecipe >= recipes.size()) selectedRecipe = -1;
    }

    private List<ModuleCraftingRecipe> allRecipes() {
        if (this.minecraft == null || this.minecraft.level == null) return List.of();
        return this.minecraft.level.getRecipeManager().getAllRecipesFor(ModRecipes.MODULE_CRAFTING.get());
    }

    private int maxScroll() {
        return Math.max(0, recipes.size() - LIST_ROWS);
    }

    private WeaponModuleMachineBlockEntity machine() {
        return this.menu.getBlockEntity();
    }

    // --- rendering ---

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        gfx.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth,
                this.topPos + this.imageHeight, 0xFF2A2A2E);
        gfx.fill(this.leftPos + 1, this.topPos + 1, this.leftPos + this.imageWidth - 1,
                this.topPos + this.imageHeight - 1, 0xFF3A3A40);
        // recipe list panel
        gfx.fill(this.leftPos + 4, this.topPos + LIST_TOP - 8, this.leftPos + 252,
                this.topPos + LIST_TOP + LIST_ROWS * LIST_ROW_HEIGHT + 2, 0xFF232326);
    }

    /** drawn by hand: no player inventory is shown, so no vanilla labels apply */
    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.drawString(this.font, this.title, 6, 4, 0xFFFFFF, false);
        // filter row: outline the active filter
        for (int i = 0; i < filterButtons.size(); i++) {
            Button button = filterButtons.get(i);
            if (filterValues.get(i) == this.filter) {
                gfx.renderOutline(button.getX() - this.leftPos - 1, button.getY() - this.topPos - 1,
                        FILTER_W + 2, FILTER_H + 2, 0xFFD080);
            }
        }
        gfx.drawString(this.font,
                Component.translatable("gui.createpneumatictacticals.machine_recipes"), 8, 48, 0xFFD080, false);
        gfx.drawString(this.font, (scrollOffset + 1) + "/" + (maxScroll() + 1), 210, 48, 0xFF909090, false);

        ResourceLocation current = machine().getRecipeId();
        for (int row = 0; row < LIST_ROWS; row++) {
            int index = scrollOffset + row;
            if (index >= recipes.size()) break;
            ModuleCraftingRecipe recipe = recipes.get(index);
            int y = LIST_TOP + row * LIST_ROW_HEIGHT;
            if (index == selectedRecipe) {
                gfx.fill(LIST_X, y - 2, LIST_X + LIST_W, y + 11, 0x805A5A8A);
            }
            if (recipe.getId().equals(current)) {
                gfx.renderOutline(LIST_X - 1, y - 3, LIST_W + 2, LIST_ROW_HEIGHT, 0xFFD080);
            }
            gfx.drawString(this.font, trim(recipe.resultStack().getHoverName().getString(), 30),
                    12, y, 0xE0E0E0, false);
            gfx.renderItem(recipe.resultStack(), LIST_ICON_X, y - 2);
        }

        // status: which module this machine is set to, and whether it is running
        ModuleCraftingRecipe currentRecipe = machine().currentRecipe();
        Component status = Component.translatable("gui.createpneumatictacticals.machine_current",
                currentRecipe == null ? Component.literal("—") : currentRecipe.resultStack().getHoverName());
        gfx.drawString(this.font, status, 8, STATUS_Y, 0xE0E0E0, false);
        if (machine().isCrafting()) {
            gfx.drawString(this.font,
                    Component.translatable("gui.createpneumatictacticals.machine_working"),
                    150, STATUS_Y, 0xFF8080, false);
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);
        int hover = hoveredRow(mouseX, mouseY);
        if (hover >= 0 && hover < recipes.size()) {
            gfx.renderComponentTooltip(this.font, ingredientTooltip(recipes.get(hover)), mouseX, mouseY);
        }
        this.renderTooltip(gfx, mouseX, mouseY);
    }

    private List<Component> ingredientTooltip(ModuleCraftingRecipe recipe) {
        List<Component> lines = new ArrayList<>();
        lines.add(recipe.resultStack().getHoverName().copy().withStyle(ChatFormatting.AQUA));
        for (var ingredient : recipe.getIngredientList()) {
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length == 0) continue;
            lines.add(Component.literal("- ").append(stacks[0].getHoverName())
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int index = hoveredRow(mouseX, mouseY);
        if (index >= 0) {
            selectedRecipe = index;
            CptNetwork.CHANNEL.sendToServer(
                    new SetMachineRecipePacket(recipes.get(index).getId()));
            return true;
        }
        boolean consumed = super.mouseClicked(mouseX, mouseY, button);
        // a consumed widget click would keep drawing the focus outline forever
        this.setFocused(null);
        return consumed;
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

    private String trim(String s, int maxChars) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 1) + "…";
    }
}
