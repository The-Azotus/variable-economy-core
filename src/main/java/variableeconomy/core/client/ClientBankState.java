package variableeconomy.core.client;

import variableeconomy.core.CoreModule;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.bank.CoinMath;
import variableeconomy.core.bank.Denomination;
import variableeconomy.core.coin.Coins;
import variableeconomy.core.network.BankPayloads;
import variableeconomy.core.service.BankService;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the client knows about the bank, filled only from server packets. Display only: every request is checked again
 * on the server. Render thread only. Uses no client-only classes, so the network code can reference it on both sides.
 */
public final class ClientBankState {
    private static final UUID NONE = new UUID(0, 0);
    private static BankPayloads.BankState state = emptyState();
    /** The leaderboard, once the screen has asked for it. */
    @Nullable
    private static BankPayloads.Leaderboard leaderboard;
    /** The shown account's history, once the screen has asked for it. */
    @Nullable
    private static BankPayloads.History history;
    private static Component status = Component.empty();
    private static boolean statusIsError;
    private static int version;

    private ClientBankState() {
    }

    private static BankPayloads.BankState emptyState() {
        return new BankPayloads.BankState(List.of(), NONE, NONE, List.of(), List.of(), Map.of(), 0, Map.of(), true, false, false);
    }

    public static void applyState(BankPayloads.BankState received) {
        state = received;
        version++;
    }

    public static void applyLeaderboard(BankPayloads.Leaderboard received) {
        leaderboard = received;
        version++;
    }

    public static void applyHistory(BankPayloads.History received) {
        history = received;
        version++;
    }

    /** The history last received, or null until the screen asks for one. */
    @Nullable
    public static BankPayloads.History history() {
        return history;
    }

    public static void applyOutcome(BankPayloads.BankOutcome outcome) {
        CoreModule.LOGGER.debug("Bank outcome: {}", outcome);
        if (outcome.result() == BankService.Result.OK) {
            status = Component.translatable(outcome.deposit() ? "screen.variable_economy.bank.deposited" : "screen.variable_economy.bank.withdrew",
                    PriceFormat.money(PriceFormat.amount(outcome.amount())));
            statusIsError = false;
        } else {
            status = Component.translatable("screen.variable_economy.bank.result." + outcome.result().name().toLowerCase(Locale.ROOT));
            statusIsError = true;
        }
        version++;
    }

    public static void applyAccountOutcome(BankPayloads.AccountOutcome outcome) {
        CoreModule.LOGGER.debug("Account outcome: {}", outcome);
        status = Component.translatable(outcome.key(), outcome.name());
        statusIsError = !outcome.ok();
        version++;
    }

    /** When the bank screen closes. */
    public static void clear() {
        state = emptyState();
        leaderboard = null;
        history = null;
        status = Component.empty();
        statusIsError = false;
        version++;
    }

    /** The leaderboard, or null until it's been received. */
    @Nullable
    public static BankPayloads.Leaderboard leaderboard() {
        return leaderboard;
    }

    /** Whether the server has the leaderboard turned on. */
    public static boolean leaderboardEnabled() {
        return state.leaderboardEnabled();
    }

    /** Whether the server lets players open extra accounts, and share them. */
    public static boolean extraEnabled() {
        return state.extraEnabled();
    }

    public static boolean sharedEnabled() {
        return state.sharedEnabled();
    }

    /** The accounts the player may use, personal first. */
    public static List<BankPayloads.AccountLine> accounts() {
        return state.accounts();
    }

    /** The account the screen shows, once the server has said. */
    public static Optional<BankPayloads.AccountLine> shown() {
        return state.shownLine();
    }

    /** The player's active account. */
    public static UUID active() {
        return state.active();
    }

    /** The shown account's members, longest-standing first; empty for a personal account. */
    public static List<BankPayloads.MemberLine> members() {
        return state.members();
    }

    /** The invitations the player holds. */
    public static List<BankPayloads.InviteLine> invites() {
        return state.invites();
    }

    /** The shown account's balance. */
    public static long balance() {
        return shown().map(BankPayloads.AccountLine::balance).orElse(0L);
    }

    /** Whether the player may take money out of the shown account. */
    public static boolean canSpendShown() {
        return shown().map(line -> line.role().canSpend()).orElse(false);
    }

    /** Whether the shown account is the player's active one. */
    public static boolean showingActive() {
        return shown().map(line -> line.id().equals(state.active())).orElse(true);
    }

    /** An account's name as the screens show it: "Personal" for the player's own. */
    public static Component label(BankPayloads.AccountLine line) {
        return line.kind() == AccountKind.PERSONAL ? Component.translatable("screen.variable_economy.bank.account.personal")
                : Component.literal(line.name());
    }

    /** A role as the screens show it. */
    public static Component role(AccountRole role) {
        return Component.translatable("command.variable_economy.account.role." + role.name().toLowerCase(Locale.ROOT));
    }

    /** The coins the bank moves, largest first. */
    public static List<Denomination> coins() {
        return Coins.denominations();
    }

    /** How many plain copies of {@code coin} the main inventory holds. */
    public static int held(Denomination coin) {
        return state.coins().getOrDefault(BuiltInRegistries.ITEM.getKey(coin.item()), 0);
    }

    /** What every coin in the main inventory is worth: what "deposit all" would pay in. */
    public static long carriedCoinValue() {
        long total = 0;
        for (Denomination coin : coins()) {
            total += (long) held(coin) * coin.value();
        }
        return total;
    }

    /** Whether {@code counts} of {@link #coins()} would fit in the main inventory, counted as the server counts it. */
    public static boolean coinsFit(long[] counts) {
        return CoinMath.fits(coins(), counts, state.emptySlots(),
                coin -> state.stackRoom().getOrDefault(BuiltInRegistries.ITEM.getKey(coin.item()), 0),
                coin -> new ItemStack(coin.item()).getMaxStackSize());
    }

    /** Whether the balance looks enough for {@code total}. The server checks again. */
    public static boolean looksAffordable(long total) {
        return balance() >= total;
    }

    public static Component status() {
        return status;
    }

    public static boolean statusIsError() {
        return statusIsError;
    }

    /** Bumped on every change, so the screen knows when to refresh. */
    public static int version() {
        return version;
    }
}
