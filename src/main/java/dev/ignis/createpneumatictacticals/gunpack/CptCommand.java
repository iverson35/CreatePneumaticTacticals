package dev.ignis.createpneumatictacticals.gunpack;

import dev.ignis.createpneumatictacticals.module.ModuleManager;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * {@code /cpt reload} — re-read module definitions from the installed
 * gunpacks (server side). Datapack content (recipes, ammo extensions) still
 * reloads via vanilla /reload; client-side module data reloads with F3+T.
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
                })));
    }
}
