package dev.ignis.createpneumatictacticals.block;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

/**
 * A plate's material: which texture set its model and its connected texture
 * use. Carried as the {@code weapon_pegboard}'s {@code material} blockstate
 * property.
 *
 * <p>Materials do separate boards — plates of different materials stay
 * separate {@link PegboardPanel}s, each with its own frame, so a mixed wall
 * reads as a row of boards rather than one. The frame is drawn per plate from
 * each plate's own texture, which is what makes the material visible.
 *
 * <p>Stamped on placement by {@link WeaponPegboardBlockItem} and read back by
 * the block's drops, so a plate survives break / pick-up / place with its
 * material intact.
 */
public enum PegboardMaterial implements StringRepresentable {

    /** the plain wooden plate, the original texture set */
    WOOD("wood", "_wood"),
    ANDESITE("andesite", "_andesite"),
    BRASS("brass", "_brass"),
    CAST_IRON("cast_iron", "_cast_iron");

    private final String name;
    private final String suffix;

    PegboardMaterial(String name, String suffix) {
        this.name = name;
        this.suffix = suffix;
    }

    @Override
    public @NotNull String getSerializedName() {
        return this.name;
    }

    /**
     * Suffix of this material's texture names and item id:
     * {@code weapon_pegboard<suffix>} (the patterned board, whose connection
     * sheet is {@code weapon_pegboard_connected<suffix>}),
     * {@code weapon_pegboard_frame<suffix>} (the plain plate the board hangs
     * on: the sheet holes out at every border the board does not run across,
     * and this frame shows through it),
     * {@code weapon_pegboard_edge<suffix>} and
     * {@code weapon_pegboard_back<suffix>} — the single source both the item
     * registration and {@code PegboardCTBehaviour} read.
     */
    public String suffix() {
        return this.suffix;
    }

    /** translation key for this material's item and its blockstate */
    public String langKey() {
        return "block." + CreatePneumaticTacticals.MODID + ".weapon_pegboard" + this.suffix;
    }
}
