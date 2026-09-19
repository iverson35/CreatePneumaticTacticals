package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.Config;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.FireRequestPacket;
import dev.ignis.createpneumatictacticals.network.ReloadResultPacket;
import dev.ignis.createpneumatictacticals.network.GunActionPacket;
import dev.ignis.createpneumatictacticals.module.FireMode;
import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client input loop: while a gun is held, left mouse fires (per fire mode),
 * R reloads, V cycles fire mode, O cycles ammo, X cycles aim stance. Fire
 * requests are sent to the server (server-authoritative); view recoil and
 * spread bloom are applied locally on the confirmed cadence.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class ClientGunInput {

    private static boolean wasFiring = false;
    /** ammo type resolved during tryFire; read by MuzzleSmoke for puff scaling */
    private static PotatoCannonProjectileType currentType;
    private static long lastLocalShotMs = 0;

    // --- client reload state machine: R starts it, completion sends the result packet ---
    private static boolean reloading = false;
    private static boolean reloadRoundMode = false;
    /** empty-magazine reload drives the third-person RELOADING_EMPTY pose */
    private static boolean reloadEmpty = false;
    private static long reloadEndMs = 0;
    private static long reloadBatchMs = 0;
    private static int reloadBatch = 0;
    private static ItemStack reloadingGun = ItemStack.EMPTY;

    /** reload ticks of the ammo behind the latest fire attempt; 0 = unknown */
    public static int currentAmmoReloadTicks() {
        return currentType == null ? 0 : currentType.reloadTicks();
    }

    private ClientGunInput() {}

    // (no LeftClickEmpty hook: it is not cancelable in 1.20.1; the air swing is
    // already suppressed item-side by GunItem.onEntitySwing)

    /** left click fires the gun; suppress block breaking + swing */
    @SubscribeEvent
    public static void onLeftClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
        if (event.getItemStack().getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        if (mc.screen != null) {
            // opening any screen mid-reload interrupts it (magazine: fails; round: batch lost)
            cancelReload();
            return;
        }

        ItemStack gun = player.getMainHandItem();
        boolean holdingGun = gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GeoGunItem;
        MuzzleClearance.tick(player, holdingGun);
        ReadyModel.tick(player, holdingGun);
        if (!holdingGun) {
            wasFiring = false;
            cancelReload();
            updatePoseBroadcast(false, gun);
            return;
        }

        GunStats stats = GunStats.ofGun(gun);

        // --- fire ---
        if (mc.options.keyAttack.isDown()) {
            if (reloading) {
                // firing is locked while reloading (no fire-to-cancel); mark the
                // click consumed so semi-auto doesn't fire on reload end
                wasFiring = true;
            } else {
                tryFire(player, gun, stats);
            }
        } else {
            wasFiring = false;
        }

        // --- reload state machine ---
        tickReload(player, gun, stats);
        if (ModKeybinds.RELOAD.consumeClick()) {
            startReload(player, gun, stats);
        }

        // --- auto reload: an empty gun tries to reload on its own (silent when
        // no ammo is selected or no pods are available — no actionbar spam) ---
        if (!reloading && stats.isComplete() && stats.feed != null
                && stats.feed.feedType != dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK
                && GunNbt.getAmmoCount(gun) <= 0) {
            String autoAmmoId = GunNbt.getAmmo(gun);
            if (autoAmmoId != null && !autoAmmoId.isEmpty()) {
                boolean cartridge = stats.supply != null && stats.supply.supplyType
                        == dev.ignis.createpneumatictacticals.module.SupplyType.CARTRIDGE;
                if (player.isCreative() || countMatchingPods(player, cartridge, autoAmmoId) > 0) {
                    startReload(player, gun, stats);
                }
            }
        }

        // --- state cycling ---
        if (ModKeybinds.FIRE_MODE.consumeClick()) {
            CptNetwork.CHANNEL.sendToServer(new GunActionPacket(GunActionPacket.Action.NEXT_FIRE_MODE));
        }
        if (ModKeybinds.CYCLE_AMMO.consumeClick()) {
            CptNetwork.CHANNEL.sendToServer(new GunActionPacket(GunActionPacket.Action.NEXT_AMMO));
        }
        if (ModKeybinds.AIM_STANCE.consumeClick()) {
            CptNetwork.CHANNEL.sendToServer(new GunActionPacket(GunActionPacket.Action.CYCLE_AIM_STANCE));
        }

        updatePoseBroadcast(true, gun);
    }

    // --- third-person pose sync: the owning client is the source of truth ---
    private static dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose lastSentPose =
            dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.HIP;

    private static void updatePoseBroadcast(boolean holdingGun, ItemStack gun) {
        dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose pose;
        if (!holdingGun) {
            pose = dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.HIP;
        } else if (reloading) {
            pose = reloadEmpty
                    ? dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.RELOADING_EMPTY
                    : dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.RELOADING;
        } else if (AimHandler.isAiming()) {
            pose = "tactical".equals(GunNbt.getAimStance(gun))
                    ? dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.TACTICAL
                    : dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.ADS;
        } else if (ReadyModel.isStowed()) {
            pose = ReadyModel.isHighReady()
                    ? dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.HIGH_READY
                    : dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.LOW_READY;
        } else {
            pose = dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.HIP;
        }
        if (pose != lastSentPose) {
            lastSentPose = pose;
            CptNetwork.CHANNEL.sendToServer(new dev.ignis.createpneumatictacticals.network.PoseUpdatePacket(pose));
        }
    }
    /** client reload in progress (drives the HUD "reloading" indicator) */
    public static boolean isReloading() {
        return reloading;
    }

    /** wall-clock ms of the last local shot (0 = never); ReadyModel reads
     *  it for the sprint-fire return-to-ready timing */
    public static long lastShotMs() {
        return lastLocalShotMs;
    }

    private static void tryFire(Player player, ItemStack gun, GunStats stats) {
        if (!stats.isComplete()) {
            feedback(player, "incomplete");
            return;
        }
        if (!ReadyModel.canFire()) return; // ready pose: gun not yet back in the firing stance
        if (MuzzleClearance.isBlocked()) return; // muzzle pressed into geometry
        FireMode mode = GunNbt.getFireMode(gun);
        long now = System.currentTimeMillis();

        boolean semi = mode == FireMode.SEMI;
        if (semi && wasFiring) return;

        // local rate limit for responsiveness; server validates authoritatively
        String ammoId = GunNbt.getAmmo(gun);
        if (ammoId == null || ammoId.isEmpty()) {
            feedback(player, "no_ammo_selected", ModKeybinds.CYCLE_AMMO);
            return;
        }
        if (stats.feed != null && stats.feed.feedType != dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK
                && GunNbt.getAmmoCount(gun) <= 0) {
            return; // empty magazine: silent (the HUD clip counter + auto reload say it)
        }
        AmmoExtension ext = AmmoExtension.get(ammoId);
        // mirror of the server gate: ammo reload_ticks / multiplier, in ms
        // (client lookups are best-effort — the server validates anyway)
        long interval;
        currentType = PotatoCannonProjectileType
                .getTypeForItem(player.level().registryAccess(),
                        AmmoExtension.contentItemFor(player.level().registryAccess(), ammoId))
                .map(ref -> ref.value()).orElse(null);
        if (currentType != null) {
            interval = (long) (50 * Math.max(1, currentType.reloadTicks() / stats.fireRateMultiplier));
        } else {
            interval = 50; // unknown type: 10/s fallthrough, server will gate
        }
        if (now - lastLocalShotMs < interval) return;
        if (reloading) return;

        // burst: single request per click for now (server sequences the burst)
        // click-time camera pose, INCLUDING the recoil view punch: the
        // crosshair is drawn at the punched camera center, so the bullet
        // must follow the punched direction (entity look alone ignores the
        // punch and lands beside the crosshair). This also kills the ~50ms
        // rotation-sync lag that put turned shots on the wrong side.
        float punchPitch = player.getXRot() - (float) dev.ignis.createpneumatictacticals.client.RecoilModel.pitchDegrees();
        float punchYaw = player.getYRot() - (float) dev.ignis.createpneumatictacticals.client.RecoilModel.yawDegrees();
        net.minecraft.world.phys.Vec3 punchDir = net.minecraft.world.phys.Vec3.directionFromRotation(punchPitch, punchYaw);
        CptNetwork.CHANNEL.sendToServer(new FireRequestPacket(
                player.getEyePosition(1.0f), punchDir));
        // instant local fire sound (default: potato-cannon FWOOMP); the
        // server broadcast excludes the shooter
        String soundId = stats.receiver.fireSound != null ? stats.receiver.fireSound : "create:fwoomp";
        var fireSoundEvent = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS
                .getValue(net.minecraft.resources.ResourceLocation.tryParse(soundId));
        if (fireSoundEvent != null) player.playSound(fireSoundEvent, 1.0f, 1.0f);
        lastLocalShotMs = now;
        wasFiring = true;

        // local feel: recoil + bloom + fire animation
        double recoilMult = stats.recoilMultiplier;
        boolean aiming = ModKeybinds.isAiming();
        RecoilModel.onShot(stats.receiver.baseRecoilPitch, stats.receiver.baseRecoilYaw, recoilMult, aiming,
                stats.recoilRecovery);
        SpreadModel.addBloom(ext);
        // muzzle smoke: purely client-side (never synced per-particle);
        // representative ammo item for the item puffs
        MuzzleSmoke.onFire(player, new ItemStack(
                AmmoExtension.contentItemFor(player.level().registryAccess(), ammoId)));
        GunAnimationDriver.onFire(gun);
    }

    /**
     * Actionbar hint for client-side fire rejection; key names resolve from the
     * player's actual keybinds, never hardcoded.
     */
    private static void feedback(Player player, String key, net.minecraft.client.KeyMapping... hints) {
        net.minecraft.network.chat.MutableComponent c = net.minecraft.network.chat.Component.translatable(
                "gui." + CreatePneumaticTacticals.MODID + ".fail." + key);
        if (hints.length > 0) {
            c.append(" (");
            for (int i = 0; i < hints.length; i++) {
                if (i > 0) c.append("/");
                c.append(hints[i].getTranslatedKeyMessage());
            }
            c.append(")");
        }
        player.displayClientMessage(c, true);
    }

    private static void startReload(Player player, ItemStack gun, GunStats stats) {
        if (reloading || !stats.isComplete() || stats.feed == null
                || stats.feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK) return;
        if (GunNbt.getAmmoCount(gun) >= stats.feed.clipSize) return; // already full
        String ammoId = GunNbt.getAmmo(gun);
        if (ammoId == null || ammoId.isEmpty()) {
            feedback(player, "no_ammo_selected", ModKeybinds.CYCLE_AMMO);
            return;
        }
        boolean cartridge = stats.supply != null && stats.supply.supplyType
                == dev.ignis.createpneumatictacticals.module.SupplyType.CARTRIDGE;
        if (!player.isCreative() && countMatchingPods(player, cartridge, ammoId) <= 0) {
            feedback(player, cartridge ? "no_pressurized_pod" : "no_pod");
            return;
        }
        reloadRoundMode = stats.feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.ROUND;
        // duration = receiver animation length (+ bolt when empty) / reload speed
        boolean empty = GunNbt.getAmmoCount(gun) <= 0;
        reloadEmpty = empty; // third-person choreography variant
        reloadBatchMs = dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .reloadBatchMs(gun, reloadRoundMode, empty, stats.reloadSpeed);
        reloadEndMs = System.currentTimeMillis() + reloadBatchMs;
        reloadBatch = reloadRoundMode ? Math.max(1, stats.feed.loadAmount) : stats.feed.clipSize;
        reloadingGun = gun;
        reloading = true;
        GunAnimationDriver.onReloadStart();
    }

    /** Inventory pods loadable into this gun (plain, or pressurized for cartridge supply). */
    public static int countMatchingPods(Player player, boolean cartridge, String ammoId) {
        net.minecraft.world.item.Item required = cartridge
                ? dev.ignis.createpneumatictacticals.item.ModItems.PRESSURIZED_POD.get()
                : dev.ignis.createpneumatictacticals.item.ModItems.POD.get();
        int n = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() != required) continue;
            var content = dev.ignis.createpneumatictacticals.item.PodItem.contentId(stack);
            if (content == null) continue;
            net.minecraft.world.item.Item item =
                    net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(content);
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            var typeRef = com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType
                    .getTypeForItem(player.level().registryAccess(), item);
            if (typeRef.isPresent()
                    && typeRef.get().unwrapKey().orElseThrow().location().toString().equals(ammoId)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    /**
     * Interruption semantics: magazine reload fails outright (no packet = no
     * ammo); round reload applies per-batch — each finished batch sends its own
     * packet, an interruption only loses the in-flight batch. A single R press
     * keeps loading round-by-round until the magazine is full; switching slots
     * or opening a screen interrupts it (firing is locked, NOT an interrupt).
     * The gun stack reference doubles as the "same gun" check: switching
     * slots / dropping / stowing replaces it.
     */
    private static void tickReload(Player player, ItemStack gun, GunStats stats) {
        if (!reloading) return;
        if (gun != reloadingGun) {
            // switching slots / dropping the gun interrupts the reload
            cancelReload();
            return;
        }
        if (System.currentTimeMillis() < reloadEndMs) return;
        // batch finished -> apply immediately
        CptNetwork.CHANNEL.sendToServer(new ReloadResultPacket(true, reloadBatch));
        if (reloadRoundMode && GunNbt.getAmmoCount(gun) < stats.feed.clipSize) {
            // next batch: no bolt cycle (chamber already loaded)
            reloadEndMs = System.currentTimeMillis() + dev.ignis.createpneumatictacticals.client.render
                    .GunAnimTiming.reloadBatchMs(gun, true, false, stats.reloadSpeed);
        } else {
            reloading = false;
        }
    }

    /** ends the reload state and stops the reload animation on the gun */
    private static void cancelReload() {
        if (!reloading) return;
        reloading = false;
        GunAnimationDriver.interrupt(reloadingGun);
    }
}