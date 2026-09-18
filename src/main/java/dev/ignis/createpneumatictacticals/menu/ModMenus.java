package dev.ignis.createpneumatictacticals.menu;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.ForgeRegistries;

/** Menu registration. */
public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<MenuType<GunWorkbenchMenu>> GUN_WORKBENCH =
            MENUS.register("gun_workbench", () -> IForgeMenuType.create(GunWorkbenchMenu::new));

    private ModMenus() {}

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}