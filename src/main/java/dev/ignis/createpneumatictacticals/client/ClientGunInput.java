package dev.ignis.createpneumatictacticals.client;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.gun.GunStats;
import dev.ignis.createpneumatictacticals.network.CptNetwork;
import dev.ignis.createpneumatictacticals.network.FireRequestPacket;
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

    private ClientGunInput() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.screen != null) return;

        ItemStack gun = player.getMainHandItem();
        boolean holdingGun = gun.getItem() instanceof dev.ignis.createpneumatictacticals.item.GeoGunItem;
        if (!holdingGun) {
            wasFiring = false;
            return;
        }

        GunStats stats = GunStats.of(GunNbt.readModules(gun));

        // --- fire ---
        if (mc.options.keyAttack.isDown()) {
            tryFire(player, gun, stats);
        } else {
            wasFiring = false;
        }

        // --- reload ---
        if (ModKeybinds.RELOAD.isDown()) {
            startReload();
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
        if (!stats.isComplete()) return;
        FireMode mode = GunNbt.getFireMode(gun);
        long now = System.currentTimeMillis();

        boolean semi = mode == FireMode.SEMI;
        if (semi && wasFiring) return;

        // local rate limit for responsiveness; server validates authoritatively
        String ammoId = GunNbt.getAmmo(gun);
        if (ammoId == null || ammoId.isEmpty()) return;
        AmmoExtension ext = AmmoExtension.get(ammoId);
        long interval = (long) (60000.0 / Math.max(1, ext.fireRate * stats.fireRateMultiplier));
        if (now - lastLocalShotMs < interval) return;

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

    private static void startReload() {
        // reload timer/animation state is client-side (server validates result packet)
        GunAnimationDriver.onReloadStart();
    }
}