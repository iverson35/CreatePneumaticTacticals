package dev.ignis.createpneumatictacticals.client.particle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;

/**
 * Blood drop: a 4x4-pixel patch of a block sprite with hand-rolled physics.
 *
 * <p>Vanilla break particles do not draw the whole block texture - they draw a
 * random quarter-sprite patch of it, which is what makes them read as
 * fragments. Copying that matters for the nether wart crop in particular: its
 * sprite is mostly transparent, so a full-sprite quad shows a floating plant
 * instead of a red speck. Patches that land on empty space are re-rolled, or
 * roughly half the drops would be invisible.
 *
 * <p>Why not a vanilla TerrainParticle: its
 * {@code Particle(level, x, y, z, xd, yd, zd)} constructor THROWS THE MAGNITUDE
 * AWAY. It keeps only the direction, adds +/-0.4 of random jitter per axis and
 * rescales the lot to 0.06-0.18 blocks/tick (plus a +0.1 lift), so every drop
 * came out the same slow speed whatever was handed in - a jet could never be
 * told apart from a sputter. Extending {@link TextureSheetParticle} and writing
 * xd/yd/zd directly after the 4-argument super constructor keeps the launch
 * velocity exactly as authored.
 *
 * <p>Never registered as a particle type: the spray builds these straight into
 * {@code ParticleEngine}, so there is no ParticleType, provider or codec to
 * keep in sync.
 */
public class BloodParticle extends TextureSheetParticle {

    /** patch origin in quarter-sprite units (0..3), as in TerrainParticle */
    private final float uo;
    private final float vo;

    public BloodParticle(ClientLevel level, double x, double y, double z,
                         double xd, double yd, double zd, int life, boolean blood) {
        super(level, x, y, z);
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        // bloodParticles=false swaps the red crop sprite for green lily-pad sap
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper()
                .getParticleIcon(blood
                        ? Blocks.NETHER_WART.defaultBlockState().setValue(NetherWartBlock.AGE, 3)
                        : Blocks.LILY_PAD.defaultBlockState());
        this.setSprite(sprite);
        int px = 0;
        int py = 0;
        for (int attempt = 0; attempt < 8; attempt++) {
            px = this.random.nextInt(4) * 4;
            py = this.random.nextInt(4) * 4;
            if (hasPixels(sprite, px, py)) break;
        }
        this.uo = px / 4.0F;
        this.vo = py / 4.0F;
        // terrain-particle physics: falls at ~2 blocks/tick terminal
        this.gravity = 1.0F;
        this.friction = 0.98F;
        this.setLifetime(life);
        this.quadSize = 0.12F;
        // a touch darker than the raw sprite so it reads as blood, not leaves
        this.rCol = 0.85F;
        this.gCol = 0.85F;
        this.bCol = 0.85F;
    }

    /** a patch is worth showing once a couple of its pixels are opaque */
    private static boolean hasPixels(TextureAtlasSprite sprite, int px, int py) {
        int hits = 0;
        for (int y = py; y < py + 4; y++) {
            for (int x = px; x < px + 4; x++) {
                if ((sprite.getPixelRGBA(0, x, y) >>> 24) > 60) hits++;
            }
        }
        return hits >= 3;
    }

    // TerrainParticle's patch UVs; the sprite's V axis is flipped, hence uo+1 on u0
    @Override
    protected float getU0() {
        return this.sprite.getU((this.uo + 1.0F) / 4.0F * 16.0F);
    }

    @Override
    protected float getU1() {
        return this.sprite.getU(this.uo / 4.0F * 16.0F);
    }

    @Override
    protected float getV0() {
        return this.sprite.getV(this.vo / 4.0F * 16.0F);
    }

    @Override
    protected float getV1() {
        return this.sprite.getV((this.vo + 1.0F) / 4.0F * 16.0F);
    }

    /** block-atlas sprite: must render through the terrain sheet */
    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.TERRAIN_SHEET;
    }
}
