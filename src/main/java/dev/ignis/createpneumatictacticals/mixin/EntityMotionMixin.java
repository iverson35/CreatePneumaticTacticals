package dev.ignis.createpneumatictacticals.mixin;

import com.simibubi.create.content.equipment.potatoCannon.PotatoProjectileEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side: keep the gun-shot replica's own velocity.
 *
 * <p>Vanilla syncs a tracked entity's velocity through
 * {@code ClientboundSetEntityMotionPacket}, whose constructor clamps EVERY
 * axis to +-3.9 before quantizing (x8000). The potato projectile is
 * trackDelta, so a flying gun shot gets that value re-applied continuously —
 * above 3.9 blocks/tick the dominant axis is truncated while the diagonal
 * components survive, which bends the visible trajectory toward the 45-degree
 * diagonals and drags the replica behind the server's position.
 *
 * <p>The replica runs the identical physics (see PotatoProjectileMixin), so
 * its own velocity is the exact one: dropping the clamped sync for
 * {@code cpt_gunshot} projectiles is what makes speeds above 3.9 render
 * truthfully. Positions still arrive through the move/teleport packets, so a
 * diverged replica is corrected as before.
 *
 * <p>Reach: Create registers the projectile with setUpdateInterval(20) and a
 * 4-chunk tracking range, so this packet only fires once per second — a bullet
 * fast enough to be truncated (>3.9 blocks/tick) has usually left the 64-block
 * tracking range long before tick 20, which is why the SPAWN packet is the
 * channel that actually bites (see PotatoProjectileMixin's cpt_vel_* restore).
 * This guard covers the remaining case: a shooter who keeps up with the bullet.
 */
@Mixin(Entity.class)
public abstract class EntityMotionMixin {

    @Inject(method = "lerpMotion", at = @At("HEAD"), cancellable = true)
    private void createpneumatictacticals$keepGunShotVelocity(double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof PotatoProjectileEntity projectile)) return;
        if (!projectile.level().isClientSide()) return;
        if (!projectile.getPersistentData().getBoolean("cpt_gunshot")) return;
        ci.cancel();
    }
}
