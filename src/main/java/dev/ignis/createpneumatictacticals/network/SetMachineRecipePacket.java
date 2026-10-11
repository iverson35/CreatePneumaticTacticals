package dev.ignis.createpneumatictacticals.network;

import dev.ignis.createpneumatictacticals.block.entity.WeaponModuleMachineBlockEntity;
import dev.ignis.createpneumatictacticals.menu.WeaponModuleMachineMenu;
import dev.ignis.createpneumatictacticals.recipe.ModRecipes;
import dev.ignis.createpneumatictacticals.recipe.ModuleCraftingRecipe;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S: the recipe picked in the machine's screen. The client only browses and
 * sends an id — the server re-resolves it from its own recipe manager (so a
 * client-side recipe table cannot inject anything) and refuses the request
 * unless the player still has this machine's menu open and is standing within
 * the machine's reach.
 */
public final class SetMachineRecipePacket {

    public final ResourceLocation recipeId;

    public SetMachineRecipePacket(ResourceLocation recipeId) {
        this.recipeId = recipeId;
    }

    public static void encode(SetMachineRecipePacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.recipeId);
    }

    public static SetMachineRecipePacket decode(FriendlyByteBuf buf) {
        return new SetMachineRecipePacket(buf.readResourceLocation());
    }

    public static void handle(SetMachineRecipePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            // an open menu means this player is really looking at this machine
            if (!(player.containerMenu instanceof WeaponModuleMachineMenu menu)) return;
            WeaponModuleMachineBlockEntity machine = menu.getBlockEntity();
            if (!machine.stillValid(player)) return;
            ModuleCraftingRecipe recipe = player.level().getRecipeManager()
                    .byKey(msg.recipeId)
                    .filter(r -> r instanceof ModuleCraftingRecipe
                            && r.getType() == ModRecipes.MODULE_CRAFTING.get())
                    .map(r -> (ModuleCraftingRecipe) r)
                    .orElse(null);
            if (recipe != null) {
                machine.setRecipe(recipe);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
