package variableeconomy.core.api.event;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.Reason;

import java.util.List;
import java.util.Objects;

/**
 * An account's balance changed. Posted at the end of the current or next server tick (work queued between ticks, such
 * as a packet's, waits for the next), once per account, listing every change made to it since its last event, in
 * order. Changes made while the server stops are posted before it saves. Waiting for the end of a tick means an action
 * with several steps (a market purchase takes the money, then gives the items) is always finished before the event is
 * posted.
 * <p>
 * Every change counts, whatever made it: trades, payouts, coin deposits and withdrawals, transfers, admin commands
 * and add-ons. The account may have been deleted by the time the event is posted.
 * <p>
 * Posted by Variable Economy; add-ons only listen for it.
 */
public final class BalanceChangedEvent extends Event {
    private final MinecraftServer server;
    private final AccountId account;
    private final long before;
    private final long after;
    private final List<Change> changes;

    /**
     * One change to the balance.
     *
     * @param amount the bits added (positive) or taken (negative)
     * @param reason why
     */
    public record Change(long amount, Reason reason) {
        /**
         * Checks the reason is there.
         *
         * @param amount the bits added (positive) or taken (negative)
         * @param reason why
         */
        public Change {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Posted by Variable Economy.
     *
     * @param server  the server
     * @param account the account
     * @param before  the balance before the first change listed
     * @param after   the balance after the last
     * @param changes every change, in order; copied
     */
    public BalanceChangedEvent(MinecraftServer server, AccountId account, long before, long after, List<Change> changes) {
        this.server = server;
        this.account = account;
        this.before = before;
        this.after = after;
        this.changes = List.copyOf(changes);
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
     * @return the account whose balance changed
     */
    public AccountId getAccount() {
        return account;
    }

    /**
     * The balance before the first change listed.
     *
     * @return the balance, in bits
     */
    public long getBefore() {
        return before;
    }

    /**
     * The balance after the last change listed. It may equal {@link #getBefore()} when the changes cancel out.
     *
     * @return the balance, in bits
     */
    public long getAfter() {
        return after;
    }

    /**
     * Every change made to the balance since the account's last event, in order; their amounts add up to {@code after − before}.
     *
     * @return the changes; the list can't be changed
     */
    public List<Change> getChanges() {
        return changes;
    }
}
