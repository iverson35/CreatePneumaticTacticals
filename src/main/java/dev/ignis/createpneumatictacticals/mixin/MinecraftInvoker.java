package dev.ignis.createpneumatictacticals.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Opens {@code Minecraft#startUseItem} (private) so the interact key can run
 * vanilla's own right-click use flow — the same loop, packet sequence and
 * hand ordering the right mouse button gets, without reimplementing it.
 */
@Mixin(Minecraft.class)
public interface MinecraftInvoker {

    @Invoker("startUseItem")
    void cpt$startUseItem();
}
