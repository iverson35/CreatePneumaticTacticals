package dev.ignis.createpneumatictacticals.block;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

/**
 * Item form of the machine tool. The block itself renders through a block
 * entity renderer (GeckoLib, not a block model), so the item icon needs its
 * own {@code BlockEntityWithoutLevelRenderer} — same split as the module item.
 * Placement additionally restores the block entity payload (recipe, materials,
 * output, door state) through the vanilla {@code BlockEntityTag} path.
 */
public class WeaponModuleMachineBlockItem extends BlockItem {

    public WeaponModuleMachineBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                return dev.ignis.createpneumatictacticals.client.render.MachineItemRenderer.INSTANCE;
            }
        });
    }
}
