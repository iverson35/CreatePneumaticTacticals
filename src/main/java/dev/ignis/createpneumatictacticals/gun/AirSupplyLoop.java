package dev.ignis.createpneumatictacticals.gun;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.item.GunItem;
import dev.ignis.createpneumatictacticals.item.ModItems;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import dev.ignis.createpneumatictacticals.module.SupplyType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime air-supply loop, driven by Create encased-fan air currents:
 *  - internal_tank guns re-pressurize while the holder stands in a fan flow;
 *  - air vials (held by a player, or lying in the flow) pressurize into
 *    pressurized air vials after staying inside the flow for N ticks.
 *
 * Flow detection uses Create's public {@link AirCurrent} API (bounds + max
 * distance) against {@link IAirCurrentSource#getAirCurrent()} — no mixin.
 */
@Mod.EventBusSubscriber(modid = CreatePneumaticTacticals.MODID)
public final class AirSupplyLoop {

    /** Ticks an item must stay inside the flow before pressurizing. */
    private static final int PRESSURIZE_TICKS = 150;
    /** Air restored to an internal-tank gun per tick inside the flow. */
    private static final int AIR_RECHARGE_PER_TICK = 5;
    /** Max blocks to probe along each axis when locating a feeding fan. */
    private static final int FAN_PROBE_RANGE = 21;

    private static final String KEY_PRESSURIZE_PROGRESS = "cpt_pressurize_progress";

    private AirSupplyLoop() {}

    /**
     * True when the position sits inside at least one encased fan's air current
     * whose segment at this position carries plain air (no blasting/splashing/
     * haunting/smoking catalyst — pressurizing only works with clean air).
     * Uses Create's public AirCurrent bounds + getTypeAt API; no mixin.
     */
    public static boolean inPlainAirFlow(Level level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (IAirCurrentSource fan : findFansAround(level, pos)) {
            AirCurrent airCurrent = fan.getAirCurrent();
            if (airCurrent == null || airCurrent.maxDistance <= 0) continue;
            if (!airCurrent.bounds.inflate(0.25f).contains(center)) continue;
            float distance = alignedDistance(fan, pos);
            if (airCurrent.getTypeAt(distance) == null) return true;
        }
        return false;
    }

    /** Axis-aligned distance along the fan's flow direction (mirrors AirCurrent usage). */
    private static float alignedDistance(IAirCurrentSource fan, BlockPos pos) {
        Vec3 rel = Vec3.atCenterOf(pos).subtract(Vec3.atCenterOf(fan.getAirCurrentPos()));
        Direction dir = fan.getAirFlowDirection();
        if (dir == null) return Float.MAX_VALUE;
        Vec3 axis = new Vec3(dir.getStepX(), dir.getStepY(), dir.getStepZ());
        return (float) Math.abs(rel.dot(axis));
    }

    /**
     * Finds encased fans that could feed a flow through {@code pos}: probes up
     * to {@link #FAN_PROBE_RANGE} blocks along each of the 6 axis directions
     * (fan range is config-capped at 20) for an IAirCurrentSource.
     */
    private static List<IAirCurrentSource> findFansAround(Level level, BlockPos pos) {
        List<IAirCurrentSource> out = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            BlockPos cursor = pos;
            for (int i = 1; i <= FAN_PROBE_RANGE; i++) {
                cursor = cursor.relative(dir);
                if (!level.isLoaded(cursor)) break;
                BlockEntity be = level.getBlockEntity(cursor);
                if (be instanceof IAirCurrentSource fan) {
                    out.add(fan);
                    break; // first BE along this ray (flow is blocked behind it anyway)
                }
            }
        }
        return out;
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) return;
        Player player = event.player;
        Level level = player.level();
        boolean inFlow = inPlainAirFlow(level, player.blockPosition());

        ItemStack held = player.getMainHandItem();

        // --- internal_tank: recharge main-hand gun in a fan flow ---
        if (held.getItem() instanceof GunItem) {
            rechargeGun(held, inFlow);
        }

        // --- held air vial pressurizes in a fan flow ---
        if (held.is(ModItems.AIR_VIAL.get()) && inFlow && tickPressurize(held, 1)) {
            held.shrink(1);
            ItemStack pressurized = new ItemStack(ModItems.PRESSURIZED_AIR_VIAL.get(), 1);
            if (!player.getInventory().add(pressurized)) {
                player.drop(pressurized, false);
            }
        }

        // --- ground air vials in the same flow pressurize too ---
        if (inFlow) {
            pressurizeNearbyItemEntities(level, player.blockPosition());
        }
    }

    private static void rechargeGun(ItemStack gun, boolean inFlow) {
        ModuleDefinition supply = GunNbt.readModules(gun).get(ModuleType.SUPPLY);
        if (supply == null || supply.supplyType != SupplyType.INTERNAL_TANK) return;
        if (!inFlow) return;
        int full = supply.airCapacity > 0 ? supply.airCapacity : gun.getMaxDamage();
        int air = gun.getDamageValue();
        if (air >= full) return;
        gun.setDamageValue(Math.min(full, air + AIR_RECHARGE_PER_TICK));
    }

    /** Advances pressurization progress on ground air vials inside the flow. */
    private static void pressurizeNearbyItemEntities(Level level, BlockPos pos) {
        AABB flowArea = new AABB(pos).inflate(1.5);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, flowArea);
        for (ItemEntity item : items) {
            ItemStack stack = item.getItem();
            if (!stack.is(ModItems.AIR_VIAL.get())) continue;
            if (!inPlainAirFlow(level, item.blockPosition())) continue;
            if (tickPressurize(stack, 1)) {
                item.setItem(new ItemStack(ModItems.PRESSURIZED_AIR_VIAL.get(), stack.getCount()));
            }
        }
    }

    /**
     * Shared progress tick for stacks exposed to fan flow (held by a player or
     * lying in the flow). Returns true when conversion should fire this tick.
     */
    public static boolean tickPressurize(ItemStack stack, int ticks) {
        // ItemEntity stacks persist this tag; conversion resets it in tickPressurize callers.
        CompoundTag tag = stack.getOrCreateTag();
        int progress = tag.getInt(KEY_PRESSURIZE_PROGRESS) + ticks;
        if (progress < PRESSURIZE_TICKS) {
            tag.putInt(KEY_PRESSURIZE_PROGRESS, progress);
            return false;
        }
        tag.remove(KEY_PRESSURIZE_PROGRESS);
        return true;
    }
}