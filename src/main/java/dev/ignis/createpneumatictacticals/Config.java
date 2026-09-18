package dev.ignis.createpneumatictacticals;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Client-side configuration. Server/global values live in a common config later.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue RECOIL_VIEW_RESET = CLIENT_BUILDER
            .comment("Whether the view snaps back after recoil")
            .define("recoilViewReset", false);

    public static final ForgeConfigSpec.IntValue READY_DELAY_MS = CLIENT_BUILDER
            .comment("Delay in ms before the gun can fire after leaving low/high ready pose")
            .defineInRange("readyDelayMs", 250, 0, 2000);

    public static final ForgeConfigSpec.BooleanValue HITMARKER_ENABLED = CLIENT_BUILDER
            .comment("Show hitmarker on successful hits")
            .define("hitmarkerEnabled", true);

    static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    public static boolean recoilViewReset;
    public static int readyDelayMs;
    public static boolean hitmarkerEnabled;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == CLIENT_SPEC) {
            recoilViewReset = RECOIL_VIEW_RESET.get();
            readyDelayMs = READY_DELAY_MS.get();
            hitmarkerEnabled = HITMARKER_ENABLED.get();
        }
    }
}
