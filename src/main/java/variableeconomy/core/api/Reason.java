package variableeconomy.core.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Why money moved. It names the movement in the audit log, and, from a later 0.x release, in the money-supply stats,
 * which add up the money created and destroyed for each reason.
 *
 * @param id    what the money is for, in the add-on's namespace, such as {@code mymod:quest_reward}. Keep the set of
 *              IDs small and fixed, since the stats count each one.
 * @param actor the player who caused the movement, if a player did; the audit log names them
 */
public record Reason(ResourceLocation id, Optional<UUID> actor) {
    /**
     * Checks both are there.
     *
     * @param id    what the money is for, in the add-on's namespace
     * @param actor the player who caused the movement, if a player did
     */
    public Reason {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
    }

    /**
     * A reason no player caused, such as a scheduled reward.
     *
     * @param id what the money is for, in the add-on's namespace
     * @return the reason
     */
    public static Reason of(ResourceLocation id) {
        return new Reason(id, Optional.empty());
    }

    /**
     * A reason a player caused, such as a purchase they made.
     *
     * @param id    what the money is for, in the add-on's namespace
     * @param actor the player's UUID
     * @return the reason
     */
    public static Reason by(ResourceLocation id, UUID actor) {
        return new Reason(id, Optional.of(actor));
    }
}
