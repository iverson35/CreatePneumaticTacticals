package dev.ignis.createpneumatictacticals.mixin;

import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.gun.GunAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skeletons swap between bow and melee goals in reassessWeaponGoal(): the
 * main hand is a bow -> bow goal; anything else -> melee goal. Our guns
 * fall into "anything else", turning a gun-wielding skeleton into a
 * melee pushover.
 *
 * <p>This mixin intercepts the tail of reassessWeaponGoal(): when the main
 * hand holds a GunItem, the vanilla-added goals (bow OR melee, whichever
 * the tail just added) are removed and a GunAttackGoal is installed at the
 * same priority 4. Idempotent across repeated reassess calls (remove
 * first, add later); no-op for vanilla skeletons.
 */
@Mixin(AbstractSkeleton.class)
public abstract class AbstractSkeletonMixin {

    @Shadow
    private RangedBowAttackGoal<AbstractSkeleton> bowGoal;
    @Shadow
    private MeleeAttackGoal meleeGoal;

    @Unique
    private final GunAttackGoal cpt$gunGoal = new GunAttackGoal((AbstractSkeleton) (Object) this, 1.0, 20, 15.0F);

    @Inject(method = "reassessWeaponGoal", at = @At("TAIL"))
    private void cpt$installGunGoal(CallbackInfo ci) {
        AbstractSkeleton self = (AbstractSkeleton) (Object) this;
        if (self.level().isClientSide()) return; // server-authoritative AI
        boolean holdingGun = self.getMainHandItem().getItem() instanceof GunItem;
        // guaranteed drop of the gun on death: drop chance > 1.0 skips the
        // vanilla dice roll in dropAllDeathLoot. Gated to skeletons holding
        // our guns; restored to vanilla 0.085 whenever the gun leaves the
        // main hand so the boost never leaks to a later bow or melee weapon.
        if (holdingGun) {
            self.setDropChance(EquipmentSlot.MAINHAND, 2.0F);
            self.goalSelector.removeGoal(this.bowGoal);
            self.goalSelector.removeGoal(this.meleeGoal);
            self.goalSelector.removeGoal(this.cpt$gunGoal);
            self.goalSelector.addGoal(4, this.cpt$gunGoal);
        } else {
            self.setDropChance(EquipmentSlot.MAINHAND, 0.085F);
        }
    }
}