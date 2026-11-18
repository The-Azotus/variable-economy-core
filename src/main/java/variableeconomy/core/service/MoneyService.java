package variableeconomy.core.service;

import variableeconomy.core.CoreModule;
import com.google.gson.JsonPrimitive;
import net.minecraft.server.MinecraftServer;
import variableeconomy.core.bank.Bank;
import variableeconomy.core.bank.WorldBank;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.Money;
import variableeconomy.core.api.PaymentResult;
import variableeconomy.core.api.Reason;
import variableeconomy.core.data.AccountRegistry;
import variableeconomy.core.data.MoneySupplyStats;
import variableeconomy.core.data.OwedPayments;
import variableeconomy.core.data.AuditLog;
import variableeconomy.core.service.Leaderboard;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Core's money service: every balance change in the game goes through here, so each one is checked the same way,
 * posts its {@link BalanceEvents balance event}, goes in its account's history, counts in the money-supply stats
 * ({@code pay} as created, {@code charge} as destroyed) and, unless it is one of the mod's own routine payments, writes
 * an audit line. Money lives in the world's {@link Bank}; this class is the only one that changes balances, apart from an
 * ended account's balance passing to a player ({@link AccountsService#closeAccount}).
 * <p>
 * Server thread only. Every check happens before anything changes.
 */
public final class MoneyService implements Money {
    private final AccountsService accounts;

    MoneyService(AccountsService accounts) {
        this.accounts = accounts;
    }

    /** Where an account's money lives, or why it can't be reached. */
    private record Target(BankResult result, Optional<Bank.Account> account) {
        static Target of(Bank.Account account) {
            return new Target(BankResult.OK, Optional.of(account));
        }

        static Target fail(BankResult result) {
            return new Target(result, Optional.empty());
        }
    }

    private Target target(MinecraftServer server, AccountId id) {
        if (!accounts.exists(server, id)) {
            return Target.fail(BankResult.NO_ACCOUNT);
        }
        return WorldBank.of(server).account(id.value()).map(Target::of).orElseGet(() -> Target.fail(BankResult.BANK_UNAVAILABLE));
    }

    @Override
    public OptionalLong balance(MinecraftServer server, AccountId account) {
        CoreServices.requireServerThread(server);
        Objects.requireNonNull(account, "account");
        Target target = target(server, account);
        return target.account().isPresent() ? OptionalLong.of(target.account().get().balance()) : OptionalLong.empty();
    }

    @Override
    public PaymentResult pay(MinecraftServer server, AccountId account, long amount, Reason reason) {
        CoreServices.requireServerThread(server);
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(reason, "reason");
        Optional<BankResult> invalid = checkAmount(amount);
        if (invalid.isPresent()) {
            return new PaymentResult(invalid.get(), amount, false);
        }
        Target target = target(server, account);
        if (target.result() == BankResult.BANK_UNAVAILABLE) {
            OwedPayments.get(server).owe(new OwedPayments.Payment(account, amount, reason));
            MoneySupplyStats.get(server).created(reason.id(), amount);
            CoreModule.LOGGER.warn("A payment of {} to {} ({}) is owed: the bank can't be reached", amount, account, reason.id());
            audit(server, "pay", reason, describe(server, account), "owed: bank unreachable", amount);
            return new PaymentResult(BankResult.OK, amount, true);
        }
        if (target.account().isEmpty()) {
            return new PaymentResult(target.result(), amount, false);
        }
        Bank.Account into = target.account().get();
        long before = into.balance();
        if (before > Long.MAX_VALUE - amount) {
            return new PaymentResult(BankResult.TOO_LARGE, amount, false);
        }
        into.deposit(amount);
        MoneySupplyStats.get(server).created(reason.id(), amount);
        BalanceEvents.changed(server, account, before, into.balance(), amount, reason);
        audit(server, "pay", reason, describe(server, account), "ok", amount);
        return new PaymentResult(BankResult.OK, amount, false);
    }

    @Override
    public PaymentResult charge(MinecraftServer server, AccountId account, long amount, Reason reason) {
        CoreServices.requireServerThread(server);
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(reason, "reason");
        Optional<BankResult> invalid = checkAmount(amount);
        if (invalid.isPresent()) {
            return new PaymentResult(invalid.get(), amount, false);
        }
        Target target = target(server, account);
        if (target.account().isEmpty()) {
            return new PaymentResult(target.result(), amount, false);
        }
        Bank.Account from = target.account().get();
        long before = from.balance();
        // The deduction is itself the funds check: it changes nothing when it returns false.
        if (!from.deduct(amount)) {
            return new PaymentResult(BankResult.NO_FUNDS, amount, false);
        }
        MoneySupplyStats.get(server).destroyed(reason.id(), amount);
        BalanceEvents.changed(server, account, before, from.balance(), -amount, reason);
        audit(server, "charge", reason, describe(server, account), "ok", amount);
        return new PaymentResult(BankResult.OK, amount, false);
    }

    @Override
    public PaymentResult transfer(MinecraftServer server, AccountId from, AccountId to, long amount, Reason reason) {
        CoreServices.requireServerThread(server);
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(reason, "reason");
        Optional<BankResult> invalid = checkAmount(amount);
        if (invalid.isPresent()) {
            return new PaymentResult(invalid.get(), amount, false);
        }
        Target source = target(server, from);
        Target dest = target(server, to);
        if (source.result() == BankResult.NO_ACCOUNT || dest.result() == BankResult.NO_ACCOUNT) {
            return new PaymentResult(BankResult.NO_ACCOUNT, amount, false);
        }
        if (from.equals(to)) {
            return new PaymentResult(BankResult.NOT_ALLOWED, amount, false);
        }
        if (source.account().isEmpty() || dest.account().isEmpty()) {
            return new PaymentResult(BankResult.BANK_UNAVAILABLE, amount, false);
        }
        Bank.Account out = source.account().get();
        Bank.Account in = dest.account().get();
        long outBefore = out.balance();
        long inBefore = in.balance();
        if (inBefore > Long.MAX_VALUE - amount) {
            return new PaymentResult(BankResult.TOO_LARGE, amount, false);
        }
        if (!out.deduct(amount)) {
            return new PaymentResult(BankResult.NO_FUNDS, amount, false);
        }
        in.deposit(amount);
        BalanceEvents.changed(server, from, outBefore, out.balance(), -amount, reason);
        BalanceEvents.changed(server, to, inBefore, in.balance(), amount, reason);
        audit(server, "transfer", reason, describe(server, from) + " to " + describe(server, to), "ok", amount);
        return new PaymentResult(BankResult.OK, amount, false);
    }

    /**
     * Moves {@code amount} from one account to another and takes {@code fee} more from the first, destroying it: one
     * all-or-nothing step, so the payment never goes through without its fee or the other way round. Like
     * {@link #transfer}, it doesn't check who may spend from {@code from}. A fee of 0 takes nothing.
     *
     * @return as {@link #transfer}; {@link BankResult#NO_FUNDS} if {@code from} can't cover the amount and the fee
     */
    public PaymentResult transferWithFee(MinecraftServer server, AccountId from, AccountId to, long amount, long fee,
                                         Reason reason, Reason feeReason) {
        CoreServices.requireServerThread(server);
        if (amount <= 0 || fee < 0) {
            return new PaymentResult(BankResult.INVALID_AMOUNT, amount, false);
        }
        if (fee > Long.MAX_VALUE - amount) {
            return new PaymentResult(BankResult.TOO_LARGE, amount, false);
        }
        Target source = target(server, from);
        Target dest = target(server, to);
        if (source.result() == BankResult.NO_ACCOUNT || dest.result() == BankResult.NO_ACCOUNT) {
            return new PaymentResult(BankResult.NO_ACCOUNT, amount, false);
        }
        if (from.equals(to)) {
            return new PaymentResult(BankResult.NOT_ALLOWED, amount, false);
        }
        if (source.account().isEmpty() || dest.account().isEmpty()) {
            return new PaymentResult(BankResult.BANK_UNAVAILABLE, amount, false);
        }
        Bank.Account out = source.account().get();
        Bank.Account in = dest.account().get();
        long outBefore = out.balance();
        long inBefore = in.balance();
        if (inBefore > Long.MAX_VALUE - amount) {
            return new PaymentResult(BankResult.TOO_LARGE, amount, false);
        }
        // The deduction is itself the funds check: it changes nothing when it returns false.
        if (!out.deduct(amount + fee)) {
            return new PaymentResult(BankResult.NO_FUNDS, amount, false);
        }
        in.deposit(amount);
        BalanceEvents.changed(server, from, outBefore, outBefore - amount, -amount, reason);
        if (fee > 0) {
            MoneySupplyStats.get(server).destroyed(feeReason.id(), fee);
            BalanceEvents.changed(server, from, outBefore - amount, out.balance(), -fee, feeReason);
        }
        BalanceEvents.changed(server, to, inBefore, in.balance(), amount, reason);
        audit(server, "transfer", reason, describe(server, from) + " to " + describe(server, to), "ok", amount);
        if (fee > 0) {
            audit(server, "charge", feeReason, describe(server, from), "ok", fee);
        }
        return new PaymentResult(BankResult.OK, amount, false);
    }

    @Override
    public boolean available(MinecraftServer server) {
        CoreServices.requireServerThread(server);
        return true;
    }

    // ---- Owed payments ---------------------------------------------------------------------------------------

    /**
     * Pays whatever is owed that the bank can take now, unless a payment failed less than
     * {@link OwedPayments#RETRY_TICKS} ago. Money owed to an account that has ended goes to the personal account of
     * whoever owned it when it ended. Every tick.
     */
    public void payOwed(MinecraftServer server) {
        OwedPayments owed = OwedPayments.get(server);
        if (owed.isEmpty() || !owed.retryDue(server.getTickCount())) {
            return;
        }
        boolean failed = false;
        for (OwedPayments.Payment payment : owed.list()) {
            AccountId to = payee(server, payment.account());
            Target target = target(server, to);
            if (target.account().isEmpty()) {
                if (target.result() == BankResult.NO_ACCOUNT) {
                    CoreModule.LOGGER.error("Money owed to {} has no account to go to; it stays owed", payment.account());
                }
                failed = true;
                continue;
            }
            Bank.Account into = target.account().get();
            long before = into.balance();
            if (before > Long.MAX_VALUE - payment.amount()) {
                failed = true;
                continue;
            }
            into.deposit(payment.amount());
            owed.paid(payment);
            BalanceEvents.changed(server, to, before, into.balance(), payment.amount(), payment.reason());
        }
        if (failed) {
            owed.failedAt(server.getTickCount());
        }
    }

    /** Where money owed to {@code account} goes now: the account, or its last owner's personal account once it ended. */
    public AccountId payee(MinecraftServer server, AccountId account) {
        Optional<AccountRegistry.Entry> entry = AccountRegistry.get(server).entry(account.value());
        if (entry.isPresent() && entry.get().ended()) {
            return accounts.personal(entry.get().owner());
        }
        return account;
    }

    /**
     * What is owed to each player's personal account, for the leaderboard. Money owed to other accounts isn't
     * included.
     */
    public Map<UUID, Long> owedToPlayers(MinecraftServer server) {
        Map<UUID, Long> held = new HashMap<>();
        for (OwedPayments.Payment payment : OwedPayments.get(server).list()) {
            AccountId to = payee(server, payment.account());
            if (accounts.isPersonal(server, to)) {
                held.merge(to.value(), payment.amount(), Long::sum);
            }
        }
        return held;
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------

    private static Optional<BankResult> checkAmount(long amount) {
        return amount <= 0 ? Optional.of(BankResult.INVALID_AMOUNT) : Optional.empty();
    }

    /** Whether {@code reason} is one of the mod's own, whose routine payments the audit log leaves out. */
    public static boolean ownReason(Reason reason) {
        String namespace = reason.id().getNamespace();
        return namespace.equals(CoreModule.MOD_ID) || namespace.startsWith(CoreModule.MOD_ID + "_");
    }

    private static void audit(MinecraftServer server, String action, Reason reason, String target, String result, long amount) {
        if (ownReason(reason)) {
            return;
        }
        UUID actor = reason.actor().orElse(null);
        String who = reason.actor().flatMap(id -> Leaderboard.profile(server, id)).map(p -> p.getName() + " for " + reason.id())
                .orElse("api:" + reason.id());
        AuditLog.record(server, who, actor, "api " + action, target, result, null, new JsonPrimitive(amount));
    }

    /** The audit log's name for an account: its player by name (when known) and UUID, or its kind, name and ID. */
    private String describe(MinecraftServer server, AccountId account) {
        if (accounts.isPersonal(server, account)) {
            UUID player = account.value();
            return Leaderboard.profile(server, player).map(profile -> profile.getName() + " (" + player + ")").orElse(player.toString());
        }
        return accounts.get(server, account)
                .map(info -> info.kind().name().toLowerCase(java.util.Locale.ROOT) + " account " + info.name() + " (" + account.value() + ")")
                .orElse("account " + account.value());
    }
}
