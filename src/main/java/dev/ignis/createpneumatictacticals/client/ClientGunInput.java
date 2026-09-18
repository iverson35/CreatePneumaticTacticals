package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.FireRequestPacket;
import dev.ignis.createpneumatictacticals.network.ReloadResultPacket;
import dev.ignis.createpneumatictacticals.network.GunActionPacket;
import dev.ignis.createpneumatictacticals.module.FireMode;
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
    private static long lastLocalShotMs = 0;

    // --- client reload state machine: R starts it, completion sends the result packet ---
    private static boolean reloading = false;
    private static boolean reloadRoundMode = false;
    private static long reloadEndMs = 0;
    private static long reloadBatchMs = 0;
    private static int reloadBatch = 0;
    private static ItemStack reloadingGun = ItemStack.EMPTY;

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
            reloading = false;
            return;
        }

        ItemStack gun = player.getMainHandItem();
        boolean holdingGun = gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GeoGunItem;
        if (!holdingGun) {
            wasFiring = false;
            reloading = false;
            return;
        }

        GunStats stats = GunStats.of(GunNbt.readModules(gun));

        // --- fire ---
        if (mc.options.keyAttack.isDown()) {
            tryFire(player, gun, stats);
        } else {
            wasFiring = false;
        }

        // --- reload state machine ---
        tickReload(player, gun, stats);
        if (ModKeybinds.RELOAD.consumeClick()) {
            startReload(gun, stats);
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

        // --- models tick ---
        RecoilModel.tick(player);
        SpreadModel.tick(player, gun);
    }
    private static void tryFire(Player player, ItemStack gun, GunStats stats) {
        if (!stats.isComplete()) {
            feedback(player, "incomplete");
            return;
        }
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
            feedback(player, "magazine_empty", ModKeybinds.RELOAD);
            return;
        }
        AmmoExtension ext = AmmoExtension.get(ammoId);
        long interval = (long) (60000.0 / Math.max(1, ext.fireRate * stats.fireRateMultiplier));
        if (now - lastLocalShotMs < interval) return;
        if (reloading) return;

        // burst: single request per click for now (server sequences the burst)
        CptNetwork.CHANNEL.sendToServer(new FireRequestPacket());
        lastLocalShotMs = now;
        wasFiring = true;

        // local feel: recoil + bloom + fire animation
        double recoilMult = stats.recoilMultiplier;
        boolean aiming = ModKeybinds.isAiming();
        RecoilModel.onShot(stats.receiver.baseRecoilPitch, stats.receiver.baseRecoilYaw, recoilMult, aiming);
        SpreadModel.addBloom(ext);
        GunAnimationDriver.onFire(gun);
    }

    /**
     * Actionbar hint for client-side fire rejection; key names resolve from the
     * player's actual keybinds, never hardcoded.
     */
    private static void feedback(Player player, String key, net.minecraft.client.KeyMapping... hints) {
        net.minecraft.network.chat.MutableComponent c = net.minecraft.network.chat.Component.translatable(
                "gui." + CreatePneumaticTacticals.MODID + ".fire_fail." + key);
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

    private static void startReload(ItemStack gun, GunStats stats) {
        if (reloading || !stats.isComplete() || stats.feed == null
                || stats.feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.BACKPACK) return;
        if (GunNbt.getAmmoCount(gun) >= stats.feed.clipSize) return; // already full
        reloadRoundMode = stats.feed.feedType == dev.ignis.createpneumatictacticals.module.FeedType.ROUND;
        // duration = receiver animation length (+ bolt when empty) / reload speed
        boolean empty = GunNbt.getAmmoCount(gun) <= 0;
        reloadBatchMs = dev.ignis.createpneumatictacticals.client.render.GunAnimTiming
                .reloadBatchMs(gun, reloadRoundMode, empty, stats.reloadSpeed);
        reloadEndMs = System.currentTimeMillis() + reloadBatchMs;
        reloadBatch = reloadRoundMode ? Math.max(1, stats.feed.loadAmount) : stats.feed.clipSize;
        reloadingGun = gun;
        reloading = true;
        GunAnimationDriver.onReloadStart();
    }

    /**
     * Interruption semantics: magazine reload fails outright (no packet = no
     * ammo); round reload applies per-batch — each finished batch sends its own
     * packet, an interruption only loses the in-flight batch. Hold R to keep
     * loading round-by-round. The gun stack reference doubles as the
     * "same gun" check: switching slots / dropping / stowing replaces it.
     */
    private static void tickReload(Player player, ItemStack gun, GunStats stats) {
        if (!reloading) return;
        if (gun != reloadingGun || mc_attackDown()) {
            reloading = false;
            return;
        }
        if (System.currentTimeMillis() < reloadEndMs) return;
        // batch finished -> apply immediately
        CptNetwork.CHANNEL.sendToServer(new ReloadResultPacket(true, reloadBatch));
        if (reloadRoundMode && ModKeybinds.RELOAD.isDown()
                && GunNbt.getAmmoCount(gun) < stats.feed.clipSize) {
            // next batch: no bolt cycle (chamber already loaded)
            reloadEndMs = System.currentTimeMillis() + dev.ignis.createpneumatictacticals.client.render
                    .GunAnimTiming.reloadBatchMs(gun, true, false, stats.reloadSpeed);
        } else {
            reloading = false;
        }
    }

    private static boolean mc_attackDown() {
        return Minecraft.getInstance().options.keyAttack.isDown();
    }
}