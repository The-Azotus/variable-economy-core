package variableeconomy.core.service;

import net.minecraft.server.MinecraftServer;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.Reason;
import variableeconomy.core.api.event.BalanceChangedEvent;
import variableeconomy.core.CoreSettings;
import variableeconomy.core.data.TransactionHistory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects balance changes and posts one {@link BalanceChangedEvent} per account at the end of the server tick, so a
 * listener never sees half of an action that changes several things (a purchase takes the money before it gives the
 * items). Flushed again when the server stops, before it saves. Server thread only.
 */
public final class BalanceEvents {
    private record Pending(MinecraftServer server, long before, long after, List<BalanceChangedEvent.Change> changes) {
    }

    private static final Map<AccountId, Pending> pending = new LinkedHashMap<>();

    private BalanceEvents() {
    }

    /**
     * One change: the balance went from {@code before} to {@code after} by {@code amount} (signed), for {@code reason}.
     * It goes in the account's {@link TransactionHistory} at once, and in its event at the end of the tick.
     */
    public static void changed(MinecraftServer server, AccountId account, long before, long after, long amount, Reason reason) {
        TransactionHistory.get(server).add(account.value(),
                new TransactionHistory.Line(System.currentTimeMillis(), amount, reason.id(), reason.actor(), after),
                CoreSettings.historySize());
        Pending old = pending.get(account);
        List<BalanceChangedEvent.Change> changes = old == null ? new ArrayList<>() : old.changes();
        changes.add(new BalanceChangedEvent.Change(amount, reason));
        pending.put(account, new Pending(server, old == null ? before : old.before(), after, changes));
    }

    /** Posts every collected change, one event per account, in the order the accounts first changed. */
    public static void flush() {
        if (pending.isEmpty()) {
            return;
        }
        List<Map.Entry<AccountId, Pending>> due = List.copyOf(pending.entrySet());
        pending.clear();
        for (Map.Entry<AccountId, Pending> entry : due) {
            Pending p = entry.getValue();
            CoreEvents.post(new BalanceChangedEvent(p.server(), entry.getKey(), p.before(), p.after(), p.changes()));
        }
    }

    /** Forgets anything not yet posted, when the server has stopped. */
    public static void clear() {
        pending.clear();
    }
}
