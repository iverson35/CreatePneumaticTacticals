package dev.ignis.createpneumatictacticals.item;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import dev.ignis.createpneumatictacticals.ammo.AmmoExtension;
import dev.ignis.createpneumatictacticals.gun.GunNbt;
import dev.ignis.createpneumatictacticals.module.ModuleDefinition;
import dev.ignis.createpneumatictacticals.module.ModuleManager;
import dev.ignis.createpneumatictacticals.module.ModuleType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two creative tabs, split by origin:
 * <ol>
 *   <li>{@code gear} — the mod's own items: pods, air vials, the ammo box
 *       and both workbenches. Icon: the gun workbench block.</li>
 *   <li>{@code gunpacks} — gunpack content: every module definition plus a
 *       complete sample gun per receiver. Icon: the module list cycling one
 *       per second (the icon supplier runs on every tab-strip redraw).</li>
 * </ol>
 * Content is built lazily on tab display, after gunpacks are loaded —
 * module definitions don't exist at static-init time.
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreatePneumaticTacticals.MODID);

    /** registry id of the gear tab: gunpacks pins itself right after it */
    public static final net.minecraft.resources.ResourceLocation GEAR_ID =
            new net.minecraft.resources.ResourceLocation(CreatePneumaticTacticals.MODID, "gear");

    public static final RegistryObject<CreativeModeTab> GEAR = TABS.register("gear",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + CreatePneumaticTacticals.MODID + ".gear"))
                    .icon(() -> new ItemStack(
                            dev.ignis.createpneumatictacticals.block.ModBlocks.GUN_WORKBENCH_ITEM.get()))
                    .displayItems(ModCreativeTabs::fillGear)
                    .build());


    public static final RegistryObject<CreativeModeTab> GUNPACKS = TABS.register("gunpacks",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + CreatePneumaticTacticals.MODID + ".gunpacks"))
                    .icon(() -> cyclingModuleIcon())
                    .withTabsBefore(GEAR_ID)
                    .withTabsAfter(new net.minecraft.resources.ResourceLocation("geckolib", "geckolib_examples"))
                    .withTabFactory(CyclingTab::new)
                    .displayItems(ModCreativeTabs::fillGunpacks)
                    .build());

    private ModCreativeTabs() {}

    public static void register(net.minecraftforge.eventbus.api.IEventBus modBus) {
        TABS.register(modBus);
    }

    /** the mod's own items: pods, vials, the ammo box, both workbenches */
    private static void fillGear(CreativeModeTab.ItemDisplayParameters params, CreativeModeTab.Output output) {
        output.accept(new ItemStack(ModItems.POD.get()));
        output.accept(new ItemStack(ModItems.PRESSURIZED_POD.get()));
        output.accept(new ItemStack(ModItems.AIR_VIAL.get()));
        output.accept(new ItemStack(ModItems.PRESSURIZED_AIR_VIAL.get()));
        output.accept(new ItemStack(dev.ignis.createpneumatictacticals.block.ModBlocks.AMMO_BOX_ITEM.get()));
        output.accept(new ItemStack(dev.ignis.createpneumatictacticals.block.ModBlocks.GUN_WORKBENCH_ITEM.get()));
        output.accept(new ItemStack(dev.ignis.createpneumatictacticals.block.ModBlocks.MODULE_WORKBENCH_ITEM.get()));
    }

    /** gunpack content: every module definition + one sample gun per receiver */
    private static void fillGunpacks(CreativeModeTab.ItemDisplayParameters params, CreativeModeTab.Output output) {
        // --- every module definition ---
        for (ModuleDefinition def : ModuleManager.all().values()) {
            output.accept(ModuleItem.of(def.id));
        }

        // --- per receiver: complete sample gun (no ammo pods in the tab:
        // users fill pods from ammo items, the pod pairs only clutter) ---
        for (ModuleDefinition receiver : ModuleManager.all().values()) {
            if (receiver.type != ModuleType.RECEIVER) continue;
            List<Holder<com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType>> compatible =
                    compatibleTypes(params, receiver);
            ItemStack gun = sampleGun(receiver, compatible);
            if (gun != null) output.accept(gun);
        }
    }

    /**
     * The gunpacks tab with a live icon: {@link CreativeModeTab#getIconItem}
     * caches the first icon forever, so the cycling supplier alone never
     * visibly changes — this subclass re-resolves the icon on every call.
     */
    private static final class CyclingTab extends CreativeModeTab {
        CyclingTab(Builder builder) {
            super(builder);
        }

        @Override
        public ItemStack getIconItem() {
            return cyclingModuleIcon();
        }
    }

    /**
     * Tab icon: cycles through the module definitions, one per real second.
     * Read through {@link CyclingTab#getIconItem} on every tab-strip redraw;
     * no ticker, no invalidation. Empty gunpacks fall back to the blank
     * module item.
     */
    private static ItemStack cyclingModuleIcon() {
        var all = ModuleManager.all();
        if (all.isEmpty()) return new ItemStack(ModItems.MODULE.get());
        var defs = new java.util.ArrayList<>(all.values());
        int pick = (int) ((System.currentTimeMillis() / 1000) % defs.size());
        return ModuleItem.of(defs.get(pick).id);
    }

    /** every potato projectile type the receiver's caliber accepts, registry order */
    private static List<Holder<com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType>> compatibleTypes(
            CreativeModeTab.ItemDisplayParameters params, ModuleDefinition receiver) {
        List<Holder<com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType>> out = new ArrayList<>();
        var lookup = params.holders().lookupOrThrow(
                com.simibubi.create.api.registry.CreateRegistries.POTATO_PROJECTILE_TYPE);
        for (var holder : lookup.listElements().toList()) {
            String ammoId = holder.unwrapKey().orElseThrow().location().toString();
            if (!dev.ignis.createpneumatictacticals.gun.AmmoTypes.isSelectable(ammoId)) continue;
            if (receiver.gunType.accepts(AmmoExtension.get(ammoId).gunType)) {
                out.add(holder);
            }
        }
        return out;
    }

    /** receiver + the first feed/barrel/supply its affected rules accept */
    private static ItemStack sampleGun(ModuleDefinition receiver,
            List<Holder<com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType>> compatible) {
        Map<ModuleType, ModuleDefinition> installed = new HashMap<>();
        installed.put(ModuleType.RECEIVER, receiver);
        for (ModuleType slot : List.of(ModuleType.FEED, ModuleType.BARREL, ModuleType.SUPPLY)) {
            ModuleDefinition pick = firstAffected(receiver, slot);
            if (pick == null) return null; // incomplete: no sample
            installed.put(slot, pick);
        }
        ItemStack gun = new ItemStack(ModItems.GUN.get());
        GunNbt.writeModules(gun, installed, Map.of());
        // first compatible ammo + a full magazine so the sample really
        // fires out of the box (survival fire gate requires ammoCount > 0)
        if (!compatible.isEmpty()) {
            GunNbt.setAmmo(gun, compatible.get(0).unwrapKey().orElseThrow().location().toString());
            int clip = installed.get(ModuleType.FEED).clipSize;
            if (clip > 0) GunNbt.setAmmoCount(gun, clip);
        }
        return gun;
    }

    private static ModuleDefinition firstAffected(ModuleDefinition receiver, ModuleType slot) {
        for (ModuleDefinition def : ModuleManager.all().values()) {
            if (def.type != slot) continue;
            for (ModuleDefinition.Affected a : receiver.affected) {
                if (a.type() == slot && a.matches(def.id)) return def;
            }
        }
        return null;
    }
}
