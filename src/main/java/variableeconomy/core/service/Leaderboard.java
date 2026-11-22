package variableeconomy.core.service;

import variableeconomy.core.CoreSettings;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.bank.OwnBank;
import variableeconomy.core.data.AccountRegistry;
import variableeconomy.core.data.OwedPayments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The richest players, or the richest accounts ({@code leaderboard.rank_by}). An account's worth is its balance, plus
 * money owed to it and money add-ons hold for it (net-worth contributors, such as the exchange's open buy orders).
 * Ranked by owner, each player's line adds up every account they own: personal, named and party. Ranked by account,
 * each account has its own line, a personal one under its player's name. Players the server has never seen, empty
 * lines and (when set) accounts operators own are left out. Server thread only: it reads the bank.
 */
public final class Leaderboard {
    private Leaderboard() {
    }

    /** One ranked line: a player (by owner) or an account (by account), its name and its worth. */
    public record Entry(UUID id, String name, long balance) {
    }

    /**
     * What one player is shown: the top of the board, and their own place on it.
     *
     * @param ownRank the player's rank from 1 (by account, their active account's), or 0 when they aren't on it
     * @param you     the line that is the viewer's: their UUID, or by account their active account's ID
     */
    public record View(List<Entry> top, int ownRank, int ranked, UUID you) {
    }

    /** The board as {@code viewer} sees it, ranked now. */
    public static View viewFor(ServerPlayer viewer) {
        return viewFor(viewer, id -> profile(viewer.server, id));
    }

    /** The same, naming players with {@code profiles} instead of the server's (GameTests have no profile cache). */
    public static View viewFor(ServerPlayer viewer, Function<UUID, Optional<GameProfile>> profiles) {
        MinecraftServer server = viewer.server;
        Predicate<GameProfile> excluded = CoreSettings.leaderboardExcludeOperators()
                ? profile -> server.getPlayerList().isOp(profile)
                : profile -> false;
        Map<AccountId, Long> worth = worthByAccount(server);
        List<Entry> ranked;
        UUID you;
        if (CoreSettings.leaderboardByAccount()) {
            AccountRegistry registry = AccountRegistry.get(server);
            Map<UUID, Long> balances = new HashMap<>();
            worth.forEach((account, amount) -> balances.put(account.value(), amount));
            ranked = rank(balances, id -> accountName(registry, id, profiles, excluded));
            you = CoreServices.ACCOUNTS.active(viewer).value();
        } else {
            ranked = rank(byOwner(server, worth), profiles, excluded);
            you = viewer.getUUID();
        }
        int ownRank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).id().equals(you)) {
                ownRank = i + 1;
                break;
            }
        }
        return new View(List.copyOf(ranked.subList(0, Math.min(CoreSettings.leaderboardSize(), ranked.size()))),
                ownRank, ranked.size(), you);
    }

    /**
     * Every account's worth: its balance, money owed to it and money add-ons hold for it. Money owed to, or held for,
     * an account that has ended counts for its last owner's personal account, where it will be paid.
     */
    public static Map<AccountId, Long> worthByAccount(MinecraftServer server) {
        Map<AccountId, Long> worth = new HashMap<>();
        OwnBank.get(server).balances().forEach((id, balance) -> worth.merge(new AccountId(id), balance, Long::sum));
        for (OwedPayments.Payment payment : OwedPayments.get(server).list()) {
            worth.merge(CoreServices.MONEY.payee(server, payment.account()), payment.amount(), Long::sum);
        }
        CoreServices.NET_WORTH.held(server).forEach((account, amount) ->
                worth.merge(CoreServices.MONEY.payee(server, account), amount, Long::sum));
        return worth;
    }

    /** Worth added up by owner: each account counts for whoever owns it (an unknown ID for itself, as a player). */
    public static Map<UUID, Long> byOwner(MinecraftServer server, Map<AccountId, Long> worth) {
        AccountRegistry registry = AccountRegistry.get(server);
        Map<UUID, Long> owners = new HashMap<>();
        worth.forEach((account, amount) -> {
            UUID owner = registry.entry(account.value()).map(AccountRegistry.Entry::owner).orElse(account.value());
            owners.merge(owner, amount, Long::sum);
        });
        return owners;
    }

    /** What net-worth contributors hold, added up by the owner of each account it's held for. */
    public static Map<UUID, Long> heldForPlayers(MinecraftServer server) {
        Map<AccountId, Long> held = new HashMap<>();
        CoreServices.NET_WORTH.held(server).forEach((account, amount) -> held.merge(CoreServices.MONEY.payee(server, account), amount, Long::sum));
        return byOwner(server, held);
    }

    /** Bank balances with money held for players elsewhere (the exchange, owed payouts) added, by player. */
    @SafeVarargs
    public static Map<UUID, Long> withHeld(Map<UUID, Long> balances, Map<UUID, Long>... held) {
        Map<UUID, Long> total = new HashMap<>(balances);
        for (Map<UUID, Long> more : held) {
            more.forEach((id, amount) -> total.merge(id, amount, Long::sum));
        }
        return total;
    }

    /**
     * Every player worth listing, richest first; equal balances by name. Players with no balance, no known profile,
     * or an excluded profile are left out.
     */
    public static List<Entry> rank(Map<UUID, Long> balances, Function<UUID, Optional<GameProfile>> profiles,
                                   Predicate<GameProfile> excluded) {
        return rank(balances, id -> profiles.apply(id)
                .filter(profile -> profile.getName() != null && !profile.getName().isEmpty() && !excluded.test(profile))
                .map(GameProfile::getName));
    }

    /** Every line worth listing, richest first, equal balances by name: those with a balance and a name. */
    public static List<Entry> rank(Map<UUID, Long> balances, Function<UUID, Optional<String>> names) {
        List<Entry> entries = new ArrayList<>();
        balances.forEach((id, balance) -> {
            if (balance > 0) {
                names.apply(id).ifPresent(name -> entries.add(new Entry(id, name, balance)));
            }
        });
        entries.sort(Comparator.comparingLong(Entry::balance).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /**
     * An account's name on the board: a personal account's player, a named or party account's own name. Empty for an
     * account whose owner is excluded or unknown, or that has ended.
     */
    private static Optional<String> accountName(AccountRegistry registry, UUID id, Function<UUID, Optional<GameProfile>> profiles,
                                                Predicate<GameProfile> excluded) {
        Optional<AccountRegistry.Entry> entry = registry.entry(id);
        if (entry.isPresent() && entry.get().ended()) {
            return Optional.empty();
        }
        UUID owner = entry.map(AccountRegistry.Entry::owner).orElse(id);
        Optional<GameProfile> profile = profiles.apply(owner)
                .filter(p -> p.getName() != null && !p.getName().isEmpty());
        if (profile.isEmpty() || excluded.test(profile.get())) {
            return Optional.empty();
        }
        boolean personal = entry.isEmpty() || entry.get().kind() == AccountKind.PERSONAL;
        return Optional.of(personal ? profile.get().getName() : entry.get().name());
    }

    /**
     * An online player's profile, or one the server has seen before (offline players, by their last name): from the
     * server's profile cache, or else from the name core remembered when they last joined, since the cache keeps only
     * the players seen most recently.
     */
    public static Optional<GameProfile> profile(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        GameProfileCache cache = server.getProfileCache();
        Optional<GameProfile> cached = cache == null ? Optional.empty() : cache.get(id);
        if (cached.isPresent()) {
            return cached;
        }
        return AccountRegistry.get(server).personalName(id).map(name -> new GameProfile(id, name));
    }
}
