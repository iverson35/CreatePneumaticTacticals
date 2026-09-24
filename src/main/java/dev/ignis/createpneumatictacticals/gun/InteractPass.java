package dev.ignis.createpneumatictacticals.gun;

/**
 * Set (client side only) around the vanilla use flow that the interact key
 * triggers. While it is up, the gun's right-click backstops in
 * {@code GunItem} ({@code use} / {@code useOn} / {@code interactLivingEntity}
 * all return CONSUME) behave as PASS, so the block, entity and offhand use
 * paths run exactly as they would with an empty hand.
 *
 * <p>Only the client prediction reads it ({@code GunItem} gates on
 * {@code level.isClientSide}): the server needs no pass because there the
 * block's {@code use} already runs before the item's {@code useOn}, and the
 * offhand fallback is a client-side loop decision.
 */
public final class InteractPass {

    private static boolean active = false;

    public static boolean isActive() {
        return active;
    }

    public static void begin() {
        active = true;
    }

    public static void end() {
        active = false;
    }

    private InteractPass() {}
}
