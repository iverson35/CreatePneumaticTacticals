package dev.ignis.createpneumatictacticals.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Data-driven module paint (涂装) definition, loaded from
 * {@code gunpacks/<pack>/paints/*.json}. A paint swaps a module's BASE
 * texture for a pack-authored recolor variant; dye colors, the dye mask
 * and the glow mask all keep working — they hang off the FINAL texture id
 * ({@code <texture id>_dye.png} / {@code <texture id>_glowmask.png}), so
 * a painted texture provides its own companions by naming convention.
 *
 * <p>JSON schema (fields named to match the module schema, §gunpack guide):
 * <pre>
 * {
 *   "name": "createpneumatictacticals:desert_stripe",   // paint id
 *   "applies_to": ["REGEX=mak_1_barrel_+"]              // exact ids or REGEX=
 * }
 * </pre>
 * The painted texture is DERIVED, not declared:
 * {@code textures/gun/<module path>_<paint path>.png} in the module's own
 * namespace — same sibling convention as the dye/glow masks, so one naming
 * rule covers base, dye and glow art.
 */
public final class PaintDefinition {

    public final ResourceLocation id;
    /** module ids this paint may be applied to (exact list + REGEX= patterns) */
    public final List<ResourceLocation> appliesTo;
    public final List<java.util.regex.Pattern> regex;

    private PaintDefinition(ResourceLocation id, List<ResourceLocation> appliesTo,
                            List<java.util.regex.Pattern> regex) {
        this.id = id;
        this.appliesTo = List.copyOf(appliesTo);
        this.regex = List.copyOf(regex);
    }

    /** same membership grammar as module_affected values (find(), not matches()) */
    public boolean appliesTo(ResourceLocation moduleId) {
        if (appliesTo.contains(moduleId)) return true;
        String s = moduleId.toString();
        for (java.util.regex.Pattern p : regex) {
            if (p.matcher(s).find()) return true;
        }
        return false;
    }

    /**
     * The painted base texture for a module: the paint id's path is
     * appended to the module texture id's stem. A paint whose id lives in
     * another namespace still recolors the module's texture in the
     * MODULE's namespace (art sits next to the module art).
     */
    public ResourceLocation textureFor(ResourceLocation moduleId) {
        String path = moduleId.getPath();
        return new ResourceLocation(moduleId.getNamespace(),
                "textures/gun/" + path + "_" + id.getPath() + ".png");
    }

    public static PaintDefinition fromJson(ResourceLocation id, JsonObject json) {
        List<ResourceLocation> appliesTo = new ArrayList<>();
        List<java.util.regex.Pattern> regex = new ArrayList<>();
        if (!json.has("applies_to")) {
            throw new IllegalArgumentException("paint requires applies_to: " + id);
        }
        for (JsonElement el : json.getAsJsonArray("applies_to")) {
            String entry = GsonHelper.convertToString(el, "applies_to entry");
            if (entry.startsWith("REGEX=")) {
                regex.add(java.util.regex.Pattern.compile(entry.substring("REGEX=".length())));
            } else {
                ResourceLocation mid = ResourceLocation.tryParse(entry);
                if (mid != null) appliesTo.add(mid);
            }
        }
        if (appliesTo.isEmpty() && regex.isEmpty()) {
            throw new IllegalArgumentException("paint applies_to is empty: " + id);
        }
        return new PaintDefinition(id, appliesTo, regex);
    }
}