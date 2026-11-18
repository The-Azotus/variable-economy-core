package variableeconomy.core.api;

import java.util.Objects;

/**
 * How a payment, charge or transfer ended.
 *
 * @param result what happened; only {@link BankResult#OK} moved money (or, for a payment, owed it)
 * @param amount the amount asked for, in bits
 * @param owed   true when a payment couldn't reach the bank and is owed instead: it is paid as soon as the bank is
 *               back, survives restarts, and counts on the leaderboard meanwhile. Always false for a charge or a
 *               transfer.
 */
public record PaymentResult(BankResult result, long amount, boolean owed) {
    /**
     * Checks the result is there.
     *
     * @param result what happened
     * @param amount the amount asked for, in bits
     * @param owed   whether a payment is owed rather than paid
     */
    public PaymentResult {
        Objects.requireNonNull(result, "result");
    }

    /**
     * Whether the money moved, or for a payment, is owed.
     *
     * @return true for {@link BankResult#OK}
     */
    public boolean ok() {
        return result == BankResult.OK;
    }
}
