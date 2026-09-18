package dev.ignis.createpneumatictacticals;

import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.block.ModBlocks;
import dev.ignis.createpneumatictacticals.item.ModItems;
import dev.ignis.createpneumatictacticals.menu.CptMenuTypes;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.recipe.ModRecipes;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(CreatePneumaticTacticals.MODID)
public class CreatePneumaticTacticals {
    public static final String MODID = "createpneumatictacticals";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CreatePneumaticTacticals() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.register(modBus);
        ModBlocks.register(modBus);
        CptMenuTypes.register(modBus);
        ModRecipes.register(modBus);
        CptNetwork.init();
    }
}