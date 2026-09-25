package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.Config;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.AmmoTypes;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.FireRequestPacket;
import dev.ignis.createpneumatictacticals.network.ReloadResultPacket;
import dev.ignis.createpneumatictacticals.network.GunActionPacket;
import dev.ignis.createpneumatictacticals.network.SelectAmmoPacket;
import dev.ignis.createpneumatictacticals.module.FireMode;
import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Client input loop: while a gun is held, left mouse fires (per fire mode),
 * R reloads, V cycles fire mode, O cycles ammo, X cycles aim stance. Fire
 * requests are sent to the server (server-authoritative); view recoil and
 * spread bloom are applied locally on the confirmed cadence.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class ClientGunInput {

    private static boolean wasFiring = false;
    /** controller busy with the fire animation (fire length + transition); reloads wait it out */
    private static long fireAnimBusyUntilMs = 0;
    /** fire animation length + margin, in ms (from the receiver animation file) */
    private static long fireAnimMs(ItemStack gun) {
        double fireTicks = dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .animLengthTicks(gun, "fire", 2.5);
        // +2: the fire's own single-tick transition plus a tick of margin, so
        // the reload's 2-tick blend starts from the fire's settled end pose
        // instead of a mid-fire one (see GunAnimationDriver.onFire)
        return (long) ((fireTicks + 2) * 50.0) + 50;
    }
    /** manual R pressed during the fire-animation window; retried next tick */
    private static boolean reloadPending = false;
    /** the gun the pending R press was meant for; a slot switch voids it */
    private static ItemStack reloadPendingGun = null;
    /** hotbar slot the pending press came from (see sameHeldGun) */
    private static int reloadPendingSlot = -1;
    /** ammo type a reload was started as a swap for; null = plain reload.
     * Consumed by the startReload it was requested for. */
    private static String reloadSwapAmmo = null;
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
    /** empty reload: wall-clock ms when the bolt segment starts — the
     * third-person RELOADING_EMPTY pose flips here so the arm out-phase
     * (up-swing + tap) aligns WITH the bolt instead of playing after it */
    private static long reloadBoltStartMs = 0;
    /** empty reload: onBolt already fired for the batch in flight */
    private static boolean reloadBoltFired = false;
    private static int reloadBatch = 0;
    private static ItemStack reloadingGun = ItemStack.EMPTY;
    /** hotbar slot the reload was started from (see sameHeldGun) */
    private static int reloadingSlot = -1;

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
        ItemStack gun = player.getMainHandItem();
        boolean holdingGun = gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GeoGunItem;
        // Pose state machines (ready/aim overlay) keep ticking with any GUI
        // open: freezing them mid-transition left prev/cur unequal, and the
        // per-frame lerp(partialTick, prev, cur) then oscillated the gun
        // between the two frozen values at tick rate — the "gun trembles
        // near a half-finished ready pose with the inventory/ESC open" bug.
        // Only the input-driven logic below is blocked while a screen is up.
        MuzzleClearance.tick(player, holdingGun);
        ReadyModel.tick(player, holdingGun);
        if (!holdingGun) {
            wasFiring = false;
            cancelReload();
            updatePoseBroadcast(false, gun);
            return;
        }

        GunStats stats = GunStats.ofGun(gun);

        // A screen interrupts NOTHING here: the reload state machine and the
        // pose broadcast run BEFORE the screen gate below, so opening the
        // inventory mid-reload lets the batch timer, the completion packet
        // and the third-person pose carry on to the end. The gun item is not
        // drawn behind a GUI, so the frozen animation is invisible — the
        // reload itself is not. Only the input-driven logic below is blocked.
        tickReload(player, gun, stats);
        updatePoseBroadcast(true, gun);
        if (mc.screen != null) return;

        // --- fire ---
        if (mc.options.keyAttack.isDown()) {
            if (reloading) {
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
            requestReload(player, gun, stats);
        }

        // --- auto reload: an empty gun tries to reload on its own (silent when
        // no ammo is selected or no pods are available — no actionbar spam) ---
        if (!reloading && stats.isComplete() && stats.feed != null
                && stats.feed.feedType != dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK
                && GunNbt.getAmmoCount(gun) <= 0) {
            String autoAmmoId = GunNbt.getPendingAmmo(gun);
            if (autoAmmoId == null || autoAmmoId.isEmpty()) autoAmmoId = GunNbt.getAmmo(gun);
            if (autoAmmoId != null && !autoAmmoId.isEmpty()) {
                boolean cartridge = stats.supply != null && stats.supply.supplyType
                        == dev.ignis.createpneumatictacticals.module.SupplyType.CARTRIDGE;
                if (player.isCreative() || countMatchingPods(player, cartridge, autoAmmoId) > 0) {
                    requestReload(player, gun, stats);
                }
            }
        }

        // deferred manual reload: R was pressed while the fire animation was
        // still blending; retry once the controller is free. The pending gun
        // must still be in hand — a slot switch voids the press.
        if (reloadPending) {
            if (!sameHeldGun(player, gun, reloadPendingSlot, reloadPendingGun)) {
                // slot switched since the press: void the pending reload and
                // cancel the swap it was going to apply
                reloadPending = false;
                reloadPendingGun = null;
                reloadSwapAmmo = null;
                CptNetwork.CHANNEL.sendToServer(
                        new GunActionPacket(GunActionPacket.Action.CANCEL_AMMO_SWAP));
            } else if (!reloading && System.currentTimeMillis() >= fireAnimBusyUntilMs) {
                reloadPending = false;
                reloadPendingGun = null;
                startReload(player, gun, stats);
            }
        }

        // --- state cycling ---
        if (ModKeybinds.FIRE_MODE.consumeClick()) {
            CptNetwork.CHANNEL.sendToServer(new GunActionPacket(GunActionPacket.Action.NEXT_FIRE_MODE));
        }
        // aim-stance key: only while aiming -- the stance drives the ADS sight
        // picture, so a press with the gun down flips a state the player
        // cannot see. consumeClick() stays the left operand so an ignored
        // press is still consumed; otherwise it would sit in the queue and
        // fire the moment the player does aim.
        if (ModKeybinds.AIM_STANCE.consumeClick() && AimHandler.isAiming()) {
            CptNetwork.CHANNEL.sendToServer(new GunActionPacket(GunActionPacket.Action.CYCLE_AIM_STANCE));
        }
    }

    // --- third-person pose sync: the owning client is the source of truth ---
    private static dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose lastSentPose =
            dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.HIP;

    private static void updatePoseBroadcast(boolean holdingGun, ItemStack gun) {
        dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose pose;
        if (!holdingGun) {
            pose = dev.ignis.createpneumatictacticals.network.PoseBroadcastPacket.Pose.HIP;
        } else if (reloading && !(reloadEmpty
                && System.currentTimeMillis() >= reloadBoltStartMs)) {
            // the RELOADING* pose covers the magazine swap only. On an empty
            // reload it must LEAVE when the bolt segment starts, so the
            // observer's ReloadArmAnimation out-phase (up-swing, then the
            // bolt-rack tap) plays WITH the bolt — falling through to the
            // aim/ready branches below while the bolt still animates
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
        // server broadcast excludes the shooter. Pitch follows the ammo's
        // sound_pitch (Create potato projectile type — same variable-pitch
        // behavior as the potato cannon) unless the receiver opts out via
        // ignore_ammo_pitch; currentType is null only for unknown ammo, and
        // Create's fallback pitch there is 1 anyway
        String soundId = stats.receiver.fireSound != null ? stats.receiver.fireSound : "create:fwoomp";
        float pitch = stats.receiver.ignoreAmmoPitch ? 1.0f
                : (currentType != null ? currentType.soundPitch() : 1.0f);
        var fireSoundEvent = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS
                .getValue(net.minecraft.resources.ResourceLocation.tryParse(soundId));
        if (fireSoundEvent != null) player.playSound(fireSoundEvent, 1.0f, pitch);
        lastLocalShotMs = now;
        wasFiring = true;
        // fire animation occupancy (fire length + the reload's blend margin):
        // startReload must wait this out or it snapshots the pose mid-fire and
        // the reload's transition then blends from that stale pose.
        fireAnimBusyUntilMs = now + fireAnimMs(gun);
        // local feel: recoil + bloom + fire animation
        boolean aiming = ModKeybinds.isAiming();
        RecoilModel.onShot(stats.receiver.baseRecoilPitch, stats.receiver.baseRecoilYaw,
                stats.recoilVerticalMultiplier, stats.recoilHorizontalMultiplier, aiming,
                stats.recoilRecovery, player);
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
    /**
     * startReload entry: defers while the fire animation owns the controller
     * (~fire length + 2-tick transition). Triggering the reload chain mid-fire
     * snapshots the bolt at its mid-pull pose; the chain's stage transitions
     * then blend from that stale snapshot and the bolt visibly teleports.
     * Manual R is consumeClick'd (a swallowed press cannot be re-read), so a
     * refused manual reload latches into reloadPending and retries next tick;
     * auto-reload re-evaluates its own conditions every tick anyway.
     */
    private static void requestReload(Player player, ItemStack gun, GunStats stats) {
        if (System.currentTimeMillis() < fireAnimBusyUntilMs) {
            reloadPending = true;
            reloadPendingGun = gun;
            reloadPendingSlot = player.getInventory().selected;
            return;
        }
        startReload(player, gun, stats);
    }

    private static void startReload(Player player, ItemStack gun, GunStats stats) {
        // consumed here: a swap shapes only the reload it was requested for
        String swapAmmo = reloadSwapAmmo;
        reloadSwapAmmo = null;
        if (reloading || !stats.isComplete() || stats.feed == null
                || stats.feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK) return;
        // a swap empties the magazine first, so it starts even from a full one
        if (swapAmmo == null && GunNbt.getAmmoCount(gun) >= stats.feed.clipSize) return;
        // an ammo swap defers the new type to this reload — check pods for
        // THAT type, or the reload would be rejected as "no_pod" even though
        // new-type pods exist. The server sync (same values) is a tick away,
        // hence the flag instead of a local NBT prediction.
        String ammoId = swapAmmo;
        if (ammoId == null || ammoId.isEmpty()) ammoId = GunNbt.getPendingAmmo(gun);
        if (ammoId == null || ammoId.isEmpty()) ammoId = GunNbt.getAmmo(gun);
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
        boolean empty = swapAmmo != null || GunNbt.getAmmoCount(gun) <= 0;
        reloadEmpty = empty; // third-person choreography variant
        reloadBatchMs = dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .reloadBatchMs(gun, reloadRoundMode, empty, stats.reloadSpeed);
        reloadEndMs = System.currentTimeMillis() + reloadBatchMs;
        reloadBoltStartMs = empty ? System.currentTimeMillis()
                + dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                        .reloadPhaseMs(gun, reloadRoundMode, stats.reloadSpeed) : 0;
        reloadBatch = reloadRoundMode ? Math.max(1, stats.feed.loadAmount) : stats.feed.clipSize;
        reloadingGun = gun;
        reloadingSlot = player.getInventory().selected;
        reloadBoltFired = false;
        reloading = true;
        GunAnimationDriver.onReloadStart();
    }

    /**
     * Ammo switch (wheel pick / tap cycle). With a magazine the server hands
     * the remaining rounds back and defers the new type to the reload started
     * here — so an interrupted reload cancels the switch: the gun keeps its
     * loaded type and the returned rounds stay with the player. The swap is
     * flagged, not written into NBT: the server's sync (same values — empty
     * magazine, new type pending) is a tick away, and a pick the server rejects
     * must not leave a local magazine state behind.
     */
    public static void switchAmmo(Player player, ItemStack gun, GunStats stats, String ammoId) {
        if (ammoId == null || ammoId.isEmpty() || stats.feed == null) return;
        if (!(gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem)) return;
        if (ammoId.equals(effectiveAmmo(gun))) return; // nothing to switch to
        CptNetwork.CHANNEL.sendToServer(new SelectAmmoPacket(ammoId));
        if (stats.feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK) {
            return; // no magazine state: the server switches instantly
        }
        reloadSwapAmmo = ammoId;
        requestReload(player, gun, stats);
    }

    /** Ammo key tap: cycle to the next type the player can load (same order as the wheel). */
    public static void cycleAmmo(Player player, ItemStack gun, GunStats stats) {
        if (!(gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GunItem)) return;
        if (stats.receiver == null) return;
        List<String> compatible = AmmoTypes.compatibleFor(player, stats);
        if (compatible.isEmpty()) return;
        String current = effectiveAmmo(gun);
        int idx = current == null ? -1 : compatible.indexOf(current);
        switchAmmo(player, gun, stats, compatible.get((idx + 1) % compatible.size()));
    }

    /** The type the gun shows: a deferred pick outranks the loaded one. */
    public static String effectiveAmmo(ItemStack gun) {
        String id = GunNbt.getPendingAmmo(gun);
        return id == null || id.isEmpty() ? GunNbt.getAmmo(gun) : id;
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
     * interrupts it (firing is locked, NOT an interrupt; opening a screen is
     * NOT one either — see onClientTick). The "same gun" check is the hotbar
     * slot + the item, never the stack instance: any server NBT write (aim
     * stance, fire mode, ammo cycle, the reload result itself) re-syncs a
     * fresh ItemStack into the slot.
     */
    private static void tickReload(Player player, ItemStack gun, GunStats stats) {
        if (!reloading) return;
        if (!sameHeldGun(player, gun, reloadingSlot, reloadingGun)) {
            // switching slots / swapping the gun out interrupts the reload.
            // Stack IDENTITY is not the test: an NBT write from the server
            // (X aim stance, V fire mode, O ammo cycle) comes back as a fresh
            // ItemStack in the same slot, and testing identity voided the
            // reload mid-way — the side/main sight switch looked like an
            // interrupt for exactly that reason.
            cancelReload();
            return;
        }
        if (reloading && reloadEmpty && !reloadBoltFired
                && System.currentTimeMillis() >= reloadBoltStartMs) {
            // the bolt plays as its own animation instead of the reload
            // chain's second stage: a GeckoLib stage switch does not re-save
            // the transition start, so the chained bolt blended from the
            // stale snapshot the fire left on the slide (see
            // GunAnimationDriver.onReloadStart)
            reloadBoltFired = true;
            GunAnimationDriver.onBolt(stats.reloadSpeed);
        }
        if (System.currentTimeMillis() < reloadEndMs) return;
        // batch finished -> apply immediately
        CptNetwork.CHANNEL.sendToServer(new ReloadResultPacket(true, reloadBatch));
        // optimistic ammo prediction: the server's authoritative NBT sync
        // lags a tick behind the result packet, and in that window the
        // auto-reload check still sees an empty magazine and would restart
        // the whole reload+bolt chain from scratch (visible as the bolt
        GunNbt.setAmmoCount(gun, Math.min(stats.feed.clipSize,
                GunNbt.getAmmoCount(gun) + reloadBatch));
        if (reloadRoundMode && GunNbt.getAmmoCount(gun) < stats.feed.clipSize) {
            // next batch: no bolt cycle (chamber already loaded)
            reloadEndMs = System.currentTimeMillis() + dev.ignis.createpneumatictacticals.client.render
                    .GunAnimTiming.reloadBatchMs(gun, true, false, stats.reloadSpeed);
        } else {
            reloading = false;
        }
    }

    /**
     * True while {@code gun} is still the gun an action was started on: same
     * hotbar slot, same item. NBT is deliberately NOT compared — a stance /
     * fire-mode / ammo-cycle write re-syncs the stack as a NEW instance, and
     * that must not read as "the gun changed". Dropping or stowing the gun
     * fails the item half of this test (the slot then holds something else).
     */
    private static boolean sameHeldGun(Player player, ItemStack gun, int slot, ItemStack ref) {
        return player.getInventory().selected == slot && gun.getItem() == ref.getItem();
    }

    /** ends the reload state and stops the reload animation on the gun */
    private static void cancelReload() {
        if (!reloading) return;
        reloading = false;
        reloadBoltFired = false;
        reloadPending = false;
        reloadPendingGun = null;
        reloadingSlot = -1;
        reloadSwapAmmo = null;
        // an aborted reload cancels the swap it was started for: the gun keeps
        // its loaded type and the returned rounds stay with the player
        CptNetwork.CHANNEL.sendToServer(
                new GunActionPacket(GunActionPacket.Action.CANCEL_AMMO_SWAP));
        // interrupt() keys off the stack's GeckoLib instance id, which the
        // server round-trip preserves, so the stale reference still stops the
        // right animation
        GunAnimationDriver.interrupt(reloadingGun);
    }
}