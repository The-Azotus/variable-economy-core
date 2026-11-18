package variableeconomy.core.api;

import java.util.Objects;
import java.util.Optional;

/**
 * How creating an account ended.
 *
 * @param result  what happened; only {@link BankResult#OK} created an account
 * @param account the new account on {@link BankResult#OK}, otherwise empty
 */
public record AccountResult(BankResult result, Optional<AccountId> account) {
    /**
     * Checks both are there.
     *
     * @param result  what happened
     * @param account the new account on {@link BankResult#OK}, otherwise empty
     */
    public AccountResult {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(account, "account");
    }

    /**
     * Whether the account was created.
     *
     * @return true for {@link BankResult#OK}
     */
    public boolean ok() {
        return result == BankResult.OK;
    }
}
