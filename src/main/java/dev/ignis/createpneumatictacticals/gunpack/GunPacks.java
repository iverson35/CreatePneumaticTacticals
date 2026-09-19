package dev.ignis.createpneumatictacticals.gunpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Gunpacks (pointblank-style): directory-form packs installed on BOTH client
 * and server at {@code <gamedir>/gunpacks/<packId>/}:
 * <pre>
 *   gunpacks/mak-1/
 *     modules/*.json            module definitions (read on both sides)
 *     assets/&lt;ns&gt;/...       geo/textures/animations/lang (client resources,
 *                               injected as a built-in resource pack)
 * </pre>
 * <p>Module content is hashed at startup and used as the network protocol
 * version, so a client whose gunpack modules differ from the server's is
 * rejected by Forge's handshake before joining.
 * <p>The mod ships a built-in example pack ({@code gunpack_defaults/mak-1} in
 * the jar) which is extracted on first launch — never overwritten afterwards.
 */
public final class GunPacks {

    public static final Path ROOT = FMLPaths.GAMEDIR.get().resolve("gunpacks");

    private static final Logger LOGGER = LogUtils.getLogger();

    /** files of the built-in "default" pack, relative to jar {@code gunpack_defaults/}.
     *  Dev note: the canonical source is the repo-root {@code gunpacks/default/}
     *  folder, packaged into the jar by gradle (processResources). */
    private static final String[] DEFAULT_FILES = {
            "default/modules/mak_1_receiver.json",
            "default/modules/mak_1_ammo_20.json",
            "default/modules/mak_1_barrel_short.json",
            "default/modules/mak_1_cartridge_supply.json",
            "default/modules/mak_1_tactical_handguard.json",
            "default/modules/mak_1_suppressor.json",
            "default/modules/1vo_muzzle_brake_a.json",
            "default/modules/pica_grip_rvg.json",
            "default/modules/mak_1_wire_stock.json",
            "default/assets/createpneumatictacticals/geo/gun/mak_1_receiver.geo.json",
            "default/assets/createpneumatictacticals/geo/gun/mak_1_ammo_20.geo.json",
            "default/assets/createpneumatictacticals/geo/gun/mak_1_barrel_short.geo.json",
            "default/assets/createpneumatictacticals/geo/gun/mak_1_cartridge_supply.geo.json",
            "default/assets/createpneumatictacticals/geo/gun/mak_1_tactical_handguard.geo.json",
            "default/assets/createpneumatictacticals/geo/gun/mak_1_suppressor.geo.json",
            "default/assets/createpneumatictacticals/geo/gun/1vo_muzzle_brake_a.geo.json",
            "default/assets/createpneumatictacticals/geo/gun/pica_grip_rvg.geo.json",
            "default/assets/createpneumatictacticals/geo/gun/mak_1_wire_stock.geo.json",
            "default/assets/createpneumatictacticals/textures/gun/mak_1_receiver.png",
            "default/assets/createpneumatictacticals/textures/gun/mak_1_ammo_20.png",
            "default/assets/createpneumatictacticals/textures/gun/mak_1_barrel_short.png",
            "default/assets/createpneumatictacticals/textures/gun/mak_1_cartridge_supply.png",
            "default/assets/createpneumatictacticals/textures/gun/mak_1_tactical_handguard.png",
            "default/assets/createpneumatictacticals/textures/gun/mak_1_suppressor.png",
            "default/assets/createpneumatictacticals/textures/gun/1vo_muzzle_brake_a.png",
            "default/assets/createpneumatictacticals/textures/gun/pica_grip_rvg.png",
            "default/assets/createpneumatictacticals/textures/gun/mak_1_wire_stock.png",
            "default/assets/createpneumatictacticals/animations/gun/mak_1_receiver.animation.json",
            "default/assets/createpneumatictacticals/animations/gun/mak_1_ammo_20.animation.json",
            "default/assets/createpneumatictacticals/sounds.json",
            "default/assets/createpneumatictacticals/lang/zh_cn.json",
            "default/assets/createpneumatictacticals/lang/en_us.json",
    };

    private static String contentHash = "0";
    /** extract defaults + compute the module content hash. Both sides, mod construction. */
    public static void init() {
        extractDefaults();
        contentHash = computeHash();
        LOGGER.info("Gunpack root: {} (module content hash {})", ROOT.toAbsolutePath(), contentHash);
    }

    public static String contentHash() {
        return contentHash;
    }

    /** packs installed under {@code gunpacks/} (directories only) */
    public static List<Path> installedPacks() {
        List<Path> packs = new ArrayList<>();
        if (!Files.isDirectory(ROOT)) return packs;
        try (Stream<Path> stream = Files.list(ROOT)) {
            stream.filter(Files::isDirectory).sorted().forEach(packs::add);
        } catch (Exception ex) {
            LOGGER.error("Failed to list gunpacks: {}", ex.getMessage());
        }
        return packs;
    }

    /**
     * All module definitions from all installed packs, as {@code id -> json}.
     * The id comes from the JSON's {@code name} field, falling back to
     * {@code <modid>:<filename>}. First pack wins on collision.
     */
    public static Map<ResourceLocation, JsonObject> loadModuleJsons() {
        Map<ResourceLocation, JsonObject> out = new LinkedHashMap<>();
        for (Path pack : installedPacks()) {
            Path modules = pack.resolve("modules");
            if (!Files.isDirectory(modules)) continue;
            try (Stream<Path> stream = Files.list(modules)) {
                for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    try {
                        JsonObject json = JsonParser.parseString(
                                Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                        ResourceLocation id = json.has("name")
                                ? ResourceLocation.tryParse(json.get("name").getAsString())
                                : null;
                        if (id == null) {
                            String name = file.getFileName().toString().replace(".json", "");
                            id = new ResourceLocation(CreatePneumaticTacticals.MODID, name);
                        }
                        if (out.putIfAbsent(id, json) != null) {
                            LOGGER.warn("Module {} defined by multiple gunpacks; keeping the first", id);
                        }
                    } catch (Exception ex) {
                        LOGGER.error("Failed to read module file {}: {}", file, ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                LOGGER.error("Failed to scan gunpack {}: {}", pack, ex.getMessage());
            }
        }
        return out;
    }

    /** sha1 over every module file's path + content, order-independent */
    private static String computeHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            List<Path> files = new ArrayList<>();
            for (Path pack : installedPacks()) {
                Path modules = pack.resolve("modules");
                if (!Files.isDirectory(modules)) continue;
                try (Stream<Path> stream = Files.list(modules)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
                }
            }
            files.sort(Comparator.comparing(p -> ROOT.relativize(p).toString()));
            for (Path file : files) {
                digest.update(ROOT.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            LOGGER.error("Failed to hash gunpacks: {}", ex.getMessage());
            return "0";
        }
    }

    private static void extractDefaults() {
        for (String rel : DEFAULT_FILES) {
            Path target = ROOT.resolve(rel);
            if (Files.exists(target)) continue; // never clobber user edits
            try (InputStream in = GunPacks.class.getResourceAsStream("/gunpack_defaults/" + rel)) {
                if (in == null) {
                    LOGGER.error("Built-in gunpack file missing from jar: {}", rel);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.write(target, in.readAllBytes());
            } catch (Exception ex) {
                LOGGER.error("Failed to extract gunpack default {}: {}", rel, ex.getMessage());
            }
        }
    }
}
