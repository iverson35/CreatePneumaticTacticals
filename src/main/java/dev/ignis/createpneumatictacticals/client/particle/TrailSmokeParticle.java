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
 * Tracer-trail dot: a poof that is shed, not printed.
 *
 * <p>Vanilla's poof (ExplodeParticle) is a poor trail dot. It is born with
 * quads up to 0.7 blocks and 18-82 ticks of life, it adds a random +-0.05
 * velocity of its own on top of the spawn velocity, and its gravity is -0.1,
 * so its dots wander sideways and float upward. This one takes its motion
 * from the spawn call (the trail gives each puff a small forward drift plus a
 * sideways fan), never adds any of its own, and holds a small size that
 * shrinks to nothing over 7-10 ticks while the sprite walks generic_4 down to
 * generic_0.
 *
 * <p>Size (+-15%), lifetime (7-10 ticks) and quad roll vary per puff, because
 * a row of identical motionless quads reads as a dotted line, not as smoke.
 */
@OnlyIn(Dist.CLIENT)
public class TrailSmokeParticle extends TextureSheetParticle {

    /** base size (~half of vanilla poof's mean quad); each puff varies +-15% */
    private static final float SIZE = 0.2f;
    /** base life; each puff lives 7-10 ticks (0.35-0.5s) */
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
        this.quadSize = SIZE * (0.85f + this.random.nextFloat() * 0.3f);
        this.lifetime = LIFETIME_TICKS - 1 + this.random.nextInt(4); // 7-10
        this.rCol = this.gCol = this.bCol = 1.0f; // pure white, no per-particle tint
        this.hasPhysics = false;
        // random roll: a row of identically-upright quads reads as printed dots
        this.roll = this.oRoll = this.random.nextFloat() * ((float) Math.PI * 2.0f);
        this.setSprite(sprites.get(0, this.lifetime)); // densest frame
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
