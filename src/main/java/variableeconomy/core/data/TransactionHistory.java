package variableeconomy.core.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every account's recent money movements, saved with the world: one line per balance change, whatever made it
 * (trades, payouts, coins, payments, admin commands, an account ending), with its reason, who caused it and the balance
 * after. Each account keeps its newest {@code history.size} lines; 0 records nothing. This is the record of every money
 * movement; the audit log keeps admin actions.
 * <p>
 * Server thread only.
 */
public final class TransactionHistory extends SavedData {
    public static final String ID = "variable_economy_history";
    private static final SavedData.Factory<TransactionHistory> FACTORY =
            new SavedData.Factory<>(TransactionHistory::new, TransactionHistory::fromTag, null);

    /**
     * One balance change.
     *
     * @param time    when, in milliseconds since the epoch
     * @param amount  the change, signed: positive when money came in
     * @param reason  why, as its {@code Reason} named it
     * @param actor   the player who caused it, if any
     * @param balance the balance after it
     */
    public record Line(long time, long amount, ResourceLocation reason, Optional<UUID> actor, long balance) {
    }

    private final Map<UUID, Deque<Line>> lines = new HashMap<>();

    public static TransactionHistory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** Adds a line to an account's history, keeping at most {@code keep} (none when 0). */
    public void add(UUID account, Line line, int keep) {
        if (keep <= 0) {
            return;
        }
        Deque<Line> history = lines.computeIfAbsent(account, id -> new ArrayDeque<>());
        history.addFirst(line);
        while (history.size() > keep) {
            history.removeLast();
        }
        setDirty();
    }

    /** An account's history, newest first. */
    public List<Line> of(UUID account) {
        Deque<Line> history = lines.get(account);
        return history == null ? List.of() : List.copyOf(history);
    }

    public static TransactionHistory fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        TransactionHistory history = new TransactionHistory();
        for (Tag element : tag.getList("accounts", Tag.TAG_COMPOUND)) {
            CompoundTag account = (CompoundTag) element;
            if (!account.hasUUID("id")) {
                continue;
            }
            Deque<Line> lines = new ArrayDeque<>();
            for (Tag l : account.getList("lines", Tag.TAG_COMPOUND)) {
                CompoundTag line = (CompoundTag) l;
                ResourceLocation reason = ResourceLocation.tryParse(line.getString("reason"));
                if (reason == null) {
                    continue;
                }
                lines.addLast(new Line(line.getLong("time"), line.getLong("amount"), reason,
                        line.hasUUID("actor") ? Optional.of(line.getUUID("actor")) : Optional.empty(), line.getLong("balance")));
            }
            if (!lines.isEmpty()) {
                history.lines.put(account.getUUID("id"), lines);
            }
        }
        return history;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag accounts = new ListTag();
        lines.forEach((id, history) -> {
            CompoundTag account = new CompoundTag();
            account.putUUID("id", id);
            ListTag list = new ListTag();
            for (Line line : history) {
                CompoundTag l = new CompoundTag();
                l.putLong("time", line.time());
                l.putLong("amount", line.amount());
                l.putString("reason", line.reason().toString());
                line.actor().ifPresent(actor -> l.putUUID("actor", actor));
                l.putLong("balance", line.balance());
                list.add(l);
            }
            account.put("lines", list);
            accounts.add(account);
        });
        tag.put("accounts", accounts);
        return tag;
    }

    /** The newest lines first, a page at a time. */
    public static <T> List<T> page(List<T> all, int page, int perPage) {
        int from = Math.max(0, (page - 1) * perPage);
        return from >= all.size() ? new ArrayList<>() : new ArrayList<>(all.subList(from, Math.min(all.size(), from + perPage)));
    }
}
