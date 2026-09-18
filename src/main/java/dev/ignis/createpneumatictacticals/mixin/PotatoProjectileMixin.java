package dev.ignis.createpneumatictacticals.mixin;

import com.simibubi.create.content.equipment.potatoCannon.PotatoProjectileEntity;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.HitConfirmPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hitmarker trigger: gun-fired potato projectiles carry the
 * {@code cpt_gunshot} persistent-data flag (set in GunFireHandler); when one
 * hits a living entity server-side, the shooter gets a HitConfirmPacket.
 */
@Mixin(PotatoProjectileEntity.class)
public abstract class PotatoProjectileMixin {

    @Inject(method = "onHitEntity", at = @At("HEAD"))
    private void createpneumatictacticals$hitConfirm(EntityHitResult ray, CallbackInfo ci) {
        PotatoProjectileEntity self = (PotatoProjectileEntity) (Object) this;
        if (self.level().isClientSide()) return;
        if (!self.getPersistentData().getBoolean("cpt_gunshot")) return;
        if (!(ray.getEntity() instanceof LivingEntity)) return;
        Entity owner = self.getOwner();
        if (owner instanceof ServerPlayer sp) {
            CptNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), new HitConfirmPacket());
        }
    }
}
