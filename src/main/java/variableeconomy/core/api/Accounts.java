package variableeconomy.core.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Accounts: reading them, each player's active account, and creating, sharing and deleting them. Get it from
 * {@link CoreApi#accounts()}.
 * <p>
 * The calls that change accounts act with the server's authority. They keep the server's rules (whether extra and
 * shared accounts are on, how many accounts a player may own, how many members an account may have), but they don't
 * check which player asked: the add-on decides who may do what, for example with {@link #role}.
 * <p>
 * <b>Which accounts exist.</b> A player's personal account exists from the first time the server sees them: when
 * they first join, or when a {@code ServerPlayer} for them is first handed to core. Other accounts exist from when
 * they are created until they end (deleted, or their FTB Teams party ended). Core remembers every account it has
 * made, ended ones included, so an ID that never named an account, or names one that has ended, is told apart from a
 * live one: reading it gives nothing, and money calls answer {@link BankResult#NO_ACCOUNT}.
 * <p>
 * Every method but {@link #personal} runs on the server thread only. Each change posts its
 * {@link variableeconomy.core.api.event.AccountEvent} once it is complete.
 */
public interface Accounts {
    /**
     * The ID of {@code player}'s personal account, online or not. The account exists once the server has seen the
     * player (see above); the ID can be worked out before that. Any thread.
     *
     * @param player the player's UUID
     * @return their personal account's ID
     */
    AccountId personal(UUID player);

    /**
     * A player's name, online or not: the online player's, then the server's profile cache, then the name core
     * remembered when they last joined. Unlike the profile cache alone, this finds every player the server has seen.
     *
     * @param server the server
     * @param player the player's UUID
     * @return their name, or empty for a player the server has never seen
     * @since 0.6.3
     */
    Optional<String> name(MinecraftServer server, UUID player);

    /**
     * The account as it is now.
     *
     * @param server the server
     * @param id     the account
     * @return the account, or empty if there is no such account or it has ended
     */
    Optional<AccountInfo> get(MinecraftServer server, AccountId id);

    /**
     * Every account {@code player} may use, in any role: their personal account first, then the others by name.
     *
     * @param server the server
     * @param player the player's UUID, online or not
     * @return the accounts, as they are now
     */
    List<AccountInfo> accessibleBy(MinecraftServer server, UUID player);

    /**
     * The account the player spends from and is paid into, unless an add-on names another: their personal account,
     * or another they picked with {@link #setActive}. If they can no longer spend from the one they picked (they left
     * it, or it was deleted), it is their personal account again.
     *
     * @param player the player
     * @return their active account
     */
    AccountId active(ServerPlayer player);

    /**
     * Makes {@code id} the player's active account. Remembered across restarts.
     *
     * @param player the player
     * @param id     the account; the player must be able to spend from it
     * @return {@link BankResult#OK}; {@link BankResult#NO_ACCOUNT} if there is no such account;
     *         {@link BankResult#NOT_ALLOWED} if the player can't spend from it
     */
    BankResult setActive(ServerPlayer player, AccountId id);

    /**
     * What {@code player} may do with an account.
     *
     * @param server the server
     * @param id     the account
     * @param player the player's UUID
     * @return their role, or empty if they aren't a member or there is no such account
     */
    Optional<AccountRole> role(MinecraftServer server, AccountId id, UUID player);

    /**
     * Opens a new, empty {@link AccountKind#NAMED} account owned by {@code owner}.
     *
     * @param server the server
     * @param owner  the owner's UUID, online or not
     * @param name   the account's name
     * @return the new account with {@link BankResult#OK}; {@link BankResult#DISABLED} if the server doesn't allow
     *         extra accounts; {@link BankResult#LIMIT_REACHED} if the owner already owns as many accounts as allowed,
     *         personal included; {@link BankResult#INVALID_NAME} if the name isn't one word of 1 to 24 letters,
     *         digits, '_' and '-'; {@link BankResult#NAME_TAKEN} if another named account has it, ignoring case
     */
    AccountResult create(MinecraftServer server, UUID owner, String name);

    /**
     * Deletes an empty account. Players whose active account it was are back on their personal account. Deleting
     * works even when the server has since turned extra accounts off.
     *
     * @param server the server
     * @param id     the account
     * @return {@link BankResult#OK}; {@link BankResult#NO_ACCOUNT}; {@link BankResult#PERSONAL_ACCOUNT} for a personal
     *         account; {@link BankResult#NOT_ALLOWED} for a party account, which ends with its party;
     *         {@link BankResult#NOT_EMPTY} if it holds money, money is owed to it, or an add-on holds money for it
     *         (a {@link NetWorthContributor} counts some)
     */
    BankResult delete(MinecraftServer server, AccountId id);

    /**
     * Adds {@code player} to an account with {@code role}, or changes their role if they are already a member. Making
     * someone the {@link AccountRole#OWNER} passes ownership to them, and the old owner becomes a
     * {@link AccountRole#MEMBER}. An owner can't be given another role directly: make someone else owner instead.
     * A member whose new role can't spend, and whose active account this was, is back on their personal account.
     *
     * @param server the server
     * @param id     the account
     * @param player the player's UUID, online or not
     * @param role   their new role
     * @return {@link BankResult#OK}, also when nothing changed; {@link BankResult#NO_ACCOUNT};
     *         {@link BankResult#PERSONAL_ACCOUNT} for a personal account; {@link BankResult#NOT_ALLOWED} for a party
     *         account, whose members follow the party; {@link BankResult#DISABLED} for adding a member if the server
     *         doesn't allow shared accounts; {@link BankResult#LIMIT_REACHED} for adding a member to an account that
     *         already has as many as allowed (owner included), or making someone owner who already owns as many
     *         accounts as allowed; {@link BankResult#NOT_ALLOWED} also for giving the owner another role
     */
    BankResult setMember(MinecraftServer server, AccountId id, UUID player, AccountRole role);

    /**
     * Takes {@code player} off an account. If they were its owner, ownership passes to the longest-standing remaining
     * member, whatever their role, even past the cap on accounts a player may own. If it was their active account,
     * their personal account is active again.
     *
     * @param server the server
     * @param id     the account
     * @param player the player's UUID, online or not
     * @return {@link BankResult#OK}; {@link BankResult#NO_ACCOUNT}; {@link BankResult#NOT_A_MEMBER};
     *         {@link BankResult#PERSONAL_ACCOUNT} for a personal account; {@link BankResult#NOT_ALLOWED} for a party
     *         account, or for the owner of an account with no other member (delete it instead)
     */
    BankResult removeMember(MinecraftServer server, AccountId id, UUID player);
}
