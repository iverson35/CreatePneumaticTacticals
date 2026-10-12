package dev.ignis.createpneumatictacticals.block;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The plate item: four of them (one per {@link PegboardMaterial}) place the
 * same block, so the material has to ride in on the item — a plain
 * {@link BlockItem} would place the default state every time. Both the state
 * stamp and the name are therefore per material.
 */
public class WeaponPegboardBlockItem extends BlockItem {

    private final PegboardMaterial material;

    public WeaponPegboardBlockItem(Block block, Properties properties, PegboardMaterial material) {
        super(block, properties);
        this.material = material;
    }

    /**
     * The one hook vanilla leaves open at placement time (its own
     * {@code updateBlockStateFromTag} is private): build the state the block
     * asks for, then stamp this item's material onto it.
     */
    @Override
    protected BlockState getPlacementState(BlockPlaceContext context) {
        BlockState state = super.getPlacementState(context);
        return state == null ? null : state.setValue(WeaponPegboardBlock.MATERIAL, this.material);
    }

    /** four items share one block, so the block id cannot name them */
    @Override
    public String getDescriptionId() {
        return this.material.langKey();
    }
}
