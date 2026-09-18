package dev.ignis.createpneumatictacticals.client.render;

import net.minecraft.world.item.ItemStack;

/**
 * Abstraction for module visual overrides, so a future Armourer's Workshop
 * integration can replace the default module model rendering with an
 * "item"-type skin while keeping locator/keypoint semantics unchanged.
 * Only display is overridden; animations stay with the default renderer.
 */
public interface ModuleRenderOverride {

    /**
     * @param gunStack    the assembled gun stack
     * @param moduleId    module definition id currently installed in its slot
     * @return true if this override claims rendering of the module's model
     */
    boolean claimsRender(ItemStack gunStack, net.minecraft.resources.ResourceLocation moduleId);
}