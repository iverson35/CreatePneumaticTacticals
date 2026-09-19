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
        dev.ignis.createpneumatictacticals.client.particle.ModParticles.register(modBus);
        // gunpacks before network: the channel protocol version is the
        // gunpack content hash
        dev.ignis.createpneumatictacticals.gunpack.GunPacks.init();
        dev.ignis.createpneumatictacticals.gunpack.GunpackSounds.scan();
        CptNetwork.init();
        modBus.addListener(this::commonSetup);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
    }

    private void commonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(dev.ignis.createpneumatictacticals.module.ModuleManager::loadFromGunPacks);
    }
}