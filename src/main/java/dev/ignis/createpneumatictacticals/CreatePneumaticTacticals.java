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
        dev.ignis.createpneumatictacticals.item.ModCreativeTabs.register(modBus);
        dev.ignis.createpneumatictacticals.sound.ModSoundEvents.register(modBus);
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
        event.enqueueWork(() -> {
            dev.ignis.createpneumatictacticals.module.ModuleManager.loadFromGunPacks();
            // Create refuses to take stress impacts for non-Create blocks through its
            // own config, so an addon registers straight into the registry. The unit is
            // SU per RPM: the machine is in the same class as Create's own drill / saw
            // / millstone (4.0), and the actual draw is 4 x |RPM|, computed by the network.
            com.simibubi.create.api.stress.BlockStressValues.IMPACTS.register(
                    dev.ignis.createpneumatictacticals.block.ModBlocks.WEAPON_MODULE_MACHINE_TOOL.get(),
                    () -> 4.0);
            // Contraption compatibility. A glued plate already moves and rotates
            // with any contraption: nothing tags it NON_MOVABLE or
            // RELOCATION_NOT_SUPPORTED, its PushReaction is NORMAL, it has a
            // collision shape and no loot-table-only handling - and the block
            // entity travels with it (Create stores saveWithFullMetadata plus
            // getUpdateTag, and rebuilds it from that NBT). What is missing
            // without this hook is the wall mount half: Create's own attached
            // blocks (ladders, wall torches, signs) come along when the block
            // they hang on moves, ours would be left floating. The check is
            // asked "is this block attached toward that neighbour", so answer
            // on the plate's back face only; an AttachedCheck never drags the
            // support in the other direction, and a glued plate still moves on
            // its own.
            com.simibubi.create.api.contraption.BlockMovementChecks.registerAttachedCheck(
                    (state, level, pos, direction) -> {
                        if (!(state.getBlock() instanceof dev.ignis.createpneumatictacticals.block.WeaponPegboardBlock))
                            return com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult.PASS;
                        return com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult.of(
                                direction == state.getValue(dev.ignis.createpneumatictacticals.block.WeaponPegboardBlock.FACING)
                                        .getOpposite());
                    });
        });
    }
}