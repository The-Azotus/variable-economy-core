package variableeconomy.core.bank;

import java.util.List;
import java.util.UUID;

/**
 * An FTB Teams party, as core's party accounts see it.
 *
 * @param id      the party's own ID in FTB Teams; never a player's, and not its account's ID
 * @param name    the party's name
 * @param owner   the party's owner, who is paid its account's balance if the party is deleted
 * @param members every member, owner included, the owner first and the rest by UUID (FTB Teams doesn't say who joined
 *                first; it hands ownership on by itself)
 */
public record Party(UUID id, String name, UUID owner, List<UUID> members) {
    public Party {
        members = List.copyOf(members);
    }
}
