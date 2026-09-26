package dev.ignis.createpneumatictacticals.compat.aw;

/**
 * Dist- and presence-safe entry points for the Armourer's Workshop item-skin
 * integration. Everything outside this package must talk to AW ONLY through
 * this class — it never mentions an AW type in its own signatures, so classes
 * that reference it load fine with AW absent, and the AW-touching
 * implementation ({@link AwSkins}) is only class-loaded after the
 * {@code ModList.get().isLoaded("armourers_workshop")} guard flips true
 * (JVM lazy resolution: an untriggered class reference cannot raise
 * NoClassDefFoundError).
 *
 * <p>Storage contract (mirrors the dye-color system exactly):
 * the module ITEM's {@code tag["ArmourersWorkshop"]} descriptor Compound is
 * the sole authority; the gun keeps a verbatim render copy under
 * {@code GunNbt.Skins[moduleId]} that is written on install and materialized
 * back onto the item on removal — see WorkbenchAssembler.
 */
public final class AwCompat {

    /** AW's mod id */
    public static final String MODID = "armourers_workshop";

    /** the NBT key AW stores its SkinDescriptor in on any skinned ItemStack
     *  (AW ModDataComponents.SKIN, 1.20.1 data-component-as-NBT) */
    public static final String TAG_AW_SKIN = "ArmourersWorkshop";

    private static boolean checked;
    private static boolean loaded;

    private AwCompat() {}

    /** True when Armourer's Workshop is in the mod list (resolved once). */
    public static boolean loaded() {
        if (!checked) {
            checked = true;
            try {
                loaded = net.minecraftforge.fml.ModList.get() != null
                        && net.minecraftforge.fml.ModList.get().isLoaded(MODID);
            } catch (Throwable t) {
                // ModList can be unavailable extremely early; treat as absent
                loaded = false;
            }
        }
        return loaded;
    }

    /**
     * Renders the module's AW skin at the current PoseStack position (the
     * mount locator's bone space, already applied by GunModulesLayer).
     * No-op returning false whenever AW is absent, the descriptor is missing,
     * or the skin is still baking — the caller then falls back to the regular
     * GeckoLib module render for that frame.
     *
     * @param descriptorTag the gun-side verbatim descriptor Compound
     *                      (GunNbt.getSkin; never parsed outside compat/aw)
     * @return true when the skin was actually drawn this frame
     */
    public static boolean renderModuleSkin(net.minecraft.nbt.CompoundTag descriptorTag,
                                           com.mojang.blaze3d.vertex.PoseStack poseStack,
                                           net.minecraft.client.renderer.MultiBufferSource bufferSource,
                                           long gunId, float partialTick, int packedLight, int packedOverlay) {
        if (!loaded() || descriptorTag == null) return false;
        try {
            return AwSkins.render(descriptorTag, poseStack, bufferSource, gunId, partialTick, packedLight, packedOverlay);
        } catch (Throwable t) {
            return false; // a broken skin must never take the gun render down
        }
    }

    /**
     * Per-frame animation sample for the module's skin: advances the gun's
     * AW animation manager so the drawn skin uses the current keyframe pose.
     * Must be called immediately before {@link #renderModuleSkin} on the same
     * descriptor. Safe no-op when AW is absent or the descriptor is missing.
     */
    public static void tickModuleSkin(net.minecraft.nbt.CompoundTag descriptorTag, long gunId, float partialTick) {
        if (!loaded() || descriptorTag == null) return;
        try {
            AwSkins.tick(descriptorTag, gunId, partialTick);
        } catch (Throwable ignored) {
        }
    }

    /** Broadcasts a gun state animation ("fire" / "reload" / "reload_round" /
     *  "bolt") to the AW skins of every module on the given gun stack. Skins
     *  that don't define the animation stay silent (same semantics as
     *  GeckoLib's filterExisting). Speed mirrors the GeckoLib playback rate
     *  (reload/bolt run at reloadSpeed; fire at 1). Client thread only. */
    public static void onGunAnimation(long gunId, String name, double speed) {
        if (!loaded()) return;
        try {
            AwSkins.onGunAnimation(gunId, name, speed);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hard interrupt (slot switch / screen opened mid-reload): stops every
     * playing skin animation of this gun and clears the replay queue.
     */
    public static void interruptGunAnimations(long gunId) {
        if (!loaded()) return;
        try {
            AwSkins.interrupt(gunId);
        } catch (Throwable ignored) {
        }
    }

    /** Drops the animation state of a gun that left the world/hands (or any
     *  stale id) so the manager table can't grow unbounded. */
    public static void releaseGunAnimations(long gunId) {
        if (!loaded()) return;
        try {
            AwSkins.release(gunId);
        } catch (Throwable ignored) {
        }
    }
}