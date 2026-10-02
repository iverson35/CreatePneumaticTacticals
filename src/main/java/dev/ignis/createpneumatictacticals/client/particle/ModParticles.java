package dev.ignis.createpneumatictacticals.client.particle;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Particle type registration. Providers are bound client-side in
 * {@code ClientModEvents#onRegisterParticles} (RegisterParticleProvidersEvent).
 */
public final class ModParticles {
    private static final DeferredRegister<ParticleType<?>> PARTICLES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<ParticleType<?>> MUZZLE_SMOKE =
            PARTICLES.register("muzzle_smoke", () -> new SimpleParticleType(false));

    /**
     * Tracer-trail dot: a poof that is smaller than vanilla's, has no gravity
     * and no self-drift, and dissolves in {@code TrailSmokeParticle.LIFETIME}
     * ticks. Used by the built-in ammo presets for medium/heavy rounds.
     */
    public static final RegistryObject<ParticleType<?>> TRAIL_SMOKE =
            PARTICLES.register("trail_smoke", () -> new SimpleParticleType(false));

    private ModParticles() {}

    public static void register(IEventBus modBus) {
        PARTICLES.register(modBus);
    }
}