package dev.ignis.createpneumatictacticals.client;

import com.mojang.logging.LogUtils;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.block.MachineLayout;
import dev.ignis.createpneumatictacticals.block.ModBlocks;
import dev.ignis.createpneumatictacticals.block.WeaponModuleMachineBlock;
import dev.ignis.createpneumatictacticals.block.entity.WeaponModuleMachineBlockEntity;
import dev.ignis.createpneumatictacticals.recipe.ModRecipes;
import dev.ignis.createpneumatictacticals.recipe.ModuleCraftingRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;

/**
 * TEMPORARY verification harness (delete after use).
 *
 * <p>Run with {@code ./gradlew runClient -PcptHarness -PcptFacing=south} (the
 * properties are forwarded to the game JVM by the {@code client} run config;
 * {@code -Dcpt.harness} on the gradle command line does NOT reach it): joins the
 * world, checks both halves of all four facings (shape box + selection ray),
 * then places the machine tool, loads the shortest module recipe into it, starts
 * the job, and takes named screenshots into {@code run/screenshots} as the job
 * progresses — a close-up of the monitor and a three-quarter view of the whole
 * machine at each step — then quits the game. World changes run on the server
 * thread; only the screenshots run on the render thread.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, value = Dist.CLIENT)
public final class MachineScreenHarness {

    private static final boolean ENABLED = Boolean.getBoolean("cpt.harness");
    private static final Logger LOGGER = LogUtils.getLogger();
    /** shots for a forced facing: {@code -Dcpt.harness.facing=south}, default the player's */
    private static final Direction SHOT_FACING = facingOverride();

    private static Direction facingOverride() {
        String wanted = System.getProperty("cpt.harness.facing", "");
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d.getName().equalsIgnoreCase(wanted)) return d;
        }
        return null;
    }

    /** job progress at which to shoot */
    private static final float[] SHOT_AT = {0.03f, 0.2f, 0.4f, 0.6f, 0.8f, 0.95f};
    /** the biggest part available: small parts are only a few pixels on screen */
    private static final String WANT_PART = "mak_1_receiver";
    /** hard stop, so a stuck run cannot hang the gradle task */
    private static final int TICK_BUDGET = 2400;

    private static int stage;
    private static int ticks;
    private static int shotIndex;
    private static int wait;
    private static boolean grabNext;
    private static int postJobTicks = -1;
    /** consecutive-frame shots: the leak this checks for is a per-frame state, not a per-tick one */
    private static int staticShots;
    private static String staticTag = "";
    /** pre-job phase: recipe chosen, materials arriving one by one, door still open */
    private static boolean loading;
    private static int loadTicks;
    private static volatile boolean setupDone;
    private static volatile BlockPos master;
    /** the machine this harness placed, removed again when the run ends */
    private static BlockPos placedAt;
    private static volatile Direction facing = Direction.NORTH;

    private MachineScreenHarness() {
    }

    /**
     * Consecutive-frame shots of one still scene. A picture pass that leaks
     * render state shows up as the whole scene changing from one frame to the
     * next (the world is redrawn 3 times per tick, the picture once), so these
     * are taken per render frame rather than per tick.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || staticShots <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        String name = String.format("cpt-harness-static-%s-%02d.png", staticTag, staticShots);
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> { });
        LOGGER.info("[harness] {} frame grab", name);
        staticShots--;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        ticks++;
        if (ticks > TICK_BUDGET) {
            LOGGER.warn("[harness] budget exhausted at stage {}, quitting", stage);
            mc.stop();
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (stage == 0) {
            if (setupDone) {
                stage = 1;
                return;
            }
            if (ticks < 40 || ticks % 20 != 0) return;
            server.execute(() -> setUp(mc));
            return;
        }
        BlockPos pos = master;
        if (pos == null) return;
        if (!(mc.level.getBlockEntity(pos) instanceof WeaponModuleMachineBlockEntity be)) return;

        if (loading) {
            loadingTick(mc, be);
            return;
        }

        // shots are driven by the observed job progress, so any recipe works
        float progress = be.craftProgress(0f);
        if (shotIndex < SHOT_AT.length) {
            if (!grabNext) {
                if (be.isCrafting() && progress >= SHOT_AT[shotIndex]) {
                    server.execute(() -> aim(mc, shotIndex % 3));
                    grabNext = true;
                    wait = 3;
                }
            } else if (wait-- <= 0) {
                grab(mc, be, shotIndex % 3);
                shotIndex++;
                grabNext = false;
            }
            return;
        }
        if (!be.isCrafting() && be.hasOutput()) {
            if (postJobTicks < 0) {
                server.execute(() -> aim(mc, 2));        // wide: the finished part in the clamp
                postJobTicks = 0;
            } else if (postJobTicks == 3) {
                grab(mc, be, 2);
            } else if (postJobTicks == 8) {
                server.execute(() -> aim(mc, 1));        // bay: the mechanism settling
            } else if (postJobTicks == 11) {
                grab(mc, be, 1);
            } else if (postJobTicks == 20) {
                server.execute(MachineScreenHarness::cleanUp);
            } else if (postJobTicks > 24) {
                LOGGER.info("[harness] done, quitting");
                mc.stop();
            }
            postJobTicks++;
        }
    }

    /**
     * Pre-job shots: the recipe is chosen and the materials are still going in,
     * so the monitor must already show the part while the clamp holds only the
     * raw block, grown by the items loaded so far. The job starts when the door
     * shuts, so this runs before the progress-driven shots.
     */
    private static void loadingTick(Minecraft mc, WeaponModuleMachineBlockEntity be) {
        MinecraftServer server = mc.getSingleplayerServer();
        switch (++loadTicks) {
            case 14 -> server.execute(() -> aim(mc, 2));
            case 17 -> grabNamed(mc, be, "load-01-one-item-wide");
            case 20 -> server.execute(() -> aim(mc, 0));
            case 23 -> grabNamed(mc, be, "load-02-one-item-monitor");
            // the monitor is part of the world: a block between camera and plate
            // must hide the picture like it hides the machine
            case 25 -> server.execute(MachineScreenHarness::raiseWall);
            case 27 -> server.execute(() -> aim(mc, 0));
            case 29 -> grabNamed(mc, be, "load-02b-monitor-behind-wall");
            case 31 -> server.execute(MachineScreenHarness::dropWall);
            case 33 -> server.execute(() -> fillRest(mc));
            case 37 -> server.execute(() -> aim(mc, 2));
            // still scene, door open, nothing crafting: consecutive frames for the leak check
            case 38 -> startStatic("load");
            case 40 -> grabNamed(mc, be, "load-03-all-items-wide");
            case 41 -> server.execute(() -> aim(mc, 3));
            case 43 -> grabNamed(mc, be, "load-04-clamp");
            case 44 -> server.execute(() -> closeDoor(mc));
            default -> {
                if (loadTicks > 47) loading = false;
            }
        }
    }

    /** put a solid block between the camera and the monitor plate (server thread) */
    private static void raiseWall() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        BlockPos pos = master;
        if (server == null || pos == null) return;
        ServerLevel level = server.overworld();
        BlockPos wall = pos.relative(facing);
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        LOGGER.info("[harness] wall placed at {} in front of the monitor", wall);
    }

    /** take that block out again (server thread) */
    private static void dropWall() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        BlockPos pos = master;
        if (server == null || pos == null) return;
        server.overworld().setBlockAndUpdate(pos.relative(facing), Blocks.AIR.defaultBlockState());
    }

    // --- server thread ---

    /** load every remaining ingredient into its station (server thread) */
    private static void fillRest(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        BlockPos pos = master;
        if (server == null || pos == null) return;
        if (!(server.overworld().getBlockEntity(pos) instanceof WeaponModuleMachineBlockEntity be)) return;
        ModuleCraftingRecipe recipe = be.currentRecipe();
        if (recipe == null) return;
        for (int i = 0; i < recipe.getIngredientList().size(); i++) {
            if (be.getItem(i).isEmpty()) {
                be.setItem(i, recipe.getIngredientList().get(i).getItems()[0].copyWithCount(1));
            }
        }
        LOGGER.info("[harness] loaded all materials, complete={} fraction={}",
                be.materialsComplete(), String.format("%.2f", be.materialsFraction()));
    }

    /** shut the door: with the materials in, this starts the job (server thread) */
    private static void closeDoor(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        BlockPos pos = master;
        if (server == null || pos == null) return;
        ServerLevel level = server.overworld();
        if (!(level.getBlockEntity(pos) instanceof WeaponModuleMachineBlockEntity be)) return;
        level.setBlockAndUpdate(pos,
                level.getBlockState(pos).setValue(WeaponModuleMachineBlock.OPEN, false));
        be.onDoorClosed();
        LOGGER.info("[harness] door shut: crafting={}", be.isCrafting());
    }

    /** arm the consecutive-frame grabber for the next frames (client tick) */
    private static void startStatic(String tag) {
        staticTag = tag;
        staticShots = 8;
        LOGGER.info("[harness] consecutive-frame shots armed: {}", tag);
    }

    private static void grabNamed(Minecraft mc, WeaponModuleMachineBlockEntity be, String tag) {
        String name = "cpt-harness-" + tag + ".png";
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> { });
        LOGGER.info("[harness] {} tick {} crafting={} recipe={} rawFill={}", name, ticks,
                be.isCrafting(), be.getRecipeId(), String.format("%.2f", be.materialsFraction()));
    }

    private static void setUp(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        ServerLevel level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (player == null) return;
        player.setNoGravity(true);
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        // a fresh machine in the open air above the surface: nothing to destroy,
        // daylight on the whole thing, and whatever is already in the world is
        // left alone (removed again once the shots are taken)
        Direction face = SHOT_FACING != null ? SHOT_FACING : player.getDirection().getOpposite();
        BlockPos column = player.blockPosition().relative(player.getDirection(), 6);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                column.getX(), column.getZ()) + 2;
        BlockPos m = new BlockPos(column.getX(), y, column.getZ());
        BlockPos ext = MachineLayout.slavePos(m, face);
        for (BlockPos cell : new BlockPos[]{m, ext, m.above(), ext.above()}) {
            if (!level.getBlockState(cell).isAir()) level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        }
        checkFacings(level, m, player);
        level.setBlock(m, ModBlocks.WEAPON_MODULE_MACHINE_TOOL.get().defaultBlockState()
                .setValue(WeaponModuleMachineBlock.FACING, face)
                .setValue(WeaponModuleMachineBlock.OPEN, false), 3);
        level.setBlock(ext, ModBlocks.WEAPON_MODULE_MACHINE_TOOL_EXTENSION.get().defaultBlockState()
                .setValue(WeaponModuleMachineBlock.FACING, face), 3);
        WeaponModuleMachineBlockEntity machine = level.getBlockEntity(m) instanceof WeaponModuleMachineBlockEntity be
                ? be : null;
        LOGGER.info("[harness] placed machine at {} facing {} -> {}", m, face, machine);
        if (machine == null) return;
        placedAt = m;
        master = machine.getBlockPos();
        facing = MachineLayout.facing(machine.getBlockState());

        if (machine.hasOutput() && !machine.isCrafting()) {
            ItemStack out = machine.takeOutput();   // hand the finished part back, do not destroy it
            player.getInventory().add(out);
            LOGGER.info("[harness] took finished {} back into the inventory", out);
        }
        if (!machine.isCrafting()) {
            List<ModuleCraftingRecipe> recipes = level.getRecipeManager()
                    .getAllRecipesFor(ModRecipes.MODULE_CRAFTING.get());
            ModuleCraftingRecipe recipe = recipes.stream()
                    .filter(r -> r.getModuleId().getPath().contains(WANT_PART))
                    .min(Comparator.comparingInt(r -> r.getIngredientList().size()))
                    .orElseGet(() -> recipes.stream()
                            .min(Comparator.comparingInt(r -> r.getIngredientList().size())).orElse(null));
            if (recipe == null) {
                LOGGER.error("[harness] no module recipes");
                return;
            }
            machine.setRecipe(recipe);
            // first ingredient only: the rest arrives during the loading phase,
            // so the shots show the raw block growing item by item with the door
            // still open (nothing starts until the door shuts)
            machine.setItem(0, recipe.getIngredientList().get(0).getItems()[0].copyWithCount(1));
            level.setBlockAndUpdate(m, level.getBlockState(m).setValue(WeaponModuleMachineBlock.OPEN, true));
            loading = true;
            LOGGER.info("[harness] job {} ({} ingredients) at {} facing {} — loading 1 item, door open",
                    recipe.getId(), recipe.getIngredientList().size(), master, facing);
        } else {
            LOGGER.info("[harness] machine at {} already running: progress {}", master,
                    machine.craftProgress(0f));
        }
        setupDone = true;
    }

    /**
     * Four-facing self-check, run before the job: for every horizontal facing both
     * halves must frame the machine with one and the same world box, and a ray
     * aimed at either half must select one of the machine's own two cells —
     * never a neighbouring cell of empty air, and never nothing at all.
     */
    private static void checkFacings(ServerLevel level, BlockPos base, ServerPlayer player) {
        boolean all = true;
        for (Direction face : Direction.Plane.HORIZONTAL) {
            BlockPos m = base.above(8);
            BlockPos ext = MachineLayout.slavePos(m, face);
            for (BlockPos cell : new BlockPos[]{m, ext}) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            }
            level.setBlock(m, ModBlocks.WEAPON_MODULE_MACHINE_TOOL.get().defaultBlockState()
                    .setValue(WeaponModuleMachineBlock.FACING, face)
                    .setValue(WeaponModuleMachineBlock.OPEN, false), 3);
            level.setBlock(ext, ModBlocks.WEAPON_MODULE_MACHINE_TOOL_EXTENSION.get().defaultBlockState()
                    .setValue(WeaponModuleMachineBlock.FACING, face), 3);
            // the box the outline should draw: both cells along the machine's own
            // axis, one cell across it, and the machine's height
            AABB want = new AABB(Math.min(m.getX(), ext.getX()), m.getY(), Math.min(m.getZ(), ext.getZ()),
                    Math.max(m.getX(), ext.getX()) + 1, m.getY() + MachineLayout.MODEL_HEIGHT,
                    Math.max(m.getZ(), ext.getZ()) + 1);

            for (BlockPos cell : new BlockPos[]{m, ext}) {
                boolean master = cell.equals(m);
                BlockState state = level.getBlockState(cell);
                AABB got = state.getShape(level, cell).bounds().move(cell.getX(), cell.getY(), cell.getZ());
                boolean shapeOk = same(got, want);
                // eye one cell in front of the cell, ray straight at its centre
                Vec3 eye = new Vec3(cell.getX() + 0.5 - face.getStepX(), cell.getY() + 0.5,
                        cell.getZ() + 0.5 - face.getStepZ());
                Vec3 to = eye.add(face.getStepX() * 2.0, 0.0, face.getStepZ() * 2.0);
                BlockHitResult hit = level.clip(new ClipContext(eye, to,
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                boolean rayOk = hit.getType() == HitResult.Type.BLOCK
                        && (hit.getBlockPos().equals(m) || hit.getBlockPos().equals(ext));
                all &= shapeOk && rayOk;
                LOGGER.info("[harness] facing {} {} shape={} [{}..{}] hit={} at {} {}",
                        face, master ? "master" : "ext", shapeOk ? "OK" : "WRONG",
                        String.format("%.2f,%.2f,%.2f", got.minX, got.minY, got.minZ),
                        String.format("%.2f,%.2f,%.2f", got.maxX, got.maxY, got.maxZ),
                        hit.getType(), hit.getBlockPos(), rayOk ? "OK" : "WRONG");
            }
            for (BlockPos cell : new BlockPos[]{m, ext}) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        LOGGER.info("[harness] facing self-check {}", all ? "PASSED" : "FAILED");
    }

    /** tolerance compare: shapes built from float boxes will not match bit for bit */
    private static boolean same(AABB a, AABB b) {
        return Math.abs(a.minX - b.minX) < 1e-6 && Math.abs(a.minY - b.minY) < 1e-6
                && Math.abs(a.minZ - b.minZ) < 1e-6 && Math.abs(a.maxX - b.maxX) < 1e-6
                && Math.abs(a.maxY - b.maxY) < 1e-6 && Math.abs(a.maxZ - b.maxZ) < 1e-6;
    }

    /** take the harness's own machine out of the world again (server thread) */
    private static void cleanUp() {
        BlockPos pos = placedAt;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (pos == null || server == null) return;
        ServerLevel level = server.overworld();
        for (BlockPos cell : new BlockPos[]{pos, MachineLayout.slavePos(pos, facing), pos.above(),
                MachineLayout.slavePos(pos, facing).above()}) {
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        }
        LOGGER.info("[harness] removed the harness machine at {}", pos);
    }

    /** camera: close-up on the monitor plate, or a three-quarter view of the machine (server thread) */
    private static void aim(Minecraft mc, int kind) {
        ServerPlayer player = mc.getSingleplayerServer().getPlayerList().getPlayers().stream().findFirst().orElse(null);
        BlockPos pos = master;
        if (player == null || pos == null) return;
        Direction ccw = facing.getCounterClockWise();
        Vec3 plate = new Vec3(
                pos.getX() + 0.5 + facing.getStepX() * 0.49 + ccw.getStepX() * 1.5 / 16.0,
                pos.getY() + 8.25 / 16.0,
                pos.getZ() + 0.5 + facing.getStepZ() * 0.49 + ccw.getStepZ() * 1.5 / 16.0);
        Vec3 eye;
        Vec3 target;
        if (kind == 0) {
            eye = plate.add(facing.getStepX() * 1.1, 0.02, facing.getStepZ() * 1.1);
            target = plate;
        } else if (kind == 1) {
            // into the machining bay of the extension cell
            BlockPos slave = MachineLayout.slavePos(pos, facing);
            target = new Vec3(slave.getX() + 0.5, pos.getY() + 0.55, slave.getZ() + 0.5);
            eye = target.add(facing.getStepX() * 0.9, 0.06, facing.getStepZ() * 0.9);
        } else if (kind == 3) {
            // the clamp itself: the fixture sits just past the master cell's
            // centre on the machine's own axis, its top 6.4 px up the model
            Vec3 clamp = new Vec3(pos.getX() + 0.5 + facing.getStepX() * 0.53,
                    pos.getY() + 6.4 / 16.0,
                    pos.getZ() + 0.5 + facing.getStepZ() * 0.53);
            target = clamp.add(0.0, 0.16, 0.0);
            eye = target.add(facing.getStepX() * 0.95, 0.30, facing.getStepZ() * 0.95);
        } else {
            eye = plate.add(facing.getStepX() * 2.6 - ccw.getStepX() * 1.7, 0.9,
                    facing.getStepZ() * 2.6 - ccw.getStepZ() * 1.7);
            target = new Vec3(pos.getX() + 0.5 + ccw.getStepX() * 0.5, pos.getY() + 0.55,
                    pos.getZ() + 0.5 + ccw.getStepZ() * 0.5);
        }
        Vec3 d = target.subtract(eye).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.asin(d.y));
        player.connection.teleport(eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
        player.setDeltaMovement(Vec3.ZERO);
    }

    // --- render thread ---

    private static void grab(Minecraft mc, WeaponModuleMachineBlockEntity be, int kind) {
        String[] kinds = {"monitor", "bay", "wide"};
        String name = String.format("cpt-harness-%02d-%s.png", shotIndex, kinds[kind]);
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> { });
        LOGGER.info("[harness] {} tick {} crafting={} progress={} feedZ={} baseX={} baseY={} output={}",
                name, ticks, be.isCrafting(), String.format("%.3f", be.craftProgress(0f)),
                String.format("%.2f", be.mechFixZ),
                String.format("%.2f", be.mechBaseX), String.format("%.2f", be.mechBaseY), be.hasOutput());
    }
}
