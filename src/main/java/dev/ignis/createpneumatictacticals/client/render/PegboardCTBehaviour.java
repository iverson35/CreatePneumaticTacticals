package dev.ignis.createpneumatictacticals.client.render;

import com.simibubi.create.foundation.block.connected.AllCTTypes;
import com.simibubi.create.foundation.block.connected.CTModel;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTSpriteShifter;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.ModBlocks;
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

import java.util.Map;

/**
 * Connected texture for the pegboard's FRONT face: the frame is dropped on
 * every edge that borders another plate of the same {@link PegboardPanel},
 * so a rectangle of plates reads as one board with a frame around it.
 *
 * <p>Connection follows the merged rectangle, not plain adjacency: a plate
 * that neighbours another one but falls OUTSIDE the rectangle keeps its own
 * full frame, which is what separates the merged rectangle from the plate
 * next to it. Create's {@link AllCTTypes#RECTANGLE} supplies the 4x4 atlas
 * cell lookup ({@code x + y * 4} for left/right x up/down), its
 * {@link CTModel} the per-face index and the UV rewrite.
 */
public final class PegboardCTBehaviour extends ConnectedTextureBehaviour.Base {

    /** 16x16 base tile, 64x64 atlas of 4x4 cells */
    private static final CTSpriteShiftEntry SHIFT = CTSpriteShifter.getCT(AllCTTypes.RECTANGLE,
            new ResourceLocation(CreatePneumaticTacticals.MODID, "block/weapon_pegboard"),
            new ResourceLocation(CreatePneumaticTacticals.MODID, "block/weapon_pegboard_connected"));

    public static final PegboardCTBehaviour INSTANCE = new PegboardCTBehaviour();

    private PegboardCTBehaviour() {
    }

    @Nullable
    @Override
    public CTSpriteShiftEntry getShift(BlockState state, Direction direction, @Nullable TextureAtlasSprite sprite) {
        if (!(state.getBlock() instanceof WeaponPegboardBlock)) return null;
        // front only: the 1 px rim keeps the plain edge texture
        return direction == state.getValue(WeaponPegboardBlock.FACING) ? SHIFT : null;
    }

    @Override
    public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter reader, BlockPos pos,
                              BlockPos otherPos, Direction face) {
        if (!(state.getBlock() instanceof WeaponPegboardBlock)
                || !(other.getBlock() instanceof WeaponPegboardBlock)) {
            return false;
        }
        Direction facing = state.getValue(WeaponPegboardBlock.FACING);
        if (other.getValue(WeaponPegboardBlock.FACING) != facing) return false;
        Direction.Axis uAxis = PegboardPanel.uAxis(facing);
        PegboardPanel.Panel panel = PegboardPanel.panelOf(pos, facing, candidate -> {
            BlockState candidateState = reader.getBlockState(candidate);
            return candidateState.getBlock() == state.getBlock()
                    && candidateState.getValue(WeaponPegboardBlock.FACING) == facing;
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
