package variableeconomy.core.api;

import net.minecraft.server.MinecraftServer;

import java.util.OptionalLong;

/**
 * Balances, payments, charges and transfers. Get it from {@link CoreApi#money()}. All money moves this way, Variable
 * Economy's own trades and payouts included.
 * <p>
 * Amounts are in bits, at least 1. A call that moves money writes one audit line, named by its {@link Reason}, and the
 * account's {@link variableeconomy.core.api.event.BalanceChangedEvent} follows at the end of the current or next tick.
 * Variable Economy's own routine payments (trades, contract and exchange payments, coin deposits), whose reasons are
 * in its own namespaces, write no audit line, so they don't push admin actions out of the size-capped log.
 * <p>
 * Amounts and balances are {@code long}; a call answers {@link BankResult#TOO_LARGE} only when a balance couldn't
 * hold the result.
 * <p>
 * These calls act with the server's authority: they don't check who may spend from an account. When acting for a
 * player, check their role first ({@link Accounts#role}, {@link AccountRole#canSpend()}), or use
 * {@link Accounts#active}, which is always an account the player may spend from.
 * <p>
 * <b>The money rule.</b> {@link #pay} creates money and {@link #charge} destroys it, so buying items and handing them
 * in for money must never make a profit. An add-on that gives money for items does it in one of two ways:
 * <ul>
 *   <li>it sells them for the player through the market add-on's {@code MarketTrading}, so the market's price impact
 *       and fees apply; or</li>
 *   <li>it takes them and calls {@code pay}, and when the market is installed, pays less than the market add-on's
 *       {@code MarketPrices.lowestBuyCost} for them whenever that has a value, so buying them from the market to hand
 *       in can't profit.</li>
 * </ul>
 * Without the market there are no market prices to profit from, and what an add-on pays for items is its own choice.
 * Rewards that cost the player no items need no check.
 * <p>
 * Server thread only.
 */
public interface Money {
    /**
     * An account's balance.
     *
     * @param server  the server
     * @param account the account
     * @return the balance in bits, or empty if there is no such account or the bank can't be reached
     */
    OptionalLong balance(MinecraftServer server, AccountId account);

    /**
     * Pays {@code amount} new bits into an account. If the bank can't be reached, the money is owed instead: it is paid
     * as soon as the bank is back, survives restarts, and counts on the leaderboard meanwhile. If the account ends
     * before then, the personal account of whoever owned it when it ended is paid. This creates money: see the money
     * rule above.
     * <p>
     * An account that has ended answers {@link BankResult#NO_ACCOUNT}, and nothing is paid; an add-on paying money back
     * to an account that may have ended (a refund, a sale's takings) then pays the player's personal account instead.
     *
     * @param server  the server
     * @param account the account to pay
     * @param amount  the amount, in bits
     * @param reason  why
     * @return {@link BankResult#OK} when paid or owed ({@link PaymentResult#owed()} says which);
     *         {@link BankResult#INVALID_AMOUNT} for zero or less; {@link BankResult#TOO_LARGE} if the account's balance
     *         couldn't hold it; {@link BankResult#NO_ACCOUNT} if there is no such
     *         account, or it has ended
     */
    PaymentResult pay(MinecraftServer server, AccountId account, long amount, Reason reason);

    /**
     * Takes {@code amount} bits from an account, all or nothing, and destroys them.
     *
     * @param server  the server
     * @param account the account to charge
     * @param amount  the amount, in bits
     * @param reason  why
     * @return {@link BankResult#OK}; {@link BankResult#INVALID_AMOUNT}; {@link BankResult#TOO_LARGE};
     *         {@link BankResult#NO_ACCOUNT}; {@link BankResult#BANK_UNAVAILABLE}; {@link BankResult#NO_FUNDS} if the
     *         account can't cover it
     */
    PaymentResult charge(MinecraftServer server, AccountId account, long amount, Reason reason);

    /**
     * Moves {@code amount} bits from one account to another, all or nothing. No money is created or destroyed. Both
     * accounts get a {@link variableeconomy.core.api.event.BalanceChangedEvent}, and the audit line names both.
     *
     * @param server the server
     * @param from   the account to take from
     * @param to     the account to pay into
     * @param amount the amount, in bits
     * @param reason why
     * @return {@link BankResult#OK}; {@link BankResult#INVALID_AMOUNT}; {@link BankResult#TOO_LARGE};
     *         {@link BankResult#NO_ACCOUNT} if either account doesn't exist; {@link BankResult#NOT_ALLOWED} if they are
     *         the same account; {@link BankResult#BANK_UNAVAILABLE}; {@link BankResult#NO_FUNDS} if {@code from} can't
     *         cover it. Never owed.
     */
    PaymentResult transfer(MinecraftServer server, AccountId from, AccountId to, long amount, Reason reason);

    /**
     * Whether the bank can be reached. When it can't, charges and transfers are refused and payments are owed. The
     * mod's own bank always can, so this is true today.
     *
     * @param server the server
     * @return whether the bank can be reached
     */
    boolean available(MinecraftServer server);
}
