package dev.ignis.createpneumatictacticals.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.ignis.createpneumatictacticals.block.MachineLayout;
import dev.ignis.createpneumatictacticals.block.WeaponModuleMachineBlock;
import dev.ignis.createpneumatictacticals.block.entity.WeaponModuleMachineBlockEntity;
import dev.ignis.createpneumatictacticals.item.ModuleItem;
import dev.ignis.createpneumatictacticals.recipe.ModuleCraftingRecipe;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperBufferFactory;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws the whole machine (both cells) from the master block entity: the
 * two-cell model is placed once, at the master cell's centre, and rotated so
 * the front — the screen panel and the sliding door — faces the block state's
 * {@code FACING} (see {@link MachineLayout} for the mirrored model space).
 *
 * <p>The moving parts are written onto the shared baked model's bones per frame
 * and the model is restored afterwards:
 * <ul>
 *   <li>the door slides {@link #DOOR_SLIDE_PX} px along model -Z with the OPEN
 *       block state (eased over {@link #DOOR_EASE_TICKS} ticks client-side, the
 *       server only flips the property);</li>
 *   <li>while a job runs the clamp feeds the part's whole length past the bit
 *       (slow, monotone) while the bit sweeps across the part's width once per
 *       pass (fast) — a serpentine that works over the whole top face, with
 *       both amplitudes taken from the part's own size, so a small part gets a
 *       small motion — and the bit descends with the shrinking material; when
 *       the job stops the mechanism eases back to rest instead of snapping;</li>
 *   <li>the workpiece — the module being built, scaled into a
 *       {@link #WORKPIECE_MAX_Z_PX}&times;{@link #WORKPIECE_MAX_Y_PX}&times;{@link #WORKPIECE_MAX_X_PX} px
 *       box, bottom centre on the clamp — is wrapped in a block of the module's
 *       own dominant colour and of {@link #WRAP_TEXTURE} whose height follows
 *       the progress: the material is "eaten away" from the top down;</li>
 *   <li>the selected recipe's part is drawn flat on the monitor: an offscreen
 *       orthographic picture ({@link MachineScreenTexture}) shown on the plate
 *       as one quad, unlit and unshaded like a panel image — and only within
 *       {@link MachineScreenTexture#MAX_VISIBLE_BLOCKS} blocks, where it can
 *       actually be read;</li>
 * </ul>
 */
public final class WeaponModuleMachineRenderer implements BlockEntityRenderer<WeaponModuleMachineBlockEntity> {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** one-shot: a monitor that fails every frame must not flood the log */
    private static boolean screenFailureLogged;

    /**
     * The raw material the part is cut out of: a sheet whose four corners
     * supply the four quarters of every face of the shell (see
     * {@link #tiledFace}), tinted with the module's own colour.
     */
    private static final ResourceLocation WRAP_TEXTURE = new ResourceLocation(
            dev.ignis.createpneumatictacticals.CreatePneumaticTacticals.MODID,
            "textures/block/raw_material.png");

    private static final RenderType WRAP_TYPE = RenderType.entityCutoutNoCull(WRAP_TEXTURE);

    /**
     * Sliding door travel, in pixels, along model -Z: the door slides deeper
     * into its own half of the frame (the panel spans model z -15.5..-5.5 at
     * rest, the frame's far wall is at z -23.5), away from the clamp and the
     * monitor.
     */
    private static final float DOOR_SLIDE_PX = -8f;
    /** door easing length in ticks (visual half of the OPEN state change) */
    private static final float DOOR_EASE_TICKS = 10f;

    /** drill base vertical travel, in pixels */
    private static final float BASE_Y_MAX_PX = 3f;
    /**
     * How far apart the bit's passes sit: the cross-feed sweeps the part's width
     * once per pass, and a job gets one pass per this many pixels of part length
     * (clamped, see {@link #machineMechanism}) — a longer part therefore takes
     * more passes, like a real surface cut.
     */
    private static final float PASS_STEP_PX = 0.8f;
    /**
     * How many width sweeps the bit makes while the clamp feeds one lengthwise
     * pass: the bit crosses the part faster than the clamp moves it, so the cut
     * comes out as a serpentine that covers the whole layer.
     */
    private static final float SWEEPS_PER_PASS = 3f;
    /** mechanism run-in / settle easing length in ticks */
    private static final float MECH_EASE_TICKS = 10f;
    /** bit spin about its own axis, degrees per tick at the 32 RPM baseline (converted to radians at use) */
    private static final float SPIN_DEG_PER_TICK = 45f;
    /** shaft radians -> bit radians: the bit is geared up from the shaft's 9.6 deg/tick at 32 RPM */
    private static final float SPIN_GEAR = SPIN_DEG_PER_TICK / 9.6f;

    /** workpiece bounding box cap in pixels: longer modules are scaled down to fit the clamp */
    private static final float WORKPIECE_MAX_Z_PX = 10f;
    private static final float WORKPIECE_MAX_Y_PX = 5f;
    private static final float WORKPIECE_MAX_X_PX = 5f;
    /** drawn monitor plate in pixels: 3 wide (model z) by 4 tall (model y), see MachineLayout.SCREEN_* */
    static final float SCREEN_W_PX = 3f, SCREEN_H_PX = 4f;
    /** the picture floats this far in front of the plate, in pixels (no z-fighting) */
    static final float SCREEN_LIFT_PX = 0.2f;
    /** the material shell is drawn this much larger than the part, in pixels */
    static final float WRAP_INFLATE_PX = 0.25f;
    /** monitor pictures kept at once: one per machine on screen, oldest destroyed */
    private static final int MAX_SCREEN_PICTURES = 12;

    /** per-machine monitor pictures, least recently drawn first */
    private static final Map<BlockPos, MachineScreenTexture> SCREEN_PICTURES =
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<BlockPos, MachineScreenTexture> eldest) {
                    if (size() <= MAX_SCREEN_PICTURES) return false;
                    eldest.getValue().destroy();
                    return true;
                }
            };

    /** module texture -> dominant colour (see {@link #dominantColor}); cleared on resource reload */
    private static final Map<ResourceLocation, Integer> COLOR_CACHE = new HashMap<>();

    /** {@link #WRAP_TEXTURE}'s shorter side in texels, read once (0 until read) */
    private static float wrapTexels;

    /**
     * The drive shaft's stub buffers, one per world face the stub can point at.
     * The stub's pose — Create's {@code rotateToFace} plus the squash that cuts
     * the 8 px half-shaft down to {@link MachineLayout#SHAFT_STUB_PX} — is baked
     * into the vertices, and none of it depends on the block state (a partial
     * model carries no variants, tints or culling), so one buffer per face
     * serves every machine and every frame. Not
     * {@code CachedBuffers.partialDirectional}: that cache is keyed by
     * (direction, partial) alone, so a Create machine drawing the same
     * {@code SHAFT_HALF} toward the same face would either take this squash or
     * hand out its own un-squashed 8 px shaft, depending on who baked first.
     */
    private static final Map<Direction, SuperByteBuffer> STUB_BUFFERS = new EnumMap<>(Direction.class);

    public WeaponModuleMachineRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** resource reload: the module textures (and therefore the wrap colours) changed */
    public static void invalidateCaches() {
        synchronized (COLOR_CACHE) {
            COLOR_CACHE.clear();
        }
        wrapTexels = 0f;
        // the stub buffers hold sprite UVs from the old atlas
        for (SuperByteBuffer stub : STUB_BUFFERS.values()) {
            stub.delete();
        }
        STUB_BUFFERS.clear();
        // the monitor pictures were rendered from the old textures/atlas
        for (MachineScreenTexture picture : SCREEN_PICTURES.values()) {
            picture.destroy();
        }
        SCREEN_PICTURES.clear();
    }

    @Override
    public void render(WeaponModuleMachineBlockEntity machine, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BakedGeoModel baked = MachineModelDraw.bakedModel();
        if (baked == null) return;
        BlockState state = machine.getBlockState();

        // the drive shaft is Create's own model: it lives in block-local space,
        // not in the machine model's mirrored one, so it is drawn before the
        // pose is turned into model space
        drawDriveShaft(machine, state, poseStack, buffer, packedLight);

        // the part being built is the recipe's RESULT (a module id), not the
        // recipe id: ModuleItem.of(recipeId) resolves no artwork at all
        ModuleCraftingRecipe recipe = machine.currentRecipe();
        ItemStack workpiece = recipe == null ? ItemStack.EMPTY : recipe.resultStack();
        Workpiece metrics = workpiece.isEmpty() ? null : workpieceMetrics(workpiece);

        poseStack.pushPose();
        poseStack.translate(0.5, 0.0, 0.5);
        poseStack.mulPose(Axis.YP.rotation(MachineLayout.yawRadians(MachineLayout.facing(state))));
        // the baked model is shared with the item icon pass: draw at rest, then
        // restore — restoring (not re-resetting) keeps the same-frame item pass intact
        Map<CoreGeoBone, float[]> saved = GunAnimations.snapshotBones(MachineGeoModel.INSTANCE);
        GunAnimations.resetToRestPose(MachineGeoModel.INSTANCE);
        try {
            float now = machine.getLevel() == null
                    ? 0f : machine.getLevel().getGameTime() + partialTick;
            float door = doorProgress(machine, state, now);
            offsetBone(baked, "sliding_door", 0f, 0f, DOOR_SLIDE_PX * door);

            CoreGeoBone fixture = baked.getBone("fixture").orElse(null);
            // the part sits in the clamp from the moment the materials are in
            // place until the finished module is taken out of the output slot —
            // it must not vanish the tick the job ends
            boolean partInClamp = metrics != null && fixture != null
                    && (machine.isCrafting() || machine.hasOutput());
            boolean machining = machine.isCrafting() && partInClamp;
            // 1 = no raw material left: a finished part waits bare in the clamp
            float progress = machine.isCrafting() ? machine.craftProgress(partialTick) : 1f;
            // the raw material in the clamp: full while the job cuts it away, and
            // grown piece by piece while the materials are loaded before the job
            float rawFill = machining ? 1f - progress : machine.materialsFraction();
            boolean rawInClamp = metrics != null && fixture != null && rawFill > 0f;
            // mechanism offsets: the job's own cutting path while it runs, then a
            // short eased return to rest once it stops (see #machineMechanism)
            machineMechanism(machine, partInClamp ? metrics : null, fixture, baked, progress, machining, now);
            offsetBone(baked, "fixture", 0f, 0f, machine.mechFixZ);
            offsetBone(baked, "cutting_tool_base", machine.mechBaseX, machine.mechBaseY, 0f);
            if (machining) {
                // the bit is on the same shaft as the machine: its angle is Create's
                // own kinetic angle (the one the shaft stubs are drawn with), geared
                // up — so it reverses with the drive direction and stops with it
                float shaftAngle = KineticBlockEntityRenderer.getAngleForBe(machine, machine.getBlockPos(),
                        MachineLayout.shaftAxis(MachineLayout.facing(state)));
                offsetBoneRotation(baked, "cutting_tool", 0f, 0f, shaftAngle * SPIN_GEAR);
            }

            MachineModelDraw.INSTANCE.draw(baked, poseStack, buffer, packedLight, packedOverlay);

            if (partInClamp || rawInClamp) {
                // the clamp only feeds along its own axis; x stays centred
                drawWorkpiece(metrics, rawFill, partInClamp, fixture, 0f, machine.mechFixZ,
                        poseStack, buffer, partialTick, packedLight, packedOverlay);
            }
            // the monitor shows the selected part whether or not the job runs
            drawScreen(machine, metrics, baked, poseStack, buffer, now);
        } finally {
            GunAnimations.restoreBones(saved);
            poseStack.popPose();
        }
    }

    /** the machine stands two cells out: draw it from further away than a normal block */
    @Override
    public int getViewDistance() {
        return 64;
    }

    /**
     * Door position 0..1, eased over {@link #DOOR_EASE_TICKS} ticks. Client-only
     * interpolation of the OPEN block state — exactly one property on the wire.
     */
    private static float doorProgress(WeaponModuleMachineBlockEntity machine, BlockState state, float now) {
        boolean open = state.getValue(WeaponModuleMachineBlock.OPEN);
        if (machine.doorAnimTarget != open) {
            machine.doorAnimTarget = open;
            machine.doorAnimFrom = machine.doorAnimValue;
            machine.doorAnimStart = now;
        }
        float t = Mth.clamp((float) ((now - machine.doorAnimStart) / DOOR_EASE_TICKS), 0f, 1f);
        float eased = t * t * (3f - 2f * t);
        machine.doorAnimValue = machine.doorAnimFrom + ((open ? 1f : 0f) - machine.doorAnimFrom) * eased;
        return machine.doorAnimValue;
    }

    /** [-1,1] triangle wave of period 1 */
    private static float triangle(float t) {
        float f = t - (float) Math.floor(t);
        return f < 0.5f ? -1f + 4f * f : 3f - 4f * f;
    }

    /** bone offsets are ADDED to the rest pose (every frame starts from a reset) */
    private static void offsetBone(BakedGeoModel baked, String name, float x, float y, float z) {
        baked.getBone(name).ifPresent(bone -> {
            bone.setPosX(bone.getPosX() + x);
            bone.setPosY(bone.getPosY() + y);
            bone.setPosZ(bone.getPosZ() + z);
        });
    }

    /**
     * Bone rotations are ADDED to the rest pose as well. GeckoLib stores bone
     * rotations in RADIANS (the model bake and the animation loader both store
     * {@code toRadians} values, and {@code RenderUtils.rotateMatrixAroundBone}
     * feeds them straight to {@code Axis.rotation}), so callers convert.
     */
    private static void offsetBoneRotation(BakedGeoModel baked, String name, float x, float y, float z) {
        baked.getBone(name).ifPresent(bone -> {
            bone.setRotX(bone.getRotX() + x);
            bone.setRotY(bone.getRotY() + y);
            bone.setRotZ(bone.getRotZ() + z);
        });
    }

    /**
     * Move the mechanism along the job's cutting path, or ease it back to rest.
     *
     * <p>Two motions, both taken from the part's own size so a small part gets a
     * small, quick job: the clamp feeds the part's whole length past the bit and
     * back once per pass (a triangle wave over {@code passes} cycles), while the
     * bit sweeps across the part's width {@link #SWEEPS_PER_PASS} times per pass
     * and rides down the material's shrinking top face. Nothing is stored: the
     * whole path is a function of the synced job progress, so it is frame-smooth
     * and identical on every client. Starting and stopping are eased over
     * {@link #MECH_EASE_TICKS} so the mechanism never snaps.
     */
    private static void machineMechanism(WeaponModuleMachineBlockEntity machine, Workpiece wp,
                                         CoreGeoBone fixture, BakedGeoModel baked,
                                         float progress, boolean machining, float now) {
        if (machining && wp != null && fixture != null) {
            if (!machine.mechRunning) {
                machine.mechRunning = true;
                machine.mechEaseStart = now;
                snapshotMechanism(machine);
            }
            float feedZ = wp.depthPx() * 0.5f;      // the clamp feeds the part's whole length past the bit
            float sweepX = wp.widthPx() * 0.5f;     // the bit sweeps the part's whole width, once per pass
            float passes = Mth.clamp(Math.round(wp.depthPx() / PASS_STEP_PX), 3f, 12f);
            float tipY = baked.getBone("cutting_tool").map(CoreGeoBone::getPivotY).orElse(8f);
            float materialTopY = fixture.getPivotY() + wp.heightPx() * (1f - progress);
            float sweep = progress * passes;        // lengthwise passes run so far
            // the clamp runs the part past the bit and back, once per pass, while the
            // bit sweeps across the width: a serpentine cut that covers every layer
            float fixZ = feedZ * triangle(sweep);
            float baseX = sweepX * triangle(sweep * SWEEPS_PER_PASS);
            float baseY = Mth.clamp(materialTopY - tipY, 0f, BASE_Y_MAX_PX);
            float k = ease(now - machine.mechEaseStart);
            machine.mechFixZ = machine.mechFromFixZ + (fixZ - machine.mechFromFixZ) * k;
            machine.mechBaseX = machine.mechFromBaseX + (baseX - machine.mechFromBaseX) * k;
            machine.mechBaseY = machine.mechFromBaseY + (baseY - machine.mechFromBaseY) * k;
            return;
        }
        // no cut running: ease whatever the mechanism is holding back to rest
        if (machine.mechRunning) {
            machine.mechRunning = false;
            machine.mechEaseStart = now;
            snapshotMechanism(machine);
        }
        float k = 1f - ease(now - machine.mechEaseStart);
        machine.mechFixZ = machine.mechFromFixZ * k;
        machine.mechBaseX = machine.mechFromBaseX * k;
        machine.mechBaseY = machine.mechFromBaseY * k;
    }

    /** remember where the mechanism is, to ease out of it */
    private static void snapshotMechanism(WeaponModuleMachineBlockEntity machine) {
        machine.mechFromFixZ = machine.mechFixZ;
        machine.mechFromBaseX = machine.mechBaseX;
        machine.mechFromBaseY = machine.mechBaseY;
    }

    /** 0 -> 1 smoothstep over {@link #MECH_EASE_TICKS} ticks */
    private static float ease(double ticks) {
        float t = Mth.clamp((float) (ticks / MECH_EASE_TICKS), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    // --- drive shaft ---

    /**
     * The machine's drive shaft, drawn with Create's own half-shaft model — the
     * same one Create's machines use for a shaft leaving one face, cut down to
     * the machine's allowance ({@link MachineLayout#SHAFT_STUB_PX}). Two stubs:
     * the outward face of the extension cell (the machine's right end, see
     * {@link MachineLayout#shaftFace}) and the master's other end, which is the
     * same axle. Both turn with the machine's kinetic angle, so they line up
     * with a Create shaft attached next door, and they freeze when the network
     * stalls or is overstressed.
     *
     * <p>The two internal faces (master -> extension and back) are the relay
     * link, not a shaft: nothing is drawn there.
     */
    private static void drawDriveShaft(WeaponModuleMachineBlockEntity machine, BlockState state,
                                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        Direction out = MachineLayout.shaftFace(MachineLayout.facing(state));
        VertexConsumer vb = buffer.getBuffer(RenderType.solid());
        // right end: the extension cell, stub pointing out of the machine
        drawShaftStub(machine, state, poseStack, vb, packedLight, out, out);
        // left end: the master cell, stub pointing the other way
        drawShaftStub(machine, state, poseStack, vb, packedLight, null, out.getOpposite());
    }

    /**
     * One stub. {@code cellDir} is the cell to draw it in relative to the
     * master ({@code out} for the extension end), or null for the master's own
     * cell.
     *
     * <p>Create's half-shaft is 8 px long, reaching from the cell centre out to
     * the cell boundary; the machine only leaves a recess of about a pixel at
     * each end, so it is squashed along its own axis to
     * {@link MachineLayout#SHAFT_STUB_PX} about the model's outer cap plane
     * (model z = 16 px). That squash has to be part of the pose the model is
     * baked with: the buffer's own {@code translate}/{@code scale} run on the
     * baked vertices, which are already in block-local space, so they would
     * squash the block-local Z axis instead of the shaft — invisible at best,
     * and a thin blade pushed off the socket at worst.
     */
    private static void drawShaftStub(WeaponModuleMachineBlockEntity machine, BlockState state,
                                      PoseStack poseStack, VertexConsumer vb, int packedLight,
                                      @Nullable Direction cellDir, Direction face) {
        poseStack.pushPose();
        if (cellDir != null) {
            poseStack.translate(cellDir.getStepX(), 0.0, cellDir.getStepZ());
        }
        SuperByteBuffer shaft = STUB_BUFFERS.computeIfAbsent(face, f -> {
            PoseStack stub = CachedBuffers.rotateToFace(f).get();
            stub.translate(0f, 0f, 1f);
            stub.scale(1f, 1f, MachineLayout.SHAFT_STUB_PX / 8f);
            stub.translate(0f, 0f, -1f);
            return SuperBufferFactory.getInstance()
                    .createForBlock(AllPartialModels.SHAFT_HALF.get(), state, stub);
        });
        KineticBlockEntityRenderer.standardKineticRotationTransform(shaft, machine, packedLight)
                .renderInto(poseStack, vb);
        poseStack.popPose();
    }

    // --- workpiece ---

    /**
     * The workpiece in the clamp plus the block wrapping it. Both ride the clamp
     * bone (which has already been offset for this frame), so the part stays
     * clamped while the machine sways.
     *
     * <p>While the materials are being loaded the clamp holds only the raw
     * block, grown to {@code rawFill} of the part's height; the part itself
     * appears once the job starts ({@code showModule}) and the block is then
     * eaten away from the top down by {@code rawFill = 1 - progress}.
     *
     * <p>Note the mirror: model x is mirrored in render space, so a clamp moving
     * to model +x moves to pose x -1/16 px — the workpiece has to follow the
     * RENDERED clamp, hence {@code -fixtureX}.
     */
    private static void drawWorkpiece(Workpiece wp, float rawFill, boolean showModule, CoreGeoBone fixture,
                                      float fixtureX, float fixtureZ, PoseStack poseStack,
                                      MultiBufferSource buffer, float partialTick,
                                      int packedLight, int packedOverlay) {
        poseStack.pushPose();
        try {
            poseStack.translate(fixture.getPivotX() / 16f - fixtureX / 16f,
                    fixture.getPivotY() / 16f,
                    fixture.getPivotZ() / 16f + fixtureZ / 16f);
            if (showModule) {
                // the module itself: bottom centre on the clamp, scaled into the box
                poseStack.pushPose();
                try {
                    poseStack.scale(wp.scale(), wp.scale(), wp.scale());
                    poseStack.translate(-(wp.minX() + wp.maxX()) / 2f, -wp.minY(),
                            -(wp.minZ() + wp.maxZ()) / 2f);
                    // bottom-only parts are modelled rolled 180°, exactly like the
                    // standalone item flips them upright (see ModuleItemRenderer)
                    if (wp.flip()) poseStack.mulPose(Axis.ZP.rotationDegrees(180f));
                    ModuleDraw.draw(wp.stack(), poseStack, buffer, partialTick, packedLight, packedOverlay);
                } finally {
                    poseStack.popPose();
                }
            }
            // the raw material around it: a block of the module's colour and its
            // own sheet texture, growing with the loaded materials and shrinking
            // from the full part height to nothing as the job runs — drawn after
            // the part and opaque, so it hides everything it covers. It shrinks
            // from the top and the sheet stays where it is, so the material is
            // eaten away rather than squeezed (see drawBox), and it reaches zero
            // height — gone — with the last of the material.
            float fullHeight = (wp.heightPx() + WRAP_INFLATE_PX) / 16f;
            drawBox(poseStack, buffer,
                    (wp.widthPx() + 2f * WRAP_INFLATE_PX) / 32f,
                    (wp.depthPx() + 2f * WRAP_INFLATE_PX) / 32f,
                    fullHeight * rawFill, fullHeight,
                    wp.argb(), packedLight, packedOverlay);
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * The raw material block: the part's bounding box plus {@link #WRAP_INFLATE_PX}
     * on every visible side — the shell must never share a face with the part it
     * wraps — bottom centre at the pose origin, tinted with the module's colour
     * and cut out of {@link #WRAP_TEXTURE} (see {@link #tiledFace}).
     *
     * <p>{@code fullHeight} is the height the block had before machining started:
     * the block loses its top as it is cut down, and the sheet on its sides has
     * to stay where it is while that happens, so the sides are the only faces
     * that need to know it.
     */
    static void drawBox(PoseStack poseStack, MultiBufferSource buffer, float halfX, float halfZ,
                                float height, float fullHeight, int argb, int packedLight, int packedOverlay) {
        if (height <= 0f) return;
        VertexConsumer vc = buffer.getBuffer(WRAP_TYPE);
        Matrix4f mat = poseStack.last().pose();
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float x0 = -halfX, x1 = halfX, z0 = -halfZ, z1 = halfZ, y0 = 0f, y1 = height;
        // every face as seen from outside: o = its top left corner, du = its full
        // width (texture u, to the right), dv = its full height (texture v, down).
        // The top and bottom do not change with the height, so they are their own
        // full face; the four sides shrink and keep the sheet anchored.
        tiledFace(vc, mat, r, g, b, a, packedLight, packedOverlay, 0f, 1f, 0f,
                x0, y1, z0, x1 - x0, 0f, 0f, 0f, 0f, z1 - z0, z1 - z0);
        tiledFace(vc, mat, r, g, b, a, packedLight, packedOverlay, 0f, -1f, 0f,
                x1, y0, z0, x0 - x1, 0f, 0f, 0f, 0f, z1 - z0, z1 - z0);
        tiledFace(vc, mat, r, g, b, a, packedLight, packedOverlay, 0f, 0f, -1f,
                x1, y1, z0, x0 - x1, 0f, 0f, 0f, y0 - y1, 0f, fullHeight);
        tiledFace(vc, mat, r, g, b, a, packedLight, packedOverlay, 0f, 0f, 1f,
                x0, y1, z1, x1 - x0, 0f, 0f, 0f, y0 - y1, 0f, fullHeight);
        tiledFace(vc, mat, r, g, b, a, packedLight, packedOverlay, -1f, 0f, 0f,
                x0, y1, z0, 0f, 0f, z1 - z0, 0f, y0 - y1, 0f, fullHeight);
        tiledFace(vc, mat, r, g, b, a, packedLight, packedOverlay, 1f, 0f, 0f,
                x1, y1, z1, 0f, 0f, z0 - z1, 0f, y0 - y1, 0f, fullHeight);
    }

    /**
     * One face of the material block, cut out of the sheet's four corners: each
     * quarter of the face (in pixels) takes the corner patch of {@link
     * #WRAP_TEXTURE} that sits on the same corner, at one texel per pixel — so
     * the outermost texels of the material stay on the outermost edges of the
     * block however small the face is, exactly like Create's copycat shapes cut
     * a material down to a panel. A face larger than the sheet keeps its corners
     * at half a face each and stretches the patches rather than running off the
     * sheet.
     *
     * <p>{@code dv} is the part of the face that is still there, {@code fullV}
     * the face at full height. A block that is being machined away shrinks from
     * the top, and the sheet is not re-cut to the smaller face for that: its
     * bottom edge stays on the bottom edge of the block and the top of the
     * pattern goes with the material, quarter by quarter from the top down.
     */
    private static void tiledFace(VertexConsumer vc, Matrix4f mat, float r, float g, float b, float a,
                                  int packedLight, int packedOverlay, float nx, float ny, float nz,
                                  float ox, float oy, float oz,
                                  float dux, float duy, float duz, float dvx, float dvy, float dvz,
                                  float fullV) {
        float texels = wrapTexels();
        float uSize = Math.min(0.5f, length(dux, duy, duz) * 8f / texels);
        float vSize = Math.min(0.5f, fullV * 8f / texels);
        float left = fullV <= 0f ? 1f : Math.min(1f, length(dvx, dvy, dvz) / fullV);
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                // the bottom quarter stays whole until half the face is gone, the
                // top one only keeps what is left above that
                float part = j == 0 ? Math.max(0f, left - 0.5f) : Math.min(0.5f, left);
                if (part <= 0f) continue;
                float scale = 2f * part / left;
                float down = j == 0 ? 0f : 1f - part / left;
                float x = ox + dux * (i * 0.5f) + dvx * down;
                float y = oy + duy * (i * 0.5f) + dvy * down;
                float z = oz + duz * (i * 0.5f) + dvz * down;
                // the patch, cut off at its top with the face
                float v1 = j == 0 ? vSize : 1f;
                float v0 = v1 - 2f * part * vSize;
                quad(vc, mat, r, g, b, a, packedLight, packedOverlay, nx, ny, nz,
                        x, y, z, dux, duy, duz, dvx * scale, dvy * scale, dvz * scale,
                        i == 0 ? 0f : 1f - uSize, i == 0 ? uSize : 1f, v0, v1);
            }
        }
    }

    /** one quarter of a face: half of {@code du} by half of {@code dv}, from (x, y, z) */
    private static void quad(VertexConsumer vc, Matrix4f mat, float r, float g, float b, float a,
                             int packedLight, int packedOverlay, float nx, float ny, float nz,
                             float x, float y, float z,
                             float dux, float duy, float duz, float dvx, float dvy, float dvz,
                             float u0, float u1, float v0, float v1) {
        float ux = dux * 0.5f, uy = duy * 0.5f, uz = duz * 0.5f;
        float vx = dvx * 0.5f, vy = dvy * 0.5f, vz = dvz * 0.5f;
        vertex(vc, mat, r, g, b, a, packedLight, packedOverlay, nx, ny, nz, x, y, z, u0, v0);
        vertex(vc, mat, r, g, b, a, packedLight, packedOverlay, nx, ny, nz,
                x + vx, y + vy, z + vz, u0, v1);
        vertex(vc, mat, r, g, b, a, packedLight, packedOverlay, nx, ny, nz,
                x + ux + vx, y + uy + vy, z + uz + vz, u1, v1);
        vertex(vc, mat, r, g, b, a, packedLight, packedOverlay, nx, ny, nz,
                x + ux, y + uy, z + uz, u1, v0);
    }

    /** length of an axis-aligned face edge */
    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static void vertex(VertexConsumer vc, Matrix4f mat, float r, float g, float b, float a,
                               int packedLight, int packedOverlay, float nx, float ny, float nz,
                               float x, float y, float z, float u, float v) {
        vc.vertex(mat, x, y, z).color(r, g, b, a).uv(u, v)
                .overlayCoords(packedOverlay & 0xFFFF, (packedOverlay >>> 16) & 0xFFFF)
                .uv2(packedLight).normal(nx, ny, nz).endVertex();
    }

    // --- monitor ---

    /**
     * The monitor picture: the selected recipe's part drawn into an offscreen
     * texture as its own inventory icon ({@link MachineScreenTexture}) and shown
     * on the plate as a single quad, with the part's name drawn into the world
     * along the plate's bottom pixel. Both the picture and the quad are skipped
     * past {@link MachineScreenTexture#MAX_VISIBLE_BLOCKS} blocks — a screen
     * nobody can read costs nothing — and the picture is re-rendered only when
     * the part it would show has actually changed.
     */
    private static void drawScreen(WeaponModuleMachineBlockEntity machine, Workpiece wp,
                                   BakedGeoModel baked, PoseStack poseStack, MultiBufferSource buffer,
                                   float now) {
        CoreGeoBone screen = baked.getBone("loc_screen").orElse(null);
        if (screen == null || wp == null || !screenReadable(machine.getBlockPos())) return;
        MachineScreenTexture picture = SCREEN_PICTURES.computeIfAbsent(
                machine.getBlockPos(), pos -> new MachineScreenTexture());
        if (picture.stale(wp.stack())) {
            picture.render(wp);
        }
        try {
            // the model's own batch has to be out of the way first: the picture
            // sits in front of the plate, and must not depth-write over it either
            if (buffer instanceof MultiBufferSource.BufferSource source) source.endBatch();
            picture.draw(poseStack, screen);
            picture.drawName(poseStack, screen, wp.stack(), now);
        } catch (Throwable t) {
            // a failed picture must never take the whole block entity pass down
            if (!screenFailureLogged) {
                screenFailureLogged = true;
                LOGGER.warn("Machine tool monitor failed to draw at {}", machine.getBlockPos(), t);
            }
        }
    }

    /** is the machine close enough for its screen to be worth drawing? */
    private static boolean screenReadable(BlockPos pos) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float reach = MachineScreenTexture.MAX_VISIBLE_BLOCKS;
        return camera.distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
    }

    // --- workpiece metrics ---

    record Workpiece(ItemStack stack, boolean flip,
                             float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                             float scale, float widthPx, float heightPx, float depthPx, int argb) {
    }

    @Nullable
    private ResourceLocation metricsRecipe;
    @Nullable
    private Workpiece metrics;

    /**
     * Measure (and cache) the module's model: its bounding box in render space,
     * the uniform scale into the clamp box, and the colour of its texture. Keyed
     * by module id — the machine always builds the recipe's plain result stack,
     * so the id fully determines the artwork.
     */
    @Nullable
    private Workpiece workpieceMetrics(ItemStack stack) {
        ResourceLocation id = ModuleItem.getModuleId(stack);
        if (id == null) return null;
        if (id.equals(metricsRecipe)) return metrics;
        Workpiece measured = measure(stack);
        metricsRecipe = measured == null ? null : id;
        metrics = measured;
        return measured;
    }

    @Nullable
    private static Workpiece measure(ItemStack stack) {
        if (!(stack.getItem() instanceof ModuleItem moduleItem)) return null;
        ModuleGeoModel model = ModuleDraw.geoModel();
        model.setStack(stack);
        // no art assets: ModuleGeoModel falls back to the shared placeholder cube
        BakedGeoModel baked = model.getBakedModel(model.getModelResource(moduleItem));
        float[] bounds = ModelBounds.of(baked);
        if (bounds == null) return null;
        // bottom-only parts are modelled rolled 180° and drawn flipped upright
        // (ModuleItemRenderer): the clamp and the monitor must measure the pose
        // that is actually DRAWN, or the part leaves its box
        boolean flip = ModuleItemRenderer.isBottomOnly(stack);
        if (flip) bounds = ModuleDraw.flipZ(bounds);
        float widthPx = (bounds[3] - bounds[0]) * 16f;
        float heightPx = (bounds[4] - bounds[1]) * 16f;
        float depthPx = (bounds[5] - bounds[2]) * 16f;
        float scale = Math.min(1f, Math.min(
                WORKPIECE_MAX_Z_PX / Math.max(depthPx, 1.0e-4f),
                Math.min(WORKPIECE_MAX_Y_PX / Math.max(heightPx, 1.0e-4f),
                        WORKPIECE_MAX_X_PX / Math.max(widthPx, 1.0e-4f))));
        int argb = dominantColor(model.getTextureResource(moduleItem));
        return new Workpiece(stack.copy(), flip,
                bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5],
                scale, widthPx * scale, heightPx * scale, depthPx * scale, argb);
    }

    /**
     * The module texture's colour: its opaque pixels are clustered by hue — 24
     * bins, with the near-neutral ones (grey metal, black outlines) in a cluster
     * of their own — and the mean colour of the brighter half of the most
     * populated cluster is returned.
     *
     * <p>Clustering by hue keeps one whole shaded surface (a receiver's
     * grey-green body, highlights and shadows alike) in a single cluster, so the
     * pick follows the material the part is made of instead of whichever single
     * shade happens to cover the most pixels — a plain colour histogram hands
     * the part over to the black of its shadows and outlines. Averaging only the
     * brighter half then drops the shading: the mean of the whole cluster is a
     * lit surface and its shadow side averaged together, which reads a good deal
     * darker than the material does.
     *
     * <p>CHANNEL ORDER: {@link NativeImage} pixels are ABGR (red in the low
     * byte), the returned colour is ARGB like every other colour in this mod.
     * Missing texture / unreadable pixels fall back to neutral grey.
     */
    private static int dominantColor(ResourceLocation texture) {
        synchronized (COLOR_CACHE) {
            Integer cached = COLOR_CACHE.get(texture);
            if (cached != null) return cached;
        }
        int argb = 0xFF808080;
        try (NativeImage image = readTexture(texture)) {
            if (image != null) {
                int[] counts = new int[HUE_BINS + 1];
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int pixel = image.getPixelRGBA(x, y);
                        if (((pixel >>> 24) & 0xFF) < 128) continue;
                        counts[hueCluster(pixel)]++;
                    }
                }
                int best = 0;
                for (int i = 1; i < counts.length; i++) {
                    if (counts[i] > counts[best]) best = i;
                }
                if (counts[best] > 0) {
                    // how many of the cluster's pixels sit below each brightness
                    int[] byLuma = new int[256];
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            int pixel = image.getPixelRGBA(x, y);
                            if (((pixel >>> 24) & 0xFF) < 128 || hueCluster(pixel) != best) continue;
                            byLuma[luma(pixel)]++;
                        }
                    }
                    // the darker half goes; pixels of the boundary brightness are
                    // split, the first of them counted as darker (as they would
                    // come out of a stable sort by brightness)
                    int darker = counts[best] / 2;
                    int dropped = 0, boundary = 0;
                    while (boundary < byLuma.length - 1 && dropped + byLuma[boundary] <= darker) {
                        dropped += byLuma[boundary++];
                    }
                    int skip = darker - dropped;
                    long sumR = 0, sumG = 0, sumB = 0;
                    int taken = 0;
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            int pixel = image.getPixelRGBA(x, y);
                            if (((pixel >>> 24) & 0xFF) < 128 || hueCluster(pixel) != best) continue;
                            int brightness = luma(pixel);
                            if (brightness < boundary) continue;
                            if (brightness == boundary && skip > 0) {
                                skip--;
                                continue;
                            }
                            sumR += pixel & 0xFF;
                            sumG += (pixel >>> 8) & 0xFF;
                            sumB += (pixel >>> 16) & 0xFF;
                            taken++;
                        }
                    }
                    if (taken > 0) {
                        argb = 0xFF000000
                                | ((int) (sumR / taken) << 16)
                                | ((int) (sumG / taken) << 8)
                                | (int) (sumB / taken);
                    }
                }
            }
        } catch (Exception ignored) {
            // unreadable texture: keep the neutral grey
        }
        synchronized (COLOR_CACHE) {
            COLOR_CACHE.put(texture, argb);
        }
        return argb;
    }

    /** perceived brightness of an ABGR pixel, 0..255 */
    private static int luma(int pixel) {
        int r = pixel & 0xFF, g = (pixel >>> 8) & 0xFF, b = (pixel >>> 16) & 0xFF;
        return (int) (0.2126f * r + 0.7152f * g + 0.0722f * b);
    }

    /** hue clusters per full turn */
    private static final int HUE_BINS = 24;
    /** pixels below this saturation or brightness count as neutral, not as a hue */
    private static final float NEUTRAL_SATURATION = 0.12f, NEUTRAL_BRIGHTNESS = 0.12f;

    /** the hue cluster of an ABGR pixel: a hue bin, or the neutral cluster 0 */
    private static int hueCluster(int pixel) {
        int r = pixel & 0xFF, g = (pixel >>> 8) & 0xFF, b = (pixel >>> 16) & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int chroma = max - Math.min(r, Math.min(g, b));
        float saturation = max == 0 ? 0f : chroma / (float) max;
        if (saturation < NEUTRAL_SATURATION || max / 255f < NEUTRAL_BRIGHTNESS) return 0;
        float hue;
        if (max == r) {
            hue = ((g - b) / (float) chroma + 6f) % 6f;
        } else if (max == g) {
            hue = (b - r) / (float) chroma + 2f;
        } else {
            hue = (r - g) / (float) chroma + 4f;
        }
        return 1 + Math.min(HUE_BINS - 1, (int) (hue / 6f * HUE_BINS));
    }

    /**
     * {@link #WRAP_TEXTURE}'s shorter side in texels, read from the sheet itself
     * once so re-authoring it at another resolution keeps one texel per pixel of
     * the block. 16 until it can be read — the size the cutout fallback assumes.
     */
    private static float wrapTexels() {
        if (wrapTexels > 0f) return wrapTexels;
        float texels = 16f;
        try (NativeImage image = readTexture(WRAP_TEXTURE)) {
            if (image != null) {
                texels = Math.max(1f, Math.min(image.getWidth(), image.getHeight()));
            }
        } catch (Exception ignored) {
            // unreadable sheet: keep 16
        }
        wrapTexels = texels;
        return texels;
    }

    /** the texture file's pixels, or null when the resource is not there */
    @Nullable
    private static NativeImage readTexture(ResourceLocation texture) throws IOException {
        var resource = Minecraft.getInstance().getResourceManager().getResource(texture);
        return resource.isPresent() ? NativeImage.read(resource.get().open()) : null;
    }
}
