package dev.ignis.createpneumatictacticals.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Muzzle smoke puff: the vision-blocking cloud at the gun tip.
 *
 * <p>Unlike vanilla smoke/poof (BaseAshSmokeParticle randomizes quadSize
 * 0.75–1.5x and lifetime 20–100% of the base), this particle is DETERMINISTIC:
 * every puff is the same size, fades out over a fixed lifetime, and drifts
 * slowly. Uniform, small, slow — a compact cloud that reads as one dense mass
 * instead of a few huge random blobs.
 */
@OnlyIn(Dist.CLIENT)
public class MuzzleSmokeParticle extends TextureSheetParticle {

    private static final float SIZE = 0.22f;   // ~2.8x smaller than vanilla smoke quads
    private static final int LIFETIME_TICKS = 12; // 0.6s: dense then gone

    private final SpriteSet sprites;

    protected MuzzleSmokeParticle(ClientLevel level, double x, double y, double z,
                                  double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.friction = 0.9f;      // decays the initial velocity quickly
        this.gravity = 0.0f;
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.quadSize = SIZE;
        this.lifetime = LIFETIME_TICKS;
        this.rCol = this.gCol = this.bCol = 1.0f; // pure white, no per-particle tint
        this.setSprite(sprites.get(LIFETIME_TICKS - 1, LIFETIME_TICKS)); // densest frame
        this.hasPhysics = false;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    @Override
    public float getQuadSize(float partialTick) {
        // fade out by shrinking, no random growth
        float t = (age + partialTick) / (float) lifetime;
        return quadSize * (1.0f - t * t);
    }

    @Override
    public void tick() {
        super.tick();
        setSprite(sprites.get(age, lifetime));
    }

    @OnlyIn(Dist.CLIENT)
    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new MuzzleSmokeParticle(level, x, y, z, vx, vy, vz, sprites);
        }
    }
}