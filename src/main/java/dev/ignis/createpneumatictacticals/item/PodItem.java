package dev.ignis.createpneumatictacticals.item;

import com.simibubi.create.api.equipment.potatoCannon.PotatoCannonProjectileType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Pod (封装弹): air vial + content item. Content is stored in NBT; pods with
 * different contents do not stack. The content item must have a registered
 * PotatoCannonProjectileType.
 */
public class PodItem extends Item {

    public static final String KEY_CONTENT = "Content";

    public PodItem(Properties properties) {
        super(properties);
    }

    public static ItemStack ofContent(Item content, Item podItem) {
        ItemStack pod = new ItemStack(podItem);
        pod.getOrCreateTag().putString(KEY_CONTENT,
                content.builtInRegistryHolder().unwrapKey().orElseThrow().location().toString());
        return pod;
    }

    @Nullable
    public static ResourceLocation contentId(ItemStack pod) {
        if (!pod.hasTag() || !pod.getTag().contains(KEY_CONTENT)) return null;
        return ResourceLocation.tryParse(pod.getTag().getString(KEY_CONTENT));
    }

    /** the content item, or null if unset/unregistered */
    @Nullable
    public static Item contentItem(ItemStack pod) {
        ResourceLocation contentId = contentId(pod);
        if (contentId == null) return null;
        return net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(contentId);
    }

    /**
     * Resolve the potato projectile type for this pod's content, if any.
     */
    public static Optional<PotatoCannonProjectileType> projectileType(Level level, ItemStack pod) {
        Item item = contentItem(pod);
        if (item == null) return Optional.empty();
        return PotatoCannonProjectileType.getTypeForItem(level.registryAccess(), item).map(ref -> ref.value());
    }

    /**
     * The content item's display-name component. Uses the ITEM's own
     * name resolution (Item.getName) instead of the raw
     * {@code item.<ns>.<path>} lang key: block-items like pumpkin carry
     * their translated name under {@code block.<ns>.<path>}, so the raw
     * item key would render as literal "item.minecraft.pumpkin".
     */
    private Component contentName(ItemStack pod) {
        Item item = contentItem(pod);
        return item != null ? item.getName(new ItemStack(item))
                : Component.translatable("createpneumatictacticals.unknown_content");
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        ResourceLocation content = contentId(stack);
        if (content != null) {
            tooltip.add(Component.translatable("tooltip.createpneumatictacticals.pod_content",
                    contentName(stack)));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation content = contentId(stack);
        if (content != null) {
            // pod vs pressurized pod have separate name templates so a
            // pressurized pod doesn't show up as plain "Pod [...]"
            String key = stack.getItem() == dev.ignis.createpneumatictacticals.item.ModItems.PRESSURIZED_POD.get()
                    ? "item.createpneumatictacticals.pressurized_pod.named"
                    : "item.createpneumatictacticals.pod.named";
            return Component.translatable(key, contentName(stack));
        }
        return super.getName(stack);
    }
}