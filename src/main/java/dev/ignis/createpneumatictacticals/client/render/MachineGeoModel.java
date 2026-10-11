package dev.ignis.createpneumatictacticals.client.render;

import dev.ignis.createpneumatictacticals.CreatePneumaticTacticals;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

/**
 * Fixed model/texture/animation set of the weapon-module machine tool. Unlike
 * {@link ModuleGeoModel} nothing here depends on a stack: the machine's art
 * ships with the mod (assets/createpneumatictacticals) instead of coming from
 * a gunpack, so all three resources are plain constants — no cache, no lookup.
 */
public final class MachineGeoModel extends GeoModel<MachineAnimatable> {

    public static final MachineGeoModel INSTANCE = new MachineGeoModel();

    /** two cells long (model z -24..+8): the master block covers z -8..+8 */
    public static final ResourceLocation MODEL_ID = new ResourceLocation(
            CreatePneumaticTacticals.MODID, "geo/block/weapon_module_machine_tool.geo.json");

    public static final ResourceLocation TEXTURE = new ResourceLocation(
            CreatePneumaticTacticals.MODID, "textures/block/weapon_module_machine_tool.png");

    /** empty animation file: getAnimationResource must point at a real file, even
     *  though nothing plays from it (see {@link MachineAnimatable}) */
    public static final ResourceLocation ANIM_ID = new ResourceLocation(
            CreatePneumaticTacticals.MODID, "animations/block/weapon_module_machine_tool.animation.json");

    private MachineGeoModel() {
    }

    @Override
    public ResourceLocation getModelResource(MachineAnimatable animatable) {
        return MODEL_ID;
    }

    @Override
    public ResourceLocation getTextureResource(MachineAnimatable animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(MachineAnimatable animatable) {
        return ANIM_ID;
    }
}
