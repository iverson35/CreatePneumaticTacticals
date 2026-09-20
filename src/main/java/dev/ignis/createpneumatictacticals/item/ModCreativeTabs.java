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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mod's creative tab. Three groups, in order:
 * <ol>
 *   <li>base items: empty gun / pods / air vials</li>
 *   <li>every gunpack module definition as a ready NBT instance
 *       ({@link ModuleItem#of}) — the module item is NBT-selected, a plain
 *       stack is useless in the tab</li>
 *   <li>per receiver: a COMPLETE sample gun (receiver + the first
 *       feed/barrel/supply its affected rules accept, with the first
 *       compatible ammo selected) and a pod of every ammo type the
 *       receiver's caliber accepts — both pod and pressurized pod so
 *       cartridge guns are covered too</li>
 * </ol>
 * Content is built lazily on tab display, after gunpacks are loaded —
 * module definitions don't exist at static-init time.
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreatePneumaticTacticals.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + CreatePneumaticTacticals.MODID))
                    .icon(() -> new ItemStack(ModItems.GUN.get()))
                    .displayItems(ModCreativeTabs::fill)
                    .build());

    private ModCreativeTabs() {}

    public static void register(net.minecraftforge.eventbus.api.IEventBus modBus) {
        TABS.register(modBus);
    }

    private static void fill(CreativeModeTab.ItemDisplayParameters params, CreativeModeTab.Output output) {
        // --- base items ---
        output.accept(new ItemStack(ModItems.GUN.get()));
        output.accept(new ItemStack(ModItems.POD.get()));
        output.accept(new ItemStack(ModItems.PRESSURIZED_POD.get()));
        output.accept(new ItemStack(ModItems.AIR_VIAL.get()));
        output.accept(new ItemStack(ModItems.PRESSURIZED_AIR_VIAL.get()));

        // --- every module definition ---
        for (ModuleDefinition def : ModuleManager.all().values()) {
            output.accept(ModuleItem.of(def.id));
        }

        // --- per receiver: complete sample gun + compatible ammo pods ---
        java.util.Set<String> podAmmoDone = new java.util.HashSet<>();
        for (ModuleDefinition receiver : ModuleManager.all().values()) {
            if (receiver.type != ModuleType.RECEIVER) continue;
            List<Holder<com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType>> compatible =
                    compatibleTypes(params, receiver);
            ItemStack gun = sampleGun(receiver, compatible);
            if (gun != null) output.accept(gun);
            for (Holder<com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType> type : compatible) {
                String ammoKey = type.unwrapKey().orElseThrow().location().toString();
                if (!podAmmoDone.add(ammoKey)) continue; // shared calibers: once
                Item content = type.value().items().size() > 0
                        ? type.value().items().get(0).value() : null;
                if (content == null) continue;
                output.accept(PodItem.ofContent(content, ModItems.POD.get()));
                output.accept(PodItem.ofContent(content, ModItems.PRESSURIZED_POD.get()));
            }
        }
    }

    /** every potato projectile type the receiver's caliber accepts, registry order */
    private static List<Holder<com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType>> compatibleTypes(
            CreativeModeTab.ItemDisplayParameters params, ModuleDefinition receiver) {
        List<Holder<com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType>> out = new ArrayList<>();
        var lookup = params.holders().lookupOrThrow(
                com.simibubi.create.api.registry.CreateRegistries.POTATO_PROJECTILE_TYPE);
        for (var holder : lookup.listElements().toList()) {
            String ammoId = holder.unwrapKey().orElseThrow().location().toString();
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