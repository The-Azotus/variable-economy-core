package variableeconomy.core.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An account as it was when it was read. It doesn't follow later changes: read it again to see them.
 *
 * @param id      the account
 * @param kind    what sort of account it is
 * @param name    the name players see; a personal account's is its owner's name, when known
 * @param owner   the owner's UUID
 * @param members everyone who may use the account, owner included, longest-standing first. When the owner leaves,
 *                ownership passes to the longest-standing remaining member.
 * @param balance the balance, in bits
 */
public record AccountInfo(AccountId id, AccountKind kind, String name, UUID owner, List<AccountMember> members,
                          long balance) {
    /**
     * Checks every field is there, and copies {@code members} so the record can't be changed.
     *
     * @param id      the account
     * @param kind    what sort of account it is
     * @param name    the name players see
     * @param owner   the owner's UUID
     * @param members everyone who may use the account, owner included, longest-standing first
     * @param balance the balance, in bits
     */
    public AccountInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(owner, "owner");
        members = List.copyOf(members);
    }

    /**
     * What {@code player} may do with the account.
     *
     * @param player the player's UUID
     * @return their role, or empty if they aren't a member
     */
    public Optional<AccountRole> roleOf(UUID player) {
        return members.stream().filter(member -> member.player().equals(player)).map(AccountMember::role).findFirst();
    }
}
