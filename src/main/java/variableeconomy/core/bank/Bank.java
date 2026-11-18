package variableeconomy.core.bank;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where accounts' money lives. Everything that moves money goes through core's money service, which alone calls this
 * interface; {@link WorldBank#of} gives the world's bank. Amounts are bits. The bank only keeps balances: which
 * accounts exist and who may use them is core's account registry's business.
 * <p>
 * Server thread only: the bank isn't synchronized.
 */
public interface Bank {
    /**
     * The balance of the account {@code id} (a personal account's ID is its player's UUID), created on first use.
     * Empty if the bank can't be reached.
     */
    Optional<Account> account(UUID id);

    /** Every account's balance, by account ID. Empty balances aren't listed. */
    Map<UUID, Long> balances();

    /** The coins this bank takes in and pays out, largest first. */
    List<Denomination> coins();

    /** One account's balance. Amounts and balances are {@code long}. */
    interface Account {
        long balance();

        /** Cannot fail for a non-negative amount; the caller checks the balance can hold it. */
        void deposit(long amount);

        /** All-or-nothing: returns false and changes nothing if the account cannot cover the amount. */
        boolean deduct(long amount);
    }
}
