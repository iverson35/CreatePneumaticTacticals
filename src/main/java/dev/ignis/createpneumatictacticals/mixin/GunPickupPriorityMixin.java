package dev.ignis.createpneumatictacticals.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.item.GunItem;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.item.ItemStack;

/**
 * Skeletons never pick up our guns: vanilla Mob.canReplaceCurrentItem only
 * compares within five known families (sword, bow, crossbow, armor, digger)
 * and a gun falls into the catch-all else -> false against any held item,
 * the spawn bow included.
 *
 * <p>Gun priority, gated to skeletons (other mobs keep vanilla behavior so
 * e.g. zombies don't run off with dropped guns): a gun replaces anything;
 * between two guns the one with more rounds in the magazine wins; a held
 * gun is never swapped for a non-gun.
 */
@Mixin(Mob.class)
public abstract class GunPickupPriorityMixin {

    @Inject(method = "canReplaceCurrentItem", at = @At("HEAD"), cancellable = true)
    private void cpt$gunPickupPriority(ItemStack candidate, ItemStack current,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (!((Mob) (Object) this instanceof AbstractSkeleton)) return;
        boolean candGun = candidate.getItem() instanceof GunItem;
        boolean currGun = current.getItem() instanceof GunItem;
        if (candGun && currGun) {
            cir.setReturnValue(GunNbt.getAmmoCount(candidate) > GunNbt.getAmmoCount(current));
        } else if (candGun) {
            cir.setReturnValue(true);
        } else if (currGun) {
            cir.setReturnValue(false);
        }
    }
}