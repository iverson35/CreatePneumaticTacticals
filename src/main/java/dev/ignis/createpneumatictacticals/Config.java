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

    public static final ForgeConfigSpec.BooleanValue BLOOD_PARTICLES = CLIENT_BUILDER
            .comment("Show blood on bullet hits; when false, hits spray green lily-pad sap instead")
            .define("bloodParticles", true);

    public static final ForgeConfigSpec.BooleanValue HITMARKER_ENABLED = CLIENT_BUILDER
            .comment("Show hitmarker on successful hits")
            .define("hitmarkerEnabled", true);

    public static final ForgeConfigSpec.BooleanValue GUN_HUD_ENABLED = CLIENT_BUILDER
            .comment("Show the gun ammo widget while holding a gun")
            .define("gunHudEnabled", true);

    public static final ForgeConfigSpec.BooleanValue GUN_CROSSHAIR_ENABLED = CLIENT_BUILDER
            .comment("Show the dynamic gun crosshair while holding a gun")
            .define("gunCrosshairEnabled", true);

    public static final ForgeConfigSpec.IntValue GUN_HUD_OFFSET_X = CLIENT_BUILDER
            .comment("Horizontal offset of the gun ammo widget (from the right edge)")
            .defineInRange("gunHudOffsetX", 0, -2000, 2000);

    public static final ForgeConfigSpec.IntValue GUN_HUD_OFFSET_Y = CLIENT_BUILDER
            .comment("Vertical offset of the gun ammo widget (from the bottom edge)")
            .defineInRange("gunHudOffsetY", 0, -2000, 2000);

    public static final ForgeConfigSpec.BooleanValue CHARM_PHYSICS = CLIENT_BUILDER
            .comment("Simulate charm/pendant swing in first person; when false, charms hang in their rest pose")
            .define("charmPhysics", true);

    public static final ForgeConfigSpec.BooleanValue GUN_ATLAS = CLIENT_BUILDER
            .comment("Merge gun part textures into one runtime atlas (big FPS win with many guns on screen)")
            .define("gunAtlas", true);

    public static final ForgeConfigSpec.IntValue LOD_DISTANCE = CLIENT_BUILDER
            .comment("Camera distance in blocks beyond which cosmetic gun modules (sights, muzzle devices, charms, rail attachments) are not rendered; 0 disables")
            .defineInRange("lodDistance", 32, 0, 256);

    public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    public static boolean bloodParticles;
    public static boolean hitmarkerEnabled;
    public static boolean gunHudEnabled;
    public static boolean gunCrosshairEnabled;
    public static int gunHudOffsetX;
    public static int gunHudOffsetY;
    public static boolean charmPhysics;
    public static boolean gunAtlas;
    public static int lodDistance;


    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == CLIENT_SPEC) {
            bloodParticles = BLOOD_PARTICLES.get();
            hitmarkerEnabled = HITMARKER_ENABLED.get();
            gunHudEnabled = GUN_HUD_ENABLED.get();
            gunCrosshairEnabled = GUN_CROSSHAIR_ENABLED.get();
            gunHudOffsetX = GUN_HUD_OFFSET_X.get();
            gunHudOffsetY = GUN_HUD_OFFSET_Y.get();
            charmPhysics = CHARM_PHYSICS.get();
            gunAtlas = GUN_ATLAS.get();
            lodDistance = LOD_DISTANCE.get();
        }
    }
}
