package dev.ignis.createpneumatictacticals.network;

import dev.ignis.createpneumatictacticals.menu.ModuleWorkbenchMenu;
import dev.ignis.createpneumatictacticals.recipe.ModuleCraftingRecipe;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: workbench actions from the GUI.
 * Action CRAFT: payload is the selected recipe id; the server resolves it from
 * the RecipeManager and calls menu.craft(player, recipe) after validation.
 * Action SET_REGION / SET_COLOR: update pending dye selection in the BE.
 * Action DYE_CONFIRM: server executes the dye (menu.clickMenuButton).
 */
public class WorkbenchActionPacket {

    public enum Action {
        CRAFT, SET_REGION, SET_COLOR, DYE_CONFIRM
    }

    public final Action action;
    /** recipe id for CRAFT; region index (0-2) for SET_REGION; color index for SET_COLOR */
    public final ResourceLocation recipeId;
    public final int value;

    public WorkbenchActionPacket(Action action, ResourceLocation recipeId, int value) {
        this.action = action;
        this.recipeId = recipeId;
        this.value = value;
    }

    public static void encode(WorkbenchActionPacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.action.ordinal());
        boolean hasId = msg.recipeId != null;
        buf.writeBoolean(hasId);
        if (hasId) buf.writeResourceLocation(msg.recipeId);
        buf.writeVarInt(msg.value);
    }

    public static WorkbenchActionPacket decode(FriendlyByteBuf buf) {
        Action[] values = Action.values();
        Action action = values[Math.floorMod(buf.readByte(), values.length)];
        ResourceLocation id = buf.readBoolean() ? buf.readResourceLocation() : null;
        int value = buf.readVarInt();
        return new WorkbenchActionPacket(action, id, value);
    }

    public static void handle(WorkbenchActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            AbstractContainerMenu menu = player.containerMenu;
            if (!(menu instanceof ModuleWorkbenchMenu wb)) return;
            switch (msg.action) {
                case CRAFT -> {
                    if (msg.recipeId == null) return;
                    ModuleCraftingRecipe recipe = player.level().getRecipeManager()
                            .byKey(msg.recipeId)
                            .filter(r -> r instanceof ModuleCraftingRecipe
                                    && r.getType() == dev.ignis.createpneumatictacticals.recipe.ModRecipes.MODULE_CRAFTING.get())
                            .map(r -> (ModuleCraftingRecipe) r)
                            .orElse(null);
                    if (recipe != null) {
                        wb.craft(player, recipe);
                    }
                }
                case SET_REGION -> {
                    int v = Math.floorMod(msg.value, 4) - 1; // -1..2
                    wb.getBlockEntity().setRegion(v);
                }
                case SET_COLOR -> {
                    int v = Math.floorMod(msg.value, 17) - 1; // -1..15
                    wb.getBlockEntity().setChosenColor(v);
                }
                case DYE_CONFIRM -> wb.clickMenuButton(player, ModuleWorkbenchMenu.BTN_DYE_CONFIRM);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}