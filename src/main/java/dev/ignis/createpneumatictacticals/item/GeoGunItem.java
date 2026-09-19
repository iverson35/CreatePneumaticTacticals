package dev.ignis.createpneumatictacticals.item;

import dev.ignis.createpneumatictacticals.client.render.GunAnimations;
import dev.ignis.createpneumatictacticals.client.render.GunGeoModel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

/**
 * Gun item with GeckoLib rendering. Per-stack resources resolve from the
 * installed receiver definition (GunAssets/GunGeoModel). The renderer is a
 * plain GeoItemRenderer (BlockEntityWithoutLevelRenderer) bound via the
 * standard Forge IClientItemExtensions.getCustomRenderer hook.
 */
public class GeoGunItem extends GunItem implements GeoItem {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public GeoGunItem(Properties properties) {
        super(properties);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private dev.ignis.createpneumatictacticals.client.render.GunHandsAwareRenderer renderer;

            @Override
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new dev.ignis.createpneumatictacticals.client.render.GunHandsAwareRenderer(new GunGeoModel());
                }
                return renderer;
            }

            /**
             * Third-person base hold: crossbow-style two-hand pose. (Custom
             * ArmPose.create values are unusable on Forge 1.20.1: nothing
             * consumes the transformer and the vanilla poseRightArm switch
             * crashes on ordinals beyond the built-in 10 — ready poses are
             * applied via HumanoidModelMixin instead.)
             */
            @Override
            public net.minecraft.client.model.HumanoidModel.ArmPose getArmPose(
                    net.minecraft.world.entity.LivingEntity entity,
                    net.minecraft.world.InteractionHand hand, net.minecraft.world.item.ItemStack stack) {
                return net.minecraft.client.model.HumanoidModel.ArmPose.CROSSBOW_HOLD;
            }
        });
    }

    /**
     * Every gun stack gets a unique GeckoLibID (server-side, synced via NBT).
     * Without it GeoItem.getId falls back to Long.MAX_VALUE for ALL stacks:
     * every gun would share one animation manager, so two guns would play
     * each other's animations (driver/layer key module animations by this id).
     */
    @Override
    public void inventoryTick(net.minecraft.world.item.ItemStack stack, net.minecraft.world.level.Level level,
                              net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            GeoItem.getOrAssignId(stack, serverLevel);
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar registrar) {
        registrar.add(new AnimationController<>(this, "main", 5, state -> {
            // no idle authored -> stay silent instead of spamming the log
            net.minecraft.world.item.ItemStack stack =
                    state.getData(software.bernie.geckolib.constant.DataTickets.ITEMSTACK);
            if (stack == null) return software.bernie.geckolib.core.object.PlayState.STOP;
            String idle = GunAnimations.resolve(
                    dev.ignis.createpneumatictacticals.client.render.GunAssets
                            .forStack(stack).animation(), "idle");
            // no idle authored -> stay silent instead of spamming the log
            if (idle == null) return software.bernie.geckolib.core.object.PlayState.STOP;
            state.setAndContinue(RawAnimation.begin().thenLoop(idle));
            return PlayState.CONTINUE;
        }));
        // one-shot anim controller (fire/reload/bolt), driven by GunAnimationDriver.
        // GeckoLib 4: a STOP predicate cancels a forced setAnimation on the very
        // next process() — must return CONTINUE; the empty-queue guard still
        // stops the controller once the forced animation finishes.
        registrar.add(new AnimationController<>(this, dev.ignis.createpneumatictacticals.client.GunAnimationDriver.CONTROLLER, 2,
                state -> PlayState.CONTINUE));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}