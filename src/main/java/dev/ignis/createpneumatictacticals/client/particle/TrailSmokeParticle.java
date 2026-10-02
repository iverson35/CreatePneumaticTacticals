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
 * Tracer-trail dot: a poof that stays put.
 *
 * <p>Vanilla's poof (ExplodeParticle) is a poor trail dot. It is born with
 * quads up to 0.7 blocks and 18-82 ticks of life, it adds a random +-0.05
 * velocity of its own on top of the spawn velocity, and its gravity is -0.1,
 * so its dots wander sideways and float upward - and because the trail spawns
 * them with a zero velocity, that random term is the only motion they have.
 * This one keeps the motion it is given (zero for a trail point, so each dot
 * hangs exactly where it was spawned), holds a fixed small size, and
 * dissolves in {@value #LIFETIME_TICKS} ticks while the sprite walks
 * generic_4 down to generic_0.
 */
@OnlyIn(Dist.CLIENT)
public class TrailSmokeParticle extends TextureSheetParticle {

    /** ~half of vanilla poof's mean quad, and no random growth */
    private static final float SIZE = 0.2f;
    /** 0.4s: a dot on the flight path, not a lingering cloud */
    private static final int LIFETIME_TICKS = 8;

    private final SpriteSet sprites;

    protected TrailSmokeParticle(ClientLevel level, double x, double y, double z,
                                 double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.friction = 0.9f;
        this.gravity = 0.0f;   // no rise: a trail dot marks a spot, it does not drift
        this.xd = vx;          // as spawned - no random velocity of our own
        this.yd = vy;
        this.zd = vz;
        this.quadSize = SIZE;
        this.lifetime = LIFETIME_TICKS;
        this.rCol = this.gCol = this.bCol = 1.0f; // pure white, no per-particle tint
        this.hasPhysics = false;
        this.setSprite(sprites.get(0, LIFETIME_TICKS)); // densest frame
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
            return new TrailSmokeParticle(level, x, y, z, vx, vy, vz, sprites);
        }
    }
}
