package variableeconomy.core.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Names one account. A type of its own, so an account can't be mixed up with a player. A personal account's ID holds
 * its player's UUID, which is why {@link Accounts#personal(UUID)} works on any thread; every other account gets a
 * random UUID. An ID never changes and is never reused, even after its account ends, so it may be saved.
 *
 * @param value the account's UUID
 */
public record AccountId(UUID value) {
    /**
     * Checks the UUID is there.
     *
     * @param value the account's UUID
     */
    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * The UUID as text, which {@link UUID#fromString} reads back.
     *
     * @return the UUID as text
     */
    @Override
    public String toString() {
        return value.toString();
    }
}
