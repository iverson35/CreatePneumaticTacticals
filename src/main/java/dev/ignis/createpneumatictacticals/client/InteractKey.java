package dev.ignis.createpneumatictacticals.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.gun.InteractPass;
import dev.ignis.createpneumatictacticals.item.GeoGunItem;
import dev.ignis.createpneumatictacticals.mixin.MinecraftInvoker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Interact key (default middle mouse): while — and only while — a gun is held
 * in the main hand, pressing it performs the vanilla right-click use that
 * aiming otherwise swallows (open chests, press buttons, trade, use the
 * offhand item, ...).
 *
 * <p>It runs vanilla's own {@code Minecraft.startUseItem()} with
 * {@link InteractPass} set, so the gun's CONSUME backstops act as PASS for that
 * one flow and {@link AimHandler} skips its use-click cancellation.
 * Press-triggered (same as TACZ): holding the key does not repeat.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class InteractKey {

    private InteractKey() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (!ModKeybinds.INTERACT.consumeClick()) return;
        if (!(mc.player.getMainHandItem().getItem() instanceof GeoGunItem)) return;
        // 3D workbench: right-click routes there while a gun is held, so the
        // interact key does the same instead of a vanilla use
        if (AimHandler.tryBenchRightClick(mc)) return;
        InteractPass.begin();
        try {
            ((MinecraftInvoker) mc).cpt$startUseItem();
        } finally {
            InteractPass.end();
        }
    }

    /** True when the interact key sits on the same physical input as {@code other}. */
    static boolean sharesInput(KeyMapping other) {
        InputConstants.Key mine = ModKeybinds.INTERACT.getKey();
        InputConstants.Key theirs = other.getKey();
        return mine.getType() == theirs.getType() && mine.getValue() == theirs.getValue();
    }
}
