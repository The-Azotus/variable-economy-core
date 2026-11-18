package variableeconomy.core.api;

/** What a player may do with an account. Each account has exactly one owner. */
public enum AccountRole {
    /** May deposit, spend and withdraw, and manage the account: its members, and deleting it. */
    OWNER,
    /** May deposit, spend and withdraw. */
    MEMBER,
    /** May only put money in: deposit coins, or be paid into it. */
    DEPOSIT_ONLY;

    /**
     * Whether this role may put money in.
     *
     * @return true for every role
     */
    public boolean canDeposit() {
        return true;
    }

    /**
     * Whether this role may take money out: spend it, or withdraw it as coins.
     *
     * @return true for the owner and members
     */
    public boolean canSpend() {
        return this != DEPOSIT_ONLY;
    }

    /**
     * Whether this role may change the account's members or delete it.
     *
     * @return true for the owner only
     */
    public boolean canManage() {
        return this == OWNER;
    }
}
