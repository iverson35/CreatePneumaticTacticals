package dev.ignis.createpneumatictacticals.gunpack;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.PaintDefinition;
import dev.ignis.createpneumatictacticals.module.PaintManager;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /cpt reload} — re-read module + paint definitions from the
 * installed gunpacks (server side). Datapack content (recipes, ammo
 * extensions) still reloads via vanilla /reload; client-side data reloads
 * with F3+T.
 * <p>
 * {@code /cpt paint <id>} — paint (涂装) testing convenience: cycles the
 * held module item's Paint NBT to the given paint id (or clears it with
 * {@code /cpt paint clear}). The item is the sole paint authority; an
 * already-assembled gun only picks a paint up when the module travels back
 * out and is reinstalled.
 */
@Mod.EventBusSubscriber
public final class CptCommand {

    private CptCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cpt")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("reload").executes(ctx -> {
                    ModuleManager.loadFromGunPacks();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Reloaded " + ModuleManager.all().size() + " module definitions from gunpacks"), true);
                    return 1;
                }))
                .then(Commands.literal("paint")
                        .then(Commands.literal("clear").executes(ctx ->
                                paint(ctx.getSource().getPlayerOrException(), null)))
                        .then(Commands.argument("paint", ResourceLocationArgument.id()).executes(ctx -> {
                            ResourceLocation id = ResourceLocationArgument.getId(ctx, "paint");
                            return paint(ctx.getSource().getPlayerOrException(), id);
                        }))));
    }

    /** Writes (or clears) the paint id on the held module item. */
    private static int paint(ServerPlayer player, ResourceLocation paintId) {
        ItemStack held = player.getMainHandItem();
        ModuleDefinition def = ModuleManager.definitionOf(held);
        if (def == null) {
            player.sendSystemMessage(Component.literal("Hold a gun module in your main hand"));
            return 0;
        }
        if (paintId == null) {
            ModuleItem.setPaint(held, null);
            player.sendSystemMessage(Component.literal("Cleared paint from " + def.id));
            return 1;
        }
        PaintDefinition paint = PaintManager.get(paintId);
        if (paint == null) {
            player.sendSystemMessage(Component.literal("Unknown paint: " + paintId));
            return 0;
        }
        if (!paint.appliesTo(def.id)) {
            player.sendSystemMessage(Component.literal("Paint " + paintId + " does not apply to " + def.id));
            return 0;
        }
        ModuleItem.setPaint(held, paintId);
        player.sendSystemMessage(Component.literal("Painted " + def.id + " with " + paintId));
        return 1;
    }
}