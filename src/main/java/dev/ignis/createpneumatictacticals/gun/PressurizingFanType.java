package dev.ignis.createpneumatictacticals.gun;

import com.simibubi.create.AllTags.AllBlockTags;
import com.simibubi.create.AllTags.AllFluidTags;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.item.ModItems;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import dev.ignis.createpneumatictacticals.module.SupplyType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Create fan-processing hook: items sitting in a plain-air encased-fan flow —
 * <b>depots and belts</b> (the blocks Create exposes a
 * {@link TransportedItemStackHandlerBehaviour} on; chutes probe the belt below
 * instead) — pressurize exactly like Create's own smoking/washing/blasting
 * does, i.e. through the fan pipeline. The server's
 * {@code kinetics.fanProcessingTime} (150 ticks by default) is the exposure
 * time before the transformation lands:
 *
 * <ul>
 *   <li>air vial -&gt; pressurized air vial (count preserved)</li>
 *   <li>internal-tank gun -&gt; full tank</li>
 * </ul>
 *
 * <p>Deliberately NOT a general "plain air" type: it only claims blocks that
 * hold a {@link TransportedItemStackHandlerBehaviour}, because that lookup in
 * {@code AirCurrent.findAffectedHandlers} is the one place Create resolves a
 * type per block without the sticky-segment rule. Claiming every plain-air
 * flow block outright would flip plain-air fans' segment type from null to
 * this type along their whole length, which also swaps the airflow particle
 * look ({@code AirFlowParticle} picks a different sprite set for any non-null
 * type) — the claim is therefore limited to the depot / belt blocks
 * themselves (and the stretch behind them, which {@link #morphAirFlow} keeps
 * visually neutral). Loose dropped items stay on {@link AirSupplyLoop}'s own
 * scan.
 *
 * <p>Catalyzed flows stay untouched: the type is invalid wherever the flow
 * carries a catalyst (blasting / smoking / splashing / haunting), so washing
 * setups keep washing.
 *
 * <p>Registered through {@link CreateRegistries#FAN_PROCESSING_TYPE}, whose
 * freeze callback sorts Create's lookup table — the RegisterEvent runs before
 * that freeze.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PressurizingFanType implements FanProcessingType {

    public static final ResourceLocation ID =
            new ResourceLocation(CreatePneumaticTacticals.MODID, "pressurizing");

    private static final PressurizingFanType INSTANCE = new PressurizingFanType();

    /** Under every Create catalyst (blasting 100 … splashing 400). */
    private static final int PRIORITY = 50;

    private PressurizingFanType() {}

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        event.register(CreateRegistries.FAN_PROCESSING_TYPE, ID, () -> INSTANCE);
    }

    @Override
    public boolean isValidAt(Level level, BlockPos pos) {
        // depot / belt blocks only — see the class javadoc
        if (BlockEntityBehaviour.get(level, pos, TransportedItemStackHandlerBehaviour.TYPE) == null) return false;
        return plainAirFlowAt(level, pos);
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public boolean canProcess(ItemStack stack, Level level) {
        if (stack.is(ModItems.AIR_VIAL.get())) return true;
        return isDrainedTankGun(stack);
    }

    @Override
    public @Nullable List<ItemStack> process(ItemStack stack, Level level) {
        if (stack.is(ModItems.AIR_VIAL.get())) {
            return List.of(new ItemStack(ModItems.PRESSURIZED_AIR_VIAL.get(), stack.getCount()));
        }
        if (isDrainedTankGun(stack)) {
            ItemStack full = stack.copy();
            AirTank.refill(full);
            return List.of(full);
        }
        return null;
    }

    /** No extra particles: this type covers every depot/belt item in plain air. */
    @Override
    public void spawnProcessingParticles(Level level, Vec3 pos) {}

    /**
     * Keeps the plain-air look. A flow block holding a depot or belt makes the
     * flow carry this type from there on, and Create colors such flows through
     * this hook instead of its plain defaults.
     */
    @Override
    public void morphAirFlow(AirFlowParticleAccess particleAccess, RandomSource random) {
        particleAccess.setColor(PLAIN_FLOW_COLOR);
        particleAccess.setAlpha(PLAIN_FLOW_ALPHA);
    }

    /** Players are handled by {@link AirSupplyLoop}; other entities stay untouched. */
    @Override
    public void affectEntity(Entity entity, Level level) {}

    /** AirFlowParticle's plain-air color/alpha, reused to stay visually neutral. */
    private static final int PLAIN_FLOW_COLOR = 15658734; // 0xEEEEEE
    private static final float PLAIN_FLOW_ALPHA = 0.25f;

    /**
     * True for a flow that counts as plain air: an absent type (Create 6
     * reports null for plain segments) or this type, which only marks the
     * stretch at/after a depot or belt and changes nothing about the air.
     */
    public static boolean isPlainAir(@Nullable FanProcessingType type) {
        return type == null || type == INSTANCE;
    }

    private static boolean isDrainedTankGun(ItemStack stack) {
        if (!(stack.getItem() instanceof GunItem)) return false;
        ModuleDefinition supply = GunNbt.readModules(stack).get(ModuleType.SUPPLY);
        return supply != null && supply.supplyType == SupplyType.INTERNAL_TANK
                && AirTank.stored(stack) < AirTank.capacity(stack);
    }

    /**
     * True when the flow covering {@code pos} carries plain air. Mirrors
     * {@code AirCurrent}'s sticky segment rule: a pushing flow owns {@code pos}
     * unless the stretch between the fan and {@code pos} is catalyzed, a
     * pulling flow unless the stretch beyond {@code pos} is.
     */
    private static boolean plainAirFlowAt(Level level, BlockPos pos) {
        if (isCatalystAt(level, pos)) return false;
        for (IAirCurrentSource fan : AirSupplyLoop.findFansAround(level, pos)) {
            Direction flow = fan.getAirflowOriginSide();
            Direction travel = fan.getAirFlowDirection();
            if (flow == null || travel == null) continue;
            BlockPos fanPos = fan.getAirCurrentPos();
            BlockPos delta = pos.subtract(fanPos);
            int along = delta.getX() * flow.getStepX() + delta.getY() * flow.getStepY()
                    + delta.getZ() * flow.getStepZ();
            if (along <= 0) continue;
            if (delta.getX() != flow.getStepX() * along || delta.getY() != flow.getStepY() * along
                    || delta.getZ() != flow.getStepZ() * along) {
                continue; // not on the flow's axis (nozzle redirect or diagonal)
            }
            if (travel == flow) {
                // pushing: everything between the fan and pos must be plain
                for (int i = 1; i < along; i++) {
                    if (isCatalystAt(level, fanPos.relative(flow, i))) return false;
                }
            } else {
                // pulling: a catalyst farther out still owns pos's segment
                int limit = (int) Math.ceil(fan.getMaxDistance());
                for (int i = along + 1; i <= limit; i++) {
                    if (isCatalystAt(level, fanPos.relative(flow, i))) return false;
                }
            }
            return true;
        }
        return false;
    }

    /** True where the block or fluid is a catalyst for any Create fan process. */
    private static boolean isCatalystAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (AllBlockTags.FAN_PROCESSING_CATALYSTS_BLASTING.matches(state)
                || AllBlockTags.FAN_PROCESSING_CATALYSTS_HAUNTING.matches(state)
                || AllBlockTags.FAN_PROCESSING_CATALYSTS_SMOKING.matches(state)
                || AllBlockTags.FAN_PROCESSING_CATALYSTS_SPLASHING.matches(state)) {
            return true;
        }
        FluidState fluid = level.getFluidState(pos);
        return AllFluidTags.FAN_PROCESSING_CATALYSTS_BLASTING.matches(fluid)
                || AllFluidTags.FAN_PROCESSING_CATALYSTS_HAUNTING.matches(fluid)
                || AllFluidTags.FAN_PROCESSING_CATALYSTS_SMOKING.matches(fluid)
                || AllFluidTags.FAN_PROCESSING_CATALYSTS_SPLASHING.matches(fluid);
    }
}
