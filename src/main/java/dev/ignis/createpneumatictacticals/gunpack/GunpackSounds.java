package dev.ignis.createpneumatictacticals.gunpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Gunpack sound registration. A pack declares sounds in vanilla format at
 * {@code assets/<ns>/sounds.json}; every top-level key becomes a SoundEvent
 * {@code <ns>:<key>} registered here. The same sounds.json is served to the
 * client by the resource-pack injection, so the file is the single source of
 * truth for both the registry entry and the audio mapping — no code change
 * needed to add sounds. Scan runs at mod construction (gunpacks are already
 * extracted), before the registry event fires. Registry content: added or
 * removed event keys need a restart. The audio side (entry volume/pitch, the
 * ogg files) is client resource content: edits apply on F3+T.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GunpackSounds {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<ResourceLocation> DISCOVERED = new ArrayList<>();

    private GunpackSounds() {}

    /** scans installed packs for assets/&lt;ns&gt;/sounds.json; call after GunPacks.init */
    public static void scan() {
        for (Path pack : GunPacks.installedPacks()) {
            Path assets = pack.resolve("assets");
            if (!Files.isDirectory(assets)) continue;
            try (Stream<Path> namespaces = Files.list(assets)) {
                for (Path ns : namespaces.filter(Files::isDirectory).toList()) {
                    Path soundsJson = ns.resolve("sounds.json");
                    if (!Files.isRegularFile(soundsJson)) continue;
                    try {
                        JsonObject json = JsonParser.parseString(
                                Files.readString(soundsJson, StandardCharsets.UTF_8)).getAsJsonObject();
                        for (String key : json.keySet()) {
                            DISCOVERED.add(new ResourceLocation(ns.getFileName().toString(), key));
                        }
                    } catch (Exception ex) {
                        LOGGER.error("Failed to read sounds file {}: {}", soundsJson, ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                LOGGER.error("Failed to scan gunpack sounds in {}: {}", pack, ex.getMessage());
            }
        }
        if (!DISCOVERED.isEmpty()) {
            LOGGER.info("Gunpack sounds registered: {}", DISCOVERED);
        }
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.SOUND_EVENT)) return;
        event.register(Registries.SOUND_EVENT, helper -> {
            for (ResourceLocation id : DISCOVERED) {
                helper.register(id, SoundEvent.createVariableRangeEvent(id));
            }
        });
    }
}
