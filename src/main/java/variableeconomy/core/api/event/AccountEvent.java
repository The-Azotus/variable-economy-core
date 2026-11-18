package variableeconomy.core.api.event;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountRole;

import java.util.Optional;
import java.util.UUID;

/**
 * Something happened to an account: it was created or deleted, or its members changed. Listen for one of the nested
 * events, or for this class to hear them all. Each is posted once the change is complete, whether a player, an admin,
 * FTB Teams or an add-on made it.
 * <p>
 * When an owner leaves and ownership passes on, {@link MemberRemoved} for the old owner is followed by
 * {@link RoleChanged} for the new one.
 * <p>
 * Posted by Variable Economy; add-ons only listen for them. The class isn't abstract only so that a listener can be
 * registered for it (NeoForge refuses abstract event classes); it is only ever posted as one of the nested events.
 */
public class AccountEvent extends Event {
    private final MinecraftServer server;
    private final AccountId account;

    /**
     * For the nested events.
     *
     * @param server  the server
     * @param account the account
     */
    protected AccountEvent(MinecraftServer server, AccountId account) {
        this.server = server;
        this.account = account;
    }

    /**
     * The server.
     *
     * @return the server the account is on
     */
    public MinecraftServer getServer() {
        return server;
    }

    /**
     * The account.
     *
     * @return the account that changed
     */
    public AccountId getAccount() {
        return account;
    }

    /**
     * An account was created: a personal account the first time the server sees its player, an extra account, or a
     * party's account. Posted once per account, ever.
     */
    public static final class Created extends AccountEvent {
        private final AccountKind kind;
        private final UUID owner;
        private final String name;

        /**
         * Posted by Variable Economy.
         *
         * @param server  the server
         * @param account the new account
         * @param kind    what sort of account it is
         * @param owner   its owner
         * @param name    its name
         */
        public Created(MinecraftServer server, AccountId account, AccountKind kind, UUID owner, String name) {
            super(server, account);
            this.kind = kind;
            this.owner = owner;
            this.name = name;
        }

        /**
         * What sort of account it is.
         *
         * @return its kind
         */
        public AccountKind getKind() {
            return kind;
        }

        /**
         * Who owns it.
         *
         * @return the owner's UUID
         */
        public UUID getOwner() {
            return owner;
        }

        /**
         * Its name.
         *
         * @return the name players see
         */
        public String getName() {
            return name;
        }
    }

    /**
     * An account was deleted, or ended with its FTB Teams party. Any balance it had was moved to another account
     * before it ended; both accounts get a {@link BalanceChangedEvent} at the end of the tick.
     */
    public static final class Deleted extends AccountEvent {
        private final long balanceMoved;
        private final Optional<AccountId> balanceTo;

        /**
         * Posted by Variable Economy.
         *
         * @param server       the server
         * @param account      the deleted account
         * @param balanceMoved the balance it had, in bits
         * @param balanceTo    where that balance went; empty when there was none
         */
        public Deleted(MinecraftServer server, AccountId account, long balanceMoved, Optional<AccountId> balanceTo) {
            super(server, account);
            this.balanceMoved = balanceMoved;
            this.balanceTo = balanceTo;
        }

        /**
         * The balance the account had when it ended.
         *
         * @return the balance, in bits; 0 for an account deleted empty
         */
        public long getBalanceMoved() {
            return balanceMoved;
        }

        /**
         * Where the balance went, such as the owner's personal account for a party that ended with money in its
         * account.
         *
         * @return the account paid, or empty when there was no balance
         */
        public Optional<AccountId> getBalanceTo() {
            return balanceTo;
        }
    }

    /** A player became a member of an account. */
    public static final class MemberAdded extends AccountEvent {
        private final UUID player;
        private final AccountRole role;

        /**
         * Posted by Variable Economy.
         *
         * @param server  the server
         * @param account the account
         * @param player  the new member
         * @param role    their role
         */
        public MemberAdded(MinecraftServer server, AccountId account, UUID player, AccountRole role) {
            super(server, account);
            this.player = player;
            this.role = role;
        }

        /**
         * The new member.
         *
         * @return their UUID
         */
        public UUID getPlayer() {
            return player;
        }

        /**
         * Their role.
         *
         * @return what they may do with the account
         */
        public AccountRole getRole() {
            return role;
        }
    }

    /** A player stopped being a member of an account: they left, were removed, or left the FTB Teams party. */
    public static final class MemberRemoved extends AccountEvent {
        private final UUID player;
        private final AccountRole role;

        /**
         * Posted by Variable Economy.
         *
         * @param server  the server
         * @param account the account
         * @param player  the former member
         * @param role    the role they had
         */
        public MemberRemoved(MinecraftServer server, AccountId account, UUID player, AccountRole role) {
            super(server, account);
            this.player = player;
            this.role = role;
        }

        /**
         * The former member.
         *
         * @return their UUID
         */
        public UUID getPlayer() {
            return player;
        }

        /**
         * The role they had.
         *
         * @return their role before they left
         */
        public AccountRole getRole() {
            return role;
        }
    }

    /** A member's role changed, including ownership passing to them or away from them. */
    public static final class RoleChanged extends AccountEvent {
        private final UUID player;
        private final AccountRole oldRole;
        private final AccountRole newRole;

        /**
         * Posted by Variable Economy.
         *
         * @param server  the server
         * @param account the account
         * @param player  the member
         * @param oldRole their role before
         * @param newRole their role now
         */
        public RoleChanged(MinecraftServer server, AccountId account, UUID player, AccountRole oldRole, AccountRole newRole) {
            super(server, account);
            this.player = player;
            this.oldRole = oldRole;
            this.newRole = newRole;
        }

        /**
         * The member.
         *
         * @return their UUID
         */
        public UUID getPlayer() {
            return player;
        }

        /**
         * Their role before.
         *
         * @return the old role
         */
        public AccountRole getOldRole() {
            return oldRole;
        }

        /**
         * Their role now.
         *
         * @return the new role
         */
        public AccountRole getNewRole() {
            return newRole;
        }
    }
}
