package dev.ignis.createpneumatictacticals.client.render;

import com.simibubi.create.foundation.block.connected.AllCTTypes;
import com.simibubi.create.foundation.block.connected.CTModel;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTSpriteShifter;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.ModBlocks;
import dev.ignis.createpneumatictacticals.block.PegboardMaterial;
import dev.ignis.createpneumatictacticals.block.PegboardPanel;
import dev.ignis.createpneumatictacticals.block.WeaponPegboardBlock;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Connected texture for the raised board's front, and the only CT'd quad of the
 * block. That front is the whole 16x16 face at the sheet's height, carrying the
 * plain board tile at uv 0..16 — the coordinates it had before the sheet was
 * raised — so its connection sheet is the tile's own, and a cell of it boards
 * over the 1 px border ring where this plate merges with its neighbour and
 * holes it out where it does not:
 *
 * <ul>
 * <li>merged border: the ring pixel becomes the tile's pattern one period
 *     further out, so the pattern keeps its phase across the seam — the two
 *     plates read as one board, flush, with no step and no seam line.
 * <li>any other border (no neighbour, other facing, other material, outside
 *     the merged rectangle): the ring pixel is alpha 0, so the plate 0.5 px
 *     behind shows its own frame there. That recess is what the raise looks
 *     like where the board ends.
 * </ul>
 *
 * <p>The sheet is a 0.5 px slab whose front covers the whole 16x16 face, so its
 * own box faces carry its thickness at the block boundary: at a seam the two
 * plates butt there and each sheet's end face is what a grazing view catches —
 * flush, no step, the board material running on. Where the board stops short of
 * the boundary, that edge is instead the separate 1 px inset box ({@code from
 * [1,1,12.5] to [15,15,13]}) of four rim faces, standing on the plate's frame.
 * Both boxes sample the tile's own edge lines, and one caveat comes with the
 * static model: at an unmerged border the slab's end faces still sit at the
 * block boundary next to the hole, a 0.5 px lip. Create's {@link CTModel} skips
 * every quad whose sprite is not the shift's source, and {@link #getShift}
 * answers for the facing direction only, so all of them keep what they were
 * authored with.
 *
 * <p>Connection follows the merged rectangle, not plain adjacency: a
 * same-facing, same-material plate that falls OUTSIDE the rectangle is
 * refused, so both plates keep the frame on the border between them and the
 * boards read as separate. Create's {@link AllCTTypes#RECTANGLE} supplies the
 * 4x4 atlas cell lookup ({@code x + y * 4} for left/right x up/down), its
 * {@link CTModel} the per-face index and the UV rewrite.
 *
 * <p>Each {@link PegboardMaterial} has its own shift pair: the plain board
 * tile ({@code weapon_pegboard<material>}) to the 64x64 sheet of the 16
 * connection variants ({@code weapon_pegboard_connected<material>}, generated
 * by {@code tools/make_pegboard_connected_atlas.py}). Materials are part of
 * the connection test: only plates of the same facing AND material merge, so a
 * brass plate next to an andesite one keeps its own full frame and the two
 * read as separate boards. The sheet has holes, so the block renders with
 * alpha test — see {@code ClientModEvents}.
 */
public final class PegboardCTBehaviour extends ConnectedTextureBehaviour.Base {

    /** per material: 16x16 plain board tile, 64x64 atlas of 4x4 connection variants */
    private static final Map<PegboardMaterial, CTSpriteShiftEntry> SHIFTS = buildShifts();

    private static Map<PegboardMaterial, CTSpriteShiftEntry> buildShifts() {
        Map<PegboardMaterial, CTSpriteShiftEntry> shifts = new EnumMap<>(PegboardMaterial.class);
        for (PegboardMaterial material : PegboardMaterial.values()) {
            shifts.put(material, CTSpriteShifter.getCT(AllCTTypes.RECTANGLE,
                    texture("weapon_pegboard" + material.suffix()),
                    texture("weapon_pegboard_connected" + material.suffix())));
        }
        return shifts;
    }

    private static ResourceLocation texture(String path) {
        return new ResourceLocation(CreatePneumaticTacticals.MODID, "block/" + path);
    }

    public static final PegboardCTBehaviour INSTANCE = new PegboardCTBehaviour();

    private PegboardCTBehaviour() {
    }

    @Nullable
    @Override
    public CTSpriteShiftEntry getShift(BlockState state, Direction direction, @Nullable TextureAtlasSprite sprite) {
        if (!(state.getBlock() instanceof WeaponPegboardBlock)) return null;
        // The raised sheet's front quad is the only CT'd quad: it is the only
        // one sampling the plain board tile on the facing side, and its atlas
        // is where a merged border becomes board and every other border becomes
        // the hole the plate's frame shows through. Create's CTModel skips any
        // quad whose sprite is not the shift's source (the plate's frame, the
        // item model) and no rim face asks for the facing, so they stay put.
        if (direction != state.getValue(WeaponPegboardBlock.FACING)) return null;
        return SHIFTS.get(state.getValue(WeaponPegboardBlock.MATERIAL));
    }

    @Override
    public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter reader, BlockPos pos,
                              BlockPos otherPos, Direction face) {
        if (!(state.getBlock() instanceof WeaponPegboardBlock)
                || !(other.getBlock() instanceof WeaponPegboardBlock)) {
            return false;
        }
        Direction facing = state.getValue(WeaponPegboardBlock.FACING);
        PegboardMaterial material = state.getValue(WeaponPegboardBlock.MATERIAL);
        if (other.getValue(WeaponPegboardBlock.FACING) != facing) return false;
        if (other.getValue(WeaponPegboardBlock.MATERIAL) != material) return false;
        Direction.Axis uAxis = PegboardPanel.uAxis(facing);
        PegboardPanel.Panel panel = PegboardPanel.panelOf(pos, facing, candidate -> {
            BlockState candidateState = reader.getBlockState(candidate);
            // same facing and material only: a board is one material throughout,
            // so a mixed wall reads as separate boards instead of one
            return candidateState.getBlock() instanceof WeaponPegboardBlock
                    && candidateState.getValue(WeaponPegboardBlock.FACING) == facing
                    && candidateState.getValue(WeaponPegboardBlock.MATERIAL) == material;
        });
        return panel.contains(PegboardPanel.u(otherPos, uAxis), otherPos.getY());
    }

    /**
     * Replaces every baked facing variant of the block with a CT wrapper.
     * Called from {@code ModelEvent.ModifyBakingResult}, alongside
     * {@link PodItemModel#wrap}; the item model is deliberately left alone.
     */
    public static void wrap(Map<ResourceLocation, BakedModel> models) {
        ResourceLocation blockId = new ResourceLocation(CreatePneumaticTacticals.MODID, "weapon_pegboard");
        for (BlockState state : ModBlocks.WEAPON_PEGBOARD.get().getStateDefinition().getPossibleStates()) {
            net.minecraft.client.resources.model.ModelResourceLocation location =
                    BlockModelShaper.stateToModelLocation(blockId, state);
            BakedModel baked = models.get(location);
            if (baked != null && !(baked instanceof CTModel)) {
                models.put(location, new CTModel(baked, INSTANCE));
            }
        }
    }
}
