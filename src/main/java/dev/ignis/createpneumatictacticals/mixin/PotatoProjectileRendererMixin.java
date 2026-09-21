package dev.ignis.createpneumatictacticals.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.equipment.potatoCannon.PotatoProjectileEntity;
import com.simibubi.create.content.equipment.potatoCannon.PotatoProjectileRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two independent render scales, both visual only (hitbox, damage and sync
 * are untouched):
 * <ul>
 * <li>proximity: projectiles spawn a block in front of the camera and the
 * GROUND item render looms huge over the first blocks of flight, so the
 * scale ramps linearly from {@link #cpt$MIN_SCALE} at the muzzle back to full
 * size at {@link #cpt$FULL_SIZE_DISTANCE} — applies to ALL potato
 * projectiles, potato-cannon shots included</li>
 * <li>gun ammo: anything our guns fired carries the server-stamped
 * {@code cpt_gunshot} flag, which PotatoProjectileMixin mirrors through the
 * spawn packet (ForgeData itself never reaches the client) — those render at
 * {@link #cpt$GUN_SCALE}, multiplied on top of the proximity ramp</li>
 * </ul>
 * Both scales ride the same pivot, the item's visual center, so shrinking
 * never slides the projectile off the crosshair or off its hitbox.
 */
@Mixin(PotatoProjectileRenderer.class)
public abstract class PotatoProjectileRendererMixin {

    /** distance (blocks) at which the projectile renders at full size */
    @Unique
    private static final float cpt$FULL_SIZE_DISTANCE = 4.0f;
    @Unique
    private static final float cpt$MIN_SCALE = 0.25f;
    /** gun ammo renders at 25% of its size (a 75% shrink) */
    @Unique
    private static final float cpt$GUN_SCALE = 0.25f;
    /** true between the HEAD push and the TAIL pop of one render call */
    @Unique
    private static boolean cpt$shrunk = false;

    // remap = false + explicit descriptor: Create's typed override keeps its
    // official name in the release jar (only the compiler bridge m_7392_ is
    // SRG-renamed), and the descriptor excludes that bridge in dev
    @Inject(method = "render(Lcom/simibubi/create/content/equipment/potatoCannon/PotatoProjectileEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", remap = false, at = @At("HEAD"))
    private void cpt$shrinkNearby(PotatoProjectileEntity entity, float yaw, float pt, PoseStack ms,
                                  MultiBufferSource buffer, int light, CallbackInfo ci) {
        // mirror Create's own early return: empty item never pushes, so the
        // TAIL pop below (only on the fall-through path) stays balanced
        if (entity.getItem().isEmpty()) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double dist = cam.distanceTo(entity.getPosition(pt));
        float scale = Mth.clamp((float) (dist / cpt$FULL_SIZE_DISTANCE), cpt$MIN_SCALE, 1.0f);
        // gun ammo is small by design: a flat 75% shrink on top of the ramp,
        // so the visible size tracks the 0.25 hitbox instead of a whole item
        if (entity.getPersistentData().getBoolean("cpt_gunshot")) scale *= cpt$GUN_SCALE;
        if (scale >= 1.0f) return;
        // pivot both scales at the rendered item's visual center: Create
        // translates to bbHeight/2 - 1/8, then the GROUND display context
        // adds its own +2/16 y offset for generated item models — net
        // center = bounding box center. Scaling around the entity origin
        // instead would slide the item up/down as it shrinks or grows, so
        // the 75% gun-ammo shrink pans out as a pure size change.
        float py = (float) (entity.getBoundingBox().getYsize() / 2.0);
        ms.pushPose();
        ms.translate(0f, py, 0f);
        ms.scale(scale, scale, scale);
        ms.translate(0f, -py, 0f);
        cpt$shrunk = true;
    }

    @Inject(method = "render(Lcom/simibubi/create/content/equipment/potatoCannon/PotatoProjectileEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V", remap = false, at = @At("TAIL"))
    private void cpt$popShrink(PotatoProjectileEntity entity, float yaw, float pt, PoseStack ms,
                               MultiBufferSource buffer, int light, CallbackInfo ci) {
        if (cpt$shrunk) {
            ms.popPose();
            cpt$shrunk = false;
        }
    }
}
