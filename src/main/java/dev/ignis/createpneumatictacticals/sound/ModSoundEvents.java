package dev.ignis.createpneumatictacticals.sound;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Mod-registered sound events. The ogg files live in the mod's own assets
 * ({@code assets/<modid>/sounds/}); this is separate from gunpack sounds
 * ({@link dev.ignis.createpneumatictacticals.gunpack.GunpackSounds}), which
 * are registered dynamically from installed packs.
 */
public final class ModSoundEvents {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, CreatePneumaticTacticals.MODID);

    /** shell casing landing click; 5 variants picked at random by vanilla's sounds.json multi-entry mechanism */
    public static final RegistryObject<SoundEvent> SHELL_DROP = SOUNDS.register("shell_drop",
            () -> SoundEvent.createVariableRangeEvent(
                    new ResourceLocation(CreatePneumaticTacticals.MODID, "shell_drop")));

    /** dry-fire click: the trigger was pulled with nothing loaded (empty
     *  magazine, or mid-reload); 50% loudness from the sounds.json entry */
    public static final RegistryObject<SoundEvent> AMMO_EMPTY = SOUNDS.register("ammo_empty",
            () -> SoundEvent.createVariableRangeEvent(
                    new ResourceLocation(CreatePneumaticTacticals.MODID, "ammo_empty")));

    /** fire-mode selector click (V); 50% loudness from the sounds.json entry */
    public static final RegistryObject<SoundEvent> SWITCH_FIREMODE = SOUNDS.register("switch_firemode",
            () -> SoundEvent.createVariableRangeEvent(
                    new ResourceLocation(CreatePneumaticTacticals.MODID, "switch_firemode")));

    /** workbench: a module was installed onto the gun; 50% loudness from the
     *  sounds.json entry */
    public static final RegistryObject<SoundEvent> MODULE_ASSEMBLE = SOUNDS.register("module_assemble",
            () -> SoundEvent.createVariableRangeEvent(
                    new ResourceLocation(CreatePneumaticTacticals.MODID, "module_assemble")));

    /** workbench: a module was pulled off the gun; 50% loudness from the
     *  sounds.json entry */
    public static final RegistryObject<SoundEvent> MODULE_DISASSEMBLE = SOUNDS.register("module_disassemble",
            () -> SoundEvent.createVariableRangeEvent(
                    new ResourceLocation(CreatePneumaticTacticals.MODID, "module_disassemble")));

    private ModSoundEvents() {}

    public static void register(IEventBus bus) {
        SOUNDS.register(bus);
    }
}