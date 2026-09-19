package dev.ignis.createpneumatictacticals.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;

/**
 * TEMP tuning command (delete once poses are final): /cptpose.
 * Live-edits the ready-pose offsets at runtime — no recompile/restart needed.
 * Covers BOTH view passes:
 *   - first person: ReadyPoseTransform (gun transform)
 *   - third person: ReadyArmPoseTuning (arm offsets — check with F5)
 *
 * /cptpose set <param> <value>   — set one parameter
 * /cptpose add <param> <delta>   — nudge by a delta
 * /cptpose reset                 — restore source defaults
 * /cptpose emit                  — print current values as paste-ready source
 *
 * First-person params (6 DOF): fp<High|Low><X|Y|Z|Pitch|Yaw|Roll>
 *   e.g. fpHighX fpHighY fpHighZ fpHighPitch fpHighYaw fpHighRoll
 *   (X/Y/Z block offsets; rotations in degrees, negative pitch = muzzle up)
 * Third-person params: tp<Low|High|Ads><R|L><XRot|YRot|ZRot|X|Y|Z>
 *   e.g. /cptpose set tpHighRXRot -1.4  (rotations in radians, 1 rad ≈ 57°)
 * Not registered: the @EventBusSubscriber annotation below is intentionally
 * removed so Forge never wires this class and /cptpose is absent in-game.
 * Re-enable for the next tuning session by restoring:
 *   @EventBusSubscriber(value = Dist.CLIENT, modid = CreatePneumaticTacticals.MODID)
 */
public final class CptPoseCommand {

    private CptPoseCommand() {}

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cptpose");

        for (String mode : new String[]{"set", "add"}) {
            boolean isAdd = mode.equals("add");
            root.then(Commands.literal(mode)
                    .then(Commands.argument("param", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("value", FloatArgumentType.floatArg())
                                    .executes(ctx -> setParam(
                                            ctx.getArgument("param", String.class),
                                            ctx.getArgument("value", Float.class),
                                            isAdd, ctx.getSource())))));
        }

        root.then(Commands.literal("reset").executes(ctx -> {
            reset();
            ctx.getSource().sendSuccess(() -> Component.literal("[cptpose] reset to source defaults"), false);
            return Command.SINGLE_SUCCESS;
        }));

        root.then(Commands.literal("emit").executes(ctx -> {
            for (String line : emitLines())
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            return Command.SINGLE_SUCCESS;
        }));

        event.getDispatcher().register(root);
    }

    // ---- first-person params: fields on ReadyPoseTransform ------------------

    private static final String[] FP_PARAMS = {"HIGH_Y", "HIGH_Z", "HIGH_ROT", "LOW_Y", "LOW_Z", "LOW_ROT"};

    // ---- third-person params: fields on ReadyArmPoseTuning.ArmOffset --------

    private static final String[] TP_POSES = {"LOW", "HIGH", "ADS"};
    private static final String[] TP_FIELDS = {"xRot", "yRot", "zRot", "x", "y", "z"};

    private static Object arm(String pose, char side) throws ReflectiveOperationException {
        return dev.ignis.createpneumatictacticals.client.ReadyArmPoseTuning.class
                .getField(pose.toUpperCase() + "_" + (side == 'R' ? "RIGHT" : "LEFT")).get(null);
    }

    private static int setParam(String param, Float value, boolean isAdd, CommandSourceStack src) {
        // first person: fp-prefixed names map to ReadyPoseTransform fields
        if (param.startsWith("fp")) {
            try {
                java.lang.reflect.Field f = dev.ignis.createpneumatictacticals.client.render.ReadyPoseTransform.class
                        .getField(param.substring(2).toUpperCase(java.util.Locale.ROOT));
                float v = isAdd ? f.getFloat(null) + value : value;
                f.setFloat(null, v);
                src.sendSuccess(() -> Component.literal("[cptpose] " + param + " = " + v), false);
                return Command.SINGLE_SUCCESS;
            } catch (ReflectiveOperationException e) {
                src.sendFailure(Component.literal("[cptpose] bad first-person param: " + param
                        + " (expected fp<High|Low><X|Y|Z|Pitch|Yaw|Roll>)"));
                return 0;
            }
        }
        // third person: tp<Pose><Side><Field>, e.g. tpHighRXRot, tpLowLY
        // (High is 4 chars — must match the pose name, not a fixed slice)
        if (param.startsWith("tp")) {
            try {
                String rest = param.substring(2);
                String pose;
                int poseLen;
                if (rest.regionMatches(true, 0, "High", 0, 4)) { pose = "HIGH"; poseLen = 4; }
                else if (rest.regionMatches(true, 0, "Low", 0, 3)) { pose = "LOW"; poseLen = 3; }
                else if (rest.regionMatches(true, 0, "Ads", 0, 3)) { pose = "ADS"; poseLen = 3; }
                else throw new IllegalArgumentException("pose must be Low|High|Ads");
                char side = rest.charAt(poseLen);     // R|L
                if (side != 'R' && side != 'L')
                    throw new IllegalArgumentException("side must be R or L");
                String fieldRaw = rest.substring(poseLen + 1);   // XRot|YRot|ZRot|X|Y|Z
                // ArmOffset fields start lowercase: XRot -> xRot, X -> x
                String field = fieldRaw.isEmpty() ? fieldRaw
                        : Character.toLowerCase(fieldRaw.charAt(0)) + fieldRaw.substring(1);
                Object target = arm(pose, side);
                java.lang.reflect.Field f = target.getClass().getField(field);
                float v = isAdd ? f.getFloat(target) + value : value;
                f.setFloat(target, v);
                src.sendSuccess(() -> Component.literal("[cptpose] " + param + " = " + v), false);
                return Command.SINGLE_SUCCESS;
            } catch (ReflectiveOperationException | StringIndexOutOfBoundsException
                     | IllegalArgumentException e) {
                src.sendFailure(Component.literal("[cptpose] bad third-person param: " + param
                        + " (" + e.getMessage() + "; expected tp<Low|High|Ads><R|L><XRot|YRot|ZRot|X|Y|Z>)"));
                return 0;
            }
        }
        src.sendFailure(Component.literal("[cptpose] unknown param: " + param));
        return 0;
    }

    private static void reset() {
        var t = dev.ignis.createpneumatictacticals.client.render.ReadyPoseTransform.class;
        setFp(t, "HIGH_X", 0f);      setFp(t, "HIGH_Y", 0.3f);   setFp(t, "HIGH_Z", -0.7f);
        setFp(t, "HIGH_PITCH", 50f); setFp(t, "HIGH_YAW", 0f);   setFp(t, "HIGH_ROLL", 5f);
        setFp(t, "LOW_X", -0.7f);    setFp(t, "LOW_Y", -0.09f);  setFp(t, "LOW_Z", 0f);
        setFp(t, "LOW_PITCH", -15f); setFp(t, "LOW_YAW", 45f);   setFp(t, "LOW_ROLL", 0f);
        for (String pose : TP_POSES) {
            for (char side : new char[]{'R', 'L'}) {
                try {
                    Object target = arm(pose, side);
                    for (String field : TP_FIELDS) target.getClass().getField(field).setFloat(target, 0f);
                } catch (ReflectiveOperationException ignored) {}
            }
        }
        // re-apply the source static-block defaults (mirror ReadyArmPoseTuning)
        try {
            var tuning = dev.ignis.createpneumatictacticals.client.ReadyArmPoseTuning.class;
            setArm(tuning.getField("LOW_RIGHT").get(null), 0.6f, -0.5f, 0f, 0f, 0f, 0f);
            setArm(tuning.getField("LOW_LEFT").get(null), 0.7f, -0.3f, 0f, 0f, 0f, 0f);
            setArm(tuning.getField("HIGH_RIGHT").get(null), -0.6f, 0.3f, 0f, 0f, 0f, 0f);
            setArm(tuning.getField("HIGH_LEFT").get(null), -0.4f, 0.3f, 0f, 0f, 0f, 0f);
            setArm(tuning.getField("ADS_RIGHT").get(null), -0.1f, 0.2f, 0f, 0f, 0f, 0f);
            setArm(tuning.getField("ADS_LEFT").get(null), -0.1f, 0.1f, 0f, 0f, 0f, 0f);
        } catch (ReflectiveOperationException ignored) {}
    }

    private static void setArm(Object arm, float xRot, float yRot, float zRot,
                               float x, float y, float z) {
        try {
            arm.getClass().getField("xRot").setFloat(arm, xRot);
            arm.getClass().getField("yRot").setFloat(arm, yRot);
            arm.getClass().getField("zRot").setFloat(arm, zRot);
            arm.getClass().getField("x").setFloat(arm, x);
            arm.getClass().getField("y").setFloat(arm, y);
            arm.getClass().getField("z").setFloat(arm, z);
        } catch (ReflectiveOperationException ignored) {}
    }

    private static void setFp(Class<?> c, String field, float v) {
        try { c.getField(field).setFloat(null, v); } catch (ReflectiveOperationException ignored) {}
    }

    private static java.util.List<String> emitLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        var t = dev.ignis.createpneumatictacticals.client.render.ReadyPoseTransform.class;
        out.add("// ---- ReadyPoseTransform (first person) ----");
        for (String pose : new String[]{"HIGH", "LOW"}) {
            out.add(String.format("%s_X=%.4g; %s_Y=%.4g; %s_Z=%.4g;",
                    pose, getFp(t, pose + "_X"), pose, getFp(t, pose + "_Y"), pose, getFp(t, pose + "_Z")));
            out.add(String.format("%s_PITCH=%.4g; %s_YAW=%.4g; %s_ROLL=%.4g;",
                    pose, getFp(t, pose + "_PITCH"), pose, getFp(t, pose + "_YAW"), pose, getFp(t, pose + "_ROLL")));
        }
        out.add("// ---- ReadyArmPoseTuning static block (third person) ----");
        for (String pose : TP_POSES) {
            for (String side : new String[]{"RIGHT", "LEFT"}) {
                try {
                    Object armObj = dev.ignis.createpneumatictacticals.client.ReadyArmPoseTuning.class
                            .getField(pose + "_" + side).get(null);
                    StringBuilder sb = new StringBuilder(pose + "_" + side + ".");
                    boolean any = false;
                    for (String field : TP_FIELDS) {
                        float v = armObj.getClass().getField(field).getFloat(armObj);
                        if (v != 0f) { sb.append(String.format("%s=%.4g; ", field, v)); any = true; }
                    }
                    if (any) out.add(sb.toString());
                } catch (ReflectiveOperationException ignored) {}
            }
        }
        return out;
    }

    private static float getFp(Class<?> c, String field) {
        try { return c.getField(field).getFloat(null); } catch (ReflectiveOperationException e) { return Float.NaN; }
    }
}