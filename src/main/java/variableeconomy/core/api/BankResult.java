package variableeconomy.core.api;

/**
 * How a money or account call ended. Only {@link #OK} changed anything; every other result left every account exactly
 * as it was.
 */
public enum BankResult {
    /** Done. */
    OK,
    /** An amount of zero or less. */
    INVALID_AMOUNT,
    /** More than an account's balance can hold. */
    TOO_LARGE,
    /** The account can't cover the amount. */
    NO_FUNDS,
    /** There is no such account. */
    NO_ACCOUNT,
    /** The bank can't be reached. The mod's own bank always can, so this isn't returned today. */
    BANK_UNAVAILABLE,
    /**
     * Not allowed for this account or player: a player who can't spend from the account, deleting a party account or
     * changing its members, the owner leaving an account with no one to pass it to, giving the owner another role
     * without naming a new owner, or a transfer from an account to itself.
     */
    NOT_ALLOWED,
    /** The server has turned the feature off: extra accounts, or shared accounts. */
    DISABLED,
    /** The server's limit is reached: accounts a player may own, or members an account may have. */
    LIMIT_REACHED,
    /** The account holds money, or money is owed to it, so it can't be deleted. */
    NOT_EMPTY,
    /** A personal account can't be deleted or shared. */
    PERSONAL_ACCOUNT,
    /** The player isn't a member of the account. */
    NOT_A_MEMBER,
    /** The name is blank, too long, or not one word of letters, digits, '_' and '-'. */
    INVALID_NAME,
    /** Another account already has that name. */
    NAME_TAKEN
}
