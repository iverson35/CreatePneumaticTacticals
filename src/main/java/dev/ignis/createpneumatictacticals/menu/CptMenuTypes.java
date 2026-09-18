package dev.ignis.createpneumatictacticals.menu;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.entity.ModuleWorkbenchBlockEntity;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Menu (container screen) registrations.
 */
public final class CptMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<MenuType<ModuleWorkbenchMenu>> MODULE_WORKBENCH =
            MENUS.register("module_workbench", () -> IForgeMenuType.create(
                    (id, inv, buf) -> {
                        // BE is looked up client-side via extra data written by
                        // NetworkHooks.openScreen(player, provider, pos).
                        var pos = buf.readBlockPos();
                        if (inv.player.level().getBlockEntity(pos)
                                instanceof ModuleWorkbenchBlockEntity be) {
                            return new ModuleWorkbenchMenu(id, inv, be);
                        }
                        return null;
                    }));

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }

    private CptMenuTypes() {
    }
}