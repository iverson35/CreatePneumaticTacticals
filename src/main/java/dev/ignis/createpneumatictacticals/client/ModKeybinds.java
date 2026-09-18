package dev.ignis.createpneumatictacticals.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * All gun keybinds, registered as Forge KeyMappings (rebindable) via
 * ClientModEvents#onRegisterKeys.
 */
public final class ModKeybinds {

    public static final KeyMapping RELOAD = register("reload", GLFW.GLFW_KEY_R);
    public static final KeyMapping FIRE_MODE = register("fire_mode", GLFW.GLFW_KEY_V);
    public static final KeyMapping AIM_STANCE = register("aim_stance", GLFW.GLFW_KEY_X);
    public static final KeyMapping CYCLE_AMMO = register("cycle_ammo", GLFW.GLFW_KEY_O);

    private ModKeybinds() {}

    /** True while aiming (holding a gun + right mouse); see AimHandler. */
    public static boolean isAiming() {
        return AimHandler.isAiming();
    }

    private static KeyMapping register(String name, int key) {
        return new KeyMapping("key." + CreatePneumaticTacticals.MODID + "." + name,
                KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key,
                "key.categories." + CreatePneumaticTacticals.MODID);
    }
}