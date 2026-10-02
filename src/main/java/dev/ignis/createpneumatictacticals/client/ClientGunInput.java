package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.Config;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.AmmoTypes;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.FireRequestPacket;
import dev.ignis.createpneumatictacticals.network.ReloadResultPacket;
import dev.ignis.createpneumatictacticals.network.GunActionPacket;
import dev.ignis.createpneumatictacticals.network.SelectAmmoPacket;
import dev.ignis.createpneumatictacticals.module.FireMode;
import dev.ignis.createpneumatictacticals.sound.ModSoundEvents;
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
    /** fire animation length + margin, in ms (from the receiver animation file,
     *  animated ticks divided by the fire-rate playback speed) */
    private static long fireAnimMs(ItemStack gun, double speed) {
        double fireTicks = dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .animLengthTicks(gun, "fire", 2.5);
        // the animated part plays faster/slower; the transition + margin do not
        // +2: the fire's own single-tick transition plus a tick of margin, so
        // the reload's 2-tick blend starts from the fire's settled end pose
        // instead of a mid-fire one (see GunAnimationDriver.onFire)
        // (floor at the fire-speed rail, not at 0.1: a slowed fire animation
        //  must still own the whole window it plays in)
        return (long) ((fireTicks / Math.max(
                dev.ignis.createpneumatictacticals.client.render.GunAnimTiming.FIRE_SPEED_MIN, speed)
                + 2) * 50.0) + 50;
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
    /** tick the last shot was sent on — the local rate gate counts ticks,
     *  not wall-clock ms (see tryFire) */
    private static int lastFireTick = -1;
    /** set by tryFire when its own fire interval is the only thing blocking
     *  the pull — the queued-pull logic refunds a credit on exactly that */
    private static boolean fireBlockedByInterval;
    /** monotonic client tick counter (the local rate gate's unit) */
    private static int clientTicks;

    /** left-button pulls the tick loop could not see (see onAttackPress).
     *  Two at most: the one being spent now plus one stored, so a fast double
     *  click fires both while a triple still fires two. */
    private static int attackPressCredits;
    private static int attackPressTick = -1;
    private static long lastAttackPressMs;
    /** presses closer together than this are a chattering button, not a
     *  double click — a worn mouse must not fire two rounds per pull */
    private static final long PRESS_DEBOUNCE_MS = 20;
    /** a pull older than this many ticks is forgotten (a click during a long
     *  reload must not fire when the reload ends) */
    private static final int PRESS_CREDIT_TICKS = 10;
    /** the buffer only pays for itself on fast guns: at 600 RPM (one shot per
     *  2 ticks) or quicker a lost click is a sampling loss. Below that the
     *  second click is the gun's own cadence, and queuing it would only make
     *  the round late. */
    private static final long BUFFERED_MAX_INTERVAL_TICKS = 2;
    /** shots sent but not yet reflected in the synced AmmoCount */
    private static int pendingShots;
    private static int lastSeenAmmo = Integer.MIN_VALUE;

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
    /** empty reload with a pre_bolt stage: wall-clock ms when pre_bolt starts
     *  (= the reload start when the chain is pre_bolt -> reload -> bolt); 0 =
     *  no pre_bolt stage, the chain starts at reload directly */
    private static long reloadPreBoltStartMs = 0;

    /** the reload trigger (onReloadStart) already fired for the reload in
     *  flight — with a pre_bolt stage it fires LATER than the reload state
     *  itself (at the magazine-swap start), not at startReload time */
    private static boolean reloadSwapStarted = false;
    private static int reloadBatch = 0;
    private static ItemStack reloadingGun = ItemStack.EMPTY;
    /** hotbar slot the reload was started from (see sameHeldGun) */
    private static int reloadingSlot = -1;
    /** An empty reload interrupted during its bolt tail (hotbar switch): the
     *  magazine is swapped but the batch is not applied yet, so the reload is
     *  remembered instead of voided — the gun finishes with just the bolt
     *  when it comes back, rather than replaying the whole reload. Indexed by
     *  hotbar slot, so parking one gun's tail cannot overwrite another's. */
    private static final int HOTBAR_SLOTS = 9;
    private static final ItemStack[] tailGun = new ItemStack[HOTBAR_SLOTS];
    private static final int[] tailBatch = new int[HOTBAR_SLOTS];
    private static final boolean[] tailRoundMode = new boolean[HOTBAR_SLOTS];

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
        clientTicks++;
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
            // the gun left the hand: void the reload, except in its bolt tail
            // (see interruptReload — the magazine is swapped and the batch is
            // owed, so that one resumes when the gun comes back)
            interruptReload();
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
        if (GunNbt.getFireModeOrDefault(gun, stats) == FireMode.AUTO) attackPressCredits = 0;
        boolean queued = attackPressCredits > 0;
        if (queued && clientTicks - attackPressTick > PRESS_CREDIT_TICKS) {
            attackPressCredits = 0; // stale pull: forget it rather than fire late
            queued = false;
        }
        if (queued) attackPressCredits--; // spent, unless the attempt refunds it
        fireBlockedByInterval = false;
        if (mc.options.keyAttack.isDown() || queued) {
            // a queued pull is a fresh pull, whatever the held key says
            if (queued) wasFiring = false;
            if (reloading) {
                // dry click: the pull cannot fire while the reload runs,
                // but only when the magazine is actually empty (a
                // round-mode reload keeps its rounds and stays silent)
                if (!wasFiring && magazineEmpty(stats, gun)) playAmmoEmpty(player);
                wasFiring = true;
            } else {
                tryFire(player, gun, stats);
                // A pull the fire interval alone is holding back stays queued:
                // two clicks faster than the gun's own cadence should still put
                // two rounds downrange, one interval apart. Anything else (an
                // empty magazine, no ammo selected, the ready pose) spends it —
                // those must not repeat their feedback every tick.
                if (queued && fireBlockedByInterval) attackPressCredits++;
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
        if (!reloading && stats.isComplete() && magazineEmpty(stats, gun)) {
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
                startReload(player, gun, stats);
            }
        }

        // --- state cycling ---
        if (ModKeybinds.FIRE_MODE.consumeClick()) {
            CptNetwork.CHANNEL.sendToServer(new GunActionPacket(GunActionPacket.Action.NEXT_FIRE_MODE));
            // selector click: only when the receiver actually has another
            // mode to switch to (the server no-ops on a single-mode gun)
            if (stats.receiver != null && stats.receiver.fireModes != null
                    && stats.receiver.fireModes.size() > 1) {
                player.playSound(ModSoundEvents.SWITCH_FIREMODE.get(), 1.0f, 1.0f);
            }
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

    /**
     * True while a reload is deferred behind the fire animation (R pressed or
     * auto-reload triggered, waiting for the controller to free). The HUD
     * treats this window as reloading too — without it the readout flashes
     * the raw empty magazine between the trigger and the reload start.
     */
    public static boolean isReloadPending() {
        return reloadPending;
    }

    /** True while a round-by-round reload is running (vs a magazine swap). */
    public static boolean isReloadingRoundMode() {
        return reloading && reloadRoundMode;
    }

    /** wall-clock ms of the last local shot (0 = never); ReadyModel reads
     *  it for the sprint-fire return-to-ready timing */
    public static long lastShotMs() {
        return lastLocalShotMs;
    }

    /**
     * A left-button press, straight from the mouse callback
     * ({@code MouseHandler.onPress}) rather than from the tick loop.
     *
     * <p>Non-auto guns fire one round per PULL, and a tick can only sample the
     * key's held state: press-release-press inside one tick — or a press and
     * release between two ticks — arrived as "not down" and the round the
     * player asked for never fired. Recording the press itself lets the next
     * tick spend it. Auto guns need none of this (the held key is the trigger)
     * and presses while a screen is up belong to the screen.
     *
     * <p>Presses closer than {@link #PRESS_DEBOUNCE_MS} are dropped: a
     * chattering button would otherwise read as a very fast double click.
     *
     * <p>Only guns at {@value #BUFFERED_MAX_INTERVAL_TICKS} ticks per shot or
     * quicker (600 RPM) get the buffer — see the constant.
     */
    public static void onAttackPress() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null) return;
        ItemStack gun = mc.player.getMainHandItem();
        if (!(gun.getItem() instanceof GeoGunItem)) return;
        GunStats stats = GunStats.ofGun(gun);
        if (GunNbt.getFireModeOrDefault(gun, stats) == FireMode.AUTO) return;
        long intervalTicks = resolveIntervalTicks(mc.player, gun, stats);
        if (intervalTicks < 0 || intervalTicks > BUFFERED_MAX_INTERVAL_TICKS) return;
        long now = System.currentTimeMillis();
        if (now - lastAttackPressMs < PRESS_DEBOUNCE_MS) return;
        lastAttackPressMs = now;
        attackPressCredits = Math.min(2, attackPressCredits + 1);
        attackPressTick = clientTicks;
    }

    /** ticks between shots for this gun + ammo, or -1 when the ammo type is
     *  unknown — the same two lookups tryFire gates on, without touching the
     *  {@link #currentType} field the muzzle smoke reads */
    private static long resolveIntervalTicks(Player player, ItemStack gun, GunStats stats) {
        String ammoId = GunNbt.getAmmo(gun);
        if (ammoId == null || ammoId.isEmpty()) return -1;
        PotatoCannonProjectileType type = PotatoCannonProjectileType
                .getTypeForItem(player.level().registryAccess(),
                        AmmoExtension.contentItemFor(player.level().registryAccess(), ammoId))
                .map(ref -> ref.value()).orElse(null);
        return type == null ? -1 : AmmoExtension.fireIntervalTicks(type, stats.fireRateMultiplier);
    }

    private static void tryFire(Player player, ItemStack gun, GunStats stats) {
        fireBlockedByInterval = false;
        if (!stats.isComplete()) {
            feedback(player, "incomplete");
            return;
        }
        if (!ReadyModel.canFire()) return; // ready pose: gun not yet back in the firing stance
        if (MuzzleClearance.isBlocked()) return; // muzzle pressed into geometry
        FireMode mode = GunNbt.getFireModeOrDefault(gun, stats);
        long now = System.currentTimeMillis();

        boolean semi = mode == FireMode.SEMI;
        if (semi && wasFiring) return;

        // local rate limit for responsiveness; server validates authoritatively
        String ammoId = GunNbt.getAmmo(gun);
        if (ammoId == null || ammoId.isEmpty()) {
            feedback(player, "no_ammo_selected", ModKeybinds.CYCLE_AMMO);
            return;
        }
        if (magazineEmpty(stats, gun)) {
            // one click per trigger pull (holding auto fire on an empty
            // magazine would otherwise click every tick)
            if (!wasFiring) playAmmoEmpty(player);
            return; // empty magazine (the HUD clip counter + auto reload say it)
        }
        // air mirror: an internal-tank gun that cannot pay for the shot must
        // not send the request. The server rejected it anyway (same feedback
        // key), but every rejected packet still spent its predicted round:
        // the HUD dropped to 0 while the magazine was full, which then made
        // the auto/manual reload refuse as a full-magazine no-op. Refusing
        // here keeps the client's count honest (the server keeps the
        // authoritative gate). One message per trigger pull, like the empty
        // click above.
        if (stats.supply != null && stats.supply.supplyType
                == dev.ignis.createpneumatictacticals.module.SupplyType.INTERNAL_TANK
                && !dev.ignis.createpneumatictacticals.gun.AirTank.canFire(gun, stats.supply)) {
            if (!wasFiring) feedback(player, "no_air");
            wasFiring = true; // semis take one attempt per pull
            return;
        }
        AmmoExtension ext = AmmoExtension.get(ammoId);
        // Mirror of the server gate: ammo reload_ticks / multiplier, counted in
        // TICKS — the game advances in ticks, so a wall-clock gate (50 ms ×
        // ticks) skipped every tick that landed 49 ms after the previous shot
        // and then waited a whole extra tick: a 1-tick ammo measured 12×1t +
        // 7×2t gaps for 20 shots (~1.37 ticks/shot) instead of a flat 1t.
        // (client lookups are best-effort — the server validates anyway)
        currentType = PotatoCannonProjectileType
                .getTypeForItem(player.level().registryAccess(),
                        AmmoExtension.contentItemFor(player.level().registryAccess(), ammoId))
                .map(ref -> ref.value()).orElse(null);
        long intervalTicks = currentType != null
                ? AmmoExtension.fireIntervalTicks(currentType, stats.fireRateMultiplier)
                : 1; // unknown type: 10/s fallthrough, server will gate
        fireBlockedByInterval = false;
        if (clientTicks - lastFireTick < intervalTicks) {
            fireBlockedByInterval = true;
            return;
        }
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
        // one frame for both: the muzzle tip/roll the shooter's own render
        // pass captured (world space) rides to the server so nearby clients
        // puff from the barrel, and drives the local plume identically
        MuzzleSmoke.MuzzleFrame smokeFrame = MuzzleSmoke.sampleFrame(Minecraft.getInstance(), player, punchDir);
        CptNetwork.CHANNEL.sendToServer(new FireRequestPacket(
                player.getEyePosition(1.0f), punchDir,
                // same frame's ADS progress: the server closes the spread cone
                // on the client's own curve, so shots fired while the gun is
                // still coming up are not yet pinpoint
                AimHandler.aimProgress(Minecraft.getInstance().getFrameTime()),
                smokeFrame.pos(), smokeFrame.up()));
        // instant local fire sound (default: potato-cannon FWOOMP); the
        // server broadcast excludes the shooter. Pitch follows the ammo's
        // sound_pitch (Create potato projectile type — same variable-pitch
        // behavior as the potato cannon) unless the receiver opts out via
        // ignore_ammo_pitch; currentType is null only for unknown ammo, and
        // Create's fallback pitch there is 1 anyway
        String soundId = stats.fireSound != null ? stats.fireSound : "create:fwoomp";
        float pitch = stats.receiver.ignoreAmmoPitch ? 1.0f
                : (currentType != null ? currentType.soundPitch() : 1.0f);
        var fireSoundEvent = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS
                .getValue(net.minecraft.resources.ResourceLocation.tryParse(soundId));
        if (fireSoundEvent != null) player.playSound(fireSoundEvent, 1.0f, pitch);
        lastLocalShotMs = now;
        lastFireTick = clientTicks;
        pendingShots++;
        wasFiring = true;
        // fire animation speed from the real cadence: the receiver's authored
        // fire length over this gun+ammo's shot interval (see
        // GunAnimTiming.fireSpeed), so one fire cycle spans exactly one shot
        double fireSpeed = dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .fireSpeed(gun, intervalTicks);
        // fire animation occupancy (fire length + the reload's blend margin):
        // startReload must wait this out or it snapshots the pose mid-fire and
        // the reload's transition then blends from that stale pose.
        fireAnimBusyUntilMs = now + fireAnimMs(gun, fireSpeed);
        // local feel: recoil + bloom + fire animation
        boolean aiming = ModKeybinds.isAiming();
        RecoilModel.onShot(stats.receiver.baseRecoilPitch, stats.receiver.baseRecoilYaw,
                stats.recoilVerticalMultiplier, stats.recoilHorizontalMultiplier, aiming,
                stats.recoilRecovery, stats.receiver.id, player.getInventory().selected, player);
        SpreadModel.addBloom(ext);
        // muzzle smoke: puffed here, and relayed to nearby clients by the
        // server (MuzzleSmokePacket) so they puff it from their own gunpack
        MuzzleSmoke.onFire(player, smokeFrame);
        GunAnimationDriver.onFire(gun, fireSpeed);
    }

    /**
     * Actionbar hint for client-side fire rejection; key names resolve from the
     * player's actual keybinds, never hardcoded.
     */
    /**
     * Rounds the client believes are in the magazine: the synced AmmoCount
     * minus the shots it has already sent. The sync trails the server's
     * decrement by a tick or two, so reading the raw count let the client fire
     * "phantom" rounds off a stale magazine — audible as extra shots (23
     * packets for a 20-round mag, measured), every one of them dropped by the
     * server. The debt is settled from the count's own movement: a drop means
     * the server consumed some of what we owe, a rise (a reload) clears it.
     */
    public static int predictedAmmo(ItemStack gun) {
        int synced = GunNbt.getAmmoCount(gun);
        if (synced != lastSeenAmmo) {
            if (synced < lastSeenAmmo) {
                pendingShots = Math.max(0, pendingShots - (lastSeenAmmo - synced));
            } else {
                pendingShots = 0;
            }
            lastSeenAmmo = synced;
        }
        return Math.max(0, synced - pendingShots);
    }

    /** nothing loaded: non-backpack feeds with an empty magazine (a gun fed
     *  straight from the backpack has no magazine to be empty) */
    private static boolean magazineEmpty(GunStats stats, ItemStack gun) {
        return stats.feed != null
                && stats.feed.feedType != dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK
                && predictedAmmo(gun) <= 0;
    }

    /** dry-fire click; loudness is the sounds.json entry's volume (0.5) */
    private static void playAmmoEmpty(Player player) {
        player.playSound(ModSoundEvents.AMMO_EMPTY.get(), 1.0f, 1.0f);
    }

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
        // a reload already running owns the trigger: a request made mid-reload
        // (R pressed again, an ammo pick) is a no-op, not a deferred one — the
        // running reload's result packet is what applies a pick, and a latch
        // set here would outlive that reload and start a second one after it
        if (reloading) {
            reloadSwapAmmo = null; // the running reload's result applies the pick
            return;
        }
        if (System.currentTimeMillis() < fireAnimBusyUntilMs) {
            reloadPending = true;
            reloadPendingGun = gun;
            reloadPendingSlot = player.getInventory().selected;
            return;
        }
        startReload(player, gun, stats);
    }

    private static void startReload(Player player, ItemStack gun, GunStats stats) {
        // the deferred request is consumed here: a started reload satisfies it.
        // A latch that outlives its own reload — the auto trigger latched behind
        // the fire animation, a later tick then started the reload through the
        // free-animation path — would otherwise fire the moment the controller
        // frees again, with the magazine already refilled: a reload nobody
        // asked for, on the non-empty chain.
        reloadPending = false;
        reloadPendingGun = null;
        // consumed here: a swap shapes only the reload it was requested for
        String swapAmmo = reloadSwapAmmo;
        reloadSwapAmmo = null;
        if (reloading || !stats.isComplete() || stats.feed == null
                || stats.feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK) return;
        // a swap empties the magazine first, so it starts even from a full one.
        // The count is the PREDICTED one — the same quantity the trigger's
        // emptiness test reads; testing the raw synced count here instead let
        // the two disagree, and a reload refused as "full" while the trigger
        // treated the magazine as empty left the gun dead until an ammo swap
        if (swapAmmo == null && predictedAmmo(gun) >= stats.feed.clipSize) return;
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
            // no rounds anywhere (loose or boxed): no reload to run — flash
            // the reserve readout red instead of an actionbar line
            dev.ignis.createpneumatictacticals.client.GunHudOverlay.flashReserve();
            return;
        }
        reloadRoundMode = stats.feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.ROUND;
        // duration = receiver animation length (+ pre_bolt + bolt when
        // empty) / reload speed
        boolean empty = swapAmmo != null || GunNbt.getAmmoCount(gun) <= 0;
        reloadEmpty = empty; // third-person choreography variant
        long now = System.currentTimeMillis();
        double preBoltTicks = dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .preBoltTicks(gun);
        // the empty chain is pre_bolt -> reload -> bolt, each stage only
        // when the receiver's animation file defines it. The magazine-swap
        // segment (reload) is what startReload starts; with a pre_bolt the
        // swap state itself starts at the PRE-BOLT boundary instead.
        reloadPreBoltStartMs = empty && preBoltTicks > 0 ? now : 0;

        reloadSwapStarted = false;
        long preBoltMs = (long) (preBoltTicks * 50.0 / Math.max(GunStats.RELOAD_SPEED_MIN, stats.reloadSpeed));
        reloadBatchMs = dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .reloadBatchMs(gun, reloadRoundMode, empty, stats.reloadSpeed);
        reloadEndMs = now + reloadBatchMs;
        // bolt start = the whole pre-bolt + magazine segment (transitions
        // included) after the swap-state start
        reloadBoltStartMs = empty ? now + preBoltMs
                + dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                        .reloadPhaseMs(gun, reloadRoundMode, stats.reloadSpeed) : 0;
        reloadBatch = reloadRoundMode ? Math.max(1, stats.feed.loadAmount) : stats.feed.clipSize;
        reloadingGun = gun;
        reloadingSlot = player.getInventory().selected;
        reloadBoltFired = false;
        reloading = true;
        if (reloadPreBoltStartMs > 0) {
            // pre_bolt opens the chain (its own controller trigger, fired
            // right here: no chained-stage transition, see onReloadStart);
            // onReloadStart waits for the magazine-swap boundary in tickReload
            GunAnimationDriver.onPreBolt(stats.reloadSpeed);
        } else {
            GunAnimationDriver.onReloadStart();
            reloadSwapStarted = true;
        }
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
        // deep reserve: boxed pods of the same kind
        for (ItemStack stack : player.getInventory().items) {
            if (!(stack.getItem() instanceof dev.ignis.createpneumatictacticals.block
                    .AmmoBoxBlockItem)) continue;
            ItemStack template = dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.boxTemplate(stack);
            if (template.isEmpty()) continue;
            boolean boxedCartridge = template.getItem()
                    == dev.ignis.createpneumatictacticals.item.ModItems.PRESSURIZED_POD.get();
            if (boxedCartridge != cartridge) continue;
            String typeId = dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.ammoTypeId(player.level().registryAccess(), stack);
            if (ammoId.equals(typeId)) n += dev.ignis.createpneumatictacticals.block.entity.AmmoBoxBlockEntity.roundsIn(stack);
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
        if (!reloading) {
            resumeBoltTail(player, gun, stats);
            return;
        }
        if (!sameHeldGun(player, gun, reloadingSlot, reloadingGun)) {
            // switching slots / swapping the gun out interrupts the reload.
            // Stack IDENTITY is not the test: an NBT write from the server
            // (X aim stance, V fire mode, O ammo cycle) comes back as a fresh
            // ItemStack in the same slot, and testing identity voided the
            // reload mid-way — the side/main sight switch looked like an
            // interrupt for exactly that reason.
            // interrupted: void the reload, or keep its bolt tail (see
            // interruptReload)
            interruptReload();
            return;
        }
        // pre_bolt -> reload handoff at the magazine-swap boundary. The
        // pre-bolt animation fired at startReload; the magazine swap's own
        // trigger fires here once the pre-bolt window elapsed (0 ms when
        // there is no stage: reloadSwapStarted already latched in startReload)
        if (reloadEmpty && !reloadSwapStarted
                && System.currentTimeMillis() >= reloadPreBoltStartMs + preBoltMs(gun, stats)) {
            reloadSwapStarted = true;
            GunAnimationDriver.onReloadStart();
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
            // next batch: no bolt cycle (chamber already loaded), and the
            // chain flags reset so a later empty batch (impossible here,
            // kept symmetric with startReload) cannot re-fire stages
            reloadEndMs = System.currentTimeMillis() + dev.ignis.createpneumatictacticals.client.render
                    .GunAnimTiming.reloadBatchMs(gun, true, false, stats.reloadSpeed);
            reloadSwapStarted = true; // the swap segment IS running
        } else {
            reloading = false;
        }
    }

    /** pre_bolt stage length in ms, reloadSpeed-scaled (0 = no stage) */
    private static long preBoltMs(ItemStack gun, GunStats stats) {
        return (long) (dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .preBoltTicks(gun) * 50.0 / Math.max(GunStats.RELOAD_SPEED_MIN, stats.reloadSpeed));
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

    /**
     * A reload that lost its gun (hotbar switch to anything — another gun,
     * a tool, an empty hand). An empty reload whose bolt tail has already
     * started keeps its owed batch (see {@link #suspendBoltTail}): the
     * magazine swap played and only the batch application is left, so the gun
     * finishes with just the bolt when it comes back. Every earlier phase is
     * voided outright — the magazine is not swapped yet, and resuming there
     * would need the reload animation to restart anyway.
     *
     * <p>Both interrupt sites (the not-holding-a-gun early return and the
     * held-gun-changed check) MUST go through here: routing only one of them
     * left the tail voided whenever the switch landed on a non-gun slot.
     */
    private static void interruptReload() {
        if (!reloading) return;
        if (reloadEmpty && System.currentTimeMillis() >= reloadBoltStartMs) {
            suspendBoltTail();
        } else {
            cancelReload();
        }
    }

    /**
     * Park an empty reload that was interrupted in its bolt tail. The magazine
     * swap has played and the batch is still owed; the reload is NOT voided (no
     * CANCEL_AMMO_SWAP — a deferred ammo swap stays pending), only the state is
     * stashed until the gun is in hand again. The reload animation is stopped
     * with the gun; the tail replays the bolt from the top on return.
     */
    private static void suspendBoltTail() {
        if (!reloading) return;
        int slot = reloadingSlot;
        if (slot < 0 || slot >= HOTBAR_SLOTS) {
            cancelReload(); // unknown slot: nothing to key the tail on
            return;
        }
        tailGun[slot] = reloadingGun;
        tailBatch[slot] = reloadBatch;
        tailRoundMode[slot] = reloadRoundMode;
        reloading = false;
        reloadBoltFired = false;
        reloadingSlot = -1;
        GunAnimationDriver.interrupt(reloadingGun);
    }

    /**
     * Resume a parked bolt tail: play only the bolt, then let the normal
     * completion apply the owed batch. Runs before the reload-state return in
     * tickReload, so the empty-magazine auto-reload cannot restart the whole
     * reload+bolt chain first.
     */
    private static void resumeBoltTail(Player player, ItemStack gun, GunStats stats) {
        int slot = player.getInventory().selected;
        if (slot < 0 || slot >= HOTBAR_SLOTS) return;
        ItemStack parked = tailGun[slot];
        // same slot + same item is the identity test the rest of the reload
        // state machine uses (NBT re-syncs hand out fresh instances)
        if (parked == null || parked.isEmpty() || gun.getItem() != parked.getItem()) return;
        int batch = tailBatch[slot];
        boolean roundMode = tailRoundMode[slot];
        tailGun[slot] = null;
        // the tail window is the bolt segment: what the empty batch adds
        // over the pre-bolt + magazine phases, pre_bolt included (its
        // length is zero for guns without the stage, so the arithmetic is
        // unchanged for them)
        long batchMs = dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .reloadBatchMs(gun, roundMode, true, stats.reloadSpeed);
        long prePhaseMs = dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .preBoltTicks(gun) > 0 ? preBoltMs(gun, stats) : 0;
        long phaseMs = prePhaseMs + dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .reloadBatchMs(gun, roundMode, false, stats.reloadSpeed);
        reloadingGun = gun;
        reloadingSlot = slot;
        reloadRoundMode = roundMode;
        reloadBatch = batch;
        reloadEmpty = true;
        // the tail IS the bolt: mark it fired so the normal tick never racks
        // a second time, and 0 so the third-person pose skips the magazine
        // segment (it is already swapped)
        reloadBoltFired = true;
        reloadBoltStartMs = 0;
        reloadPreBoltStartMs = 0;

        reloadSwapStarted = true; // parked past the magazine swap: never re-fire
        reloadEndMs = System.currentTimeMillis() + Math.max(50, batchMs - phaseMs);
        reloading = true;
        GunAnimationDriver.onBolt(stats.reloadSpeed);
    }

    /** ends the reload state and stops the reload animation on the gun */
    private static void cancelReload() {
        if (!reloading) return;
        reloading = false;
        reloadBoltFired = false;

        reloadSwapStarted = false;
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