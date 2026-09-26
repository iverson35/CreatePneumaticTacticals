package dev.ignis.createpneumatictacticals.gunpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
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
 * <p>The mod ships the default pack as jar resources under {@code gunpack_defaults/}
 * (gradle packages the repo-root {@code gunpacks/} folder there). Every file found
 * under it is extracted on first launch — never overwritten afterwards.
 */
public final class GunPacks {

    public static final Path ROOT = FMLPaths.GAMEDIR.get().resolve("gunpacks");

    private static final Logger LOGGER = LogUtils.getLogger();

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

    /**
     * Relative paths of every file bundled under {@code /gunpack_defaults/} in the
     * mod's own resources — the jar in production, the resources directory in dev.
     * Gradle packages the whole repo-root {@code gunpacks/} folder, so scanning
     * keeps that folder the single source of truth: adding a file there is enough,
     * nothing to register here.
     */
    private static List<String> bundledDefaultFiles() {
        List<String> files = new ArrayList<>();
        URL url = GunPacks.class.getResource("/gunpack_defaults");
        if (url == null) {
            LOGGER.error("Built-in gunpack defaults are not on the classpath");
            return files;
        }
        try {
            if ("jar".equals(url.getProtocol())) {
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                // no cache: getJarFile() then hands back a private handle we may close
                connection.setUseCaches(false);
                try (JarFile jar = connection.getJarFile()) {
                    String prefix = "gunpack_defaults/";
                    jar.stream()
                            .map(JarEntry::getName)
                            .filter(name -> name.startsWith(prefix) && !name.endsWith("/"))
                            .map(name -> name.substring(prefix.length()))
                            .sorted()
                            .forEach(files::add);
                }
            } else {
                // file: exploded mod (dev); union: Forge's mod file system, which
                // is what a production mod jar is reached through. Both are real
                // NIO file systems, so the same walk covers them.
                Path root = Paths.get(url.toURI());
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                            .map(f -> root.relativize(f).toString().replace('\\', '/'))
                            .sorted()
                            .forEach(files::add);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to list built-in gunpack defaults ({} {}): {}",
                    url.getProtocol(), url, ex.getMessage());
        }
        return files;
    }

    private static void extractDefaults() {
        List<String> files = bundledDefaultFiles();
        if (files.isEmpty()) {
            LOGGER.error("No built-in gunpack defaults found — nothing extracted");
            return;
        }
        int extracted = 0;
        for (String rel : files) {
            Path target = ROOT.resolve(rel);
            if (Files.exists(target)) continue; // never clobber user edits
            try (InputStream in = GunPacks.class.getResourceAsStream("/gunpack_defaults/" + rel)) {
                if (in == null) {
                    LOGGER.error("Built-in gunpack file missing from jar: {}", rel);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.write(target, in.readAllBytes());
                extracted++;
            } catch (Exception ex) {
                LOGGER.error("Failed to extract gunpack default {}: {}", rel, ex.getMessage());
            }
        }
        if (extracted > 0) {
            LOGGER.info("Extracted {} built-in gunpack file(s) into {}", extracted, ROOT);
        }
    }

    /**
     * Reads a module's geo JSON text by the shared model-path convention
     * (geo/gun/<module-path>.geo.json under any installed pack's assets
     * namespace). Returns null when no pack provides the file — callers
     * treat that as "geometry unknown" and keep legacy defaults.
     */
    @Nullable
    public static String readGeoJson(net.minecraft.resources.ResourceLocation moduleId) {
        String rel = "assets/" + moduleId.getNamespace() + "/geo/gun/" + moduleId.getPath() + ".geo.json";
        for (Path pack : installedPacks()) {
            Path file = pack.resolve(rel);
            if (!Files.isRegularFile(file)) continue;
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                LOGGER.error("Failed to read geo {}: {}", file, ex.getMessage());
            }
        }
        return null;
    }
}
