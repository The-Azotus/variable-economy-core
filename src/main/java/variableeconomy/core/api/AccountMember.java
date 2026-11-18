package variableeconomy.core.api;

import java.util.Objects;
import java.util.UUID;

/**
 * One player who may use an account, and how.
 *
 * @param player the player's UUID
 * @param role   what they may do with the account
 */
public record AccountMember(UUID player, AccountRole role) {
    /**
     * Checks both are there.
     *
     * @param player the player's UUID
     * @param role   what they may do with the account
     */
    public AccountMember {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(role, "role");
    }
}
