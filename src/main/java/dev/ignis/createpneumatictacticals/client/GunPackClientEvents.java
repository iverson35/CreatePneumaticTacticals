package dev.ignis.createpneumatictacticals.client;

import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.gunpack.GunPacks;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client side of the gunpack system: every installed pack's {@code assets/}
 * directory is injected as a required built-in resource pack (so
 * geo/textures/animations/lang load like normal mod assets and hot-reload
 * with F3+T), and the same F3+T also re-reads module definitions.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GunPackClientEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private GunPackClientEvents() {}

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        for (Path packDir : GunPacks.installedPacks()) {
            Path assets = packDir.resolve("assets");
            if (!Files.isDirectory(assets)) continue;
            String name = packDir.getFileName().toString();
            // PathPackResources prepends the pack-type directory ("assets")
            // itself — the root must be the pack dir, not its assets folder.
            // Pack.create (not readMetaAndCreate): gunpacks carry no
            // pack.mcmeta, and readMetaAndCreate returns null without one
            event.addRepositorySource(consumer -> consumer.accept(Pack.create(
                    "gunpack/" + name,
                    Component.literal("Gunpack: " + name),
                    true, // required: always enabled, not user-toggleable
                    id -> new PathPackResources(id, packDir, true),
                    new Pack.Info(Component.literal("Gunpack " + name), 15,
                            net.minecraft.world.flag.FeatureFlagSet.of()),
                    PackType.CLIENT_RESOURCES,
                    Pack.Position.TOP,
                    true,
                    PackSource.BUILT_IN)));
        }
    }

    /** F3+T reloads pack assets anyway; piggyback module definition reload on it */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager -> {
            ModuleManager.loadFromGunPacks();
            // probe: is the injected pack actually visible to the resource manager?
            LOGGER.info("gunpack resource probe: mak_1_receiver.geo.json = {}",
                    resourceManager.getResource(
                            new ResourceLocation(CreatePneumaticTacticals.MODID, "geo/gun/mak_1_receiver.geo.json"))
                            .isPresent());
        });
    }
}
