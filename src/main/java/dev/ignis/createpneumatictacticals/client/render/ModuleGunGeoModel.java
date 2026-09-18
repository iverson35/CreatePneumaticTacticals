package dev.ignis.createpneumatictacticals.client.render;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * Resolves an on-gun module's geo/texture/animation from its
 * {@link ModuleAnimatable} module id. Singleton: the animation processor is
 * per-GeoModel, and modules are rendered sequentially per frame.
 */
public final class ModuleGunGeoModel extends GeoModel<ModuleAnimatable> {

    public static final ModuleGunGeoModel INSTANCE = new ModuleGunGeoModel();

    private ModuleGunGeoModel() {}

    public static ResourceLocation modelId(ResourceLocation moduleId) {
        return new ResourceLocation(moduleId.getNamespace(), "geo/gun/" + moduleId.getPath() + ".geo.json");
    }

    public static ResourceLocation textureId(ResourceLocation moduleId) {
        return new ResourceLocation(moduleId.getNamespace(), "textures/gun/" + moduleId.getPath() + ".png");
    }

    @Override
    public ResourceLocation getModelResource(ModuleAnimatable animatable) {
        return modelId(animatable.moduleId());
    }

    @Override
    public ResourceLocation getTextureResource(ModuleAnimatable animatable) {
        return textureId(animatable.moduleId());
    }

    @Override
    public ResourceLocation getAnimationResource(ModuleAnimatable animatable) {
        return ModuleAnimatable.animationId(animatable.moduleId());
    }
}
