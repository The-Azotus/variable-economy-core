package variableeconomy.core.api;

/** What sort of account it is, which decides what can be done with it. */
public enum AccountKind {
    /**
     * A player's own account. Every player has exactly one: it exists from the first time the server sees the player, can't be deleted and has no
     * other members, and it is where their money goes unless they pick another active account.
     */
    PERSONAL,
    /**
     * An extra account a player opened, with a name of its own, unique among named accounts. It may be shared with
     * other players when the server allows shared accounts. It can be deleted once it is empty.
     */
    NAMED,
    /**
     * An FTB Teams party's account. Its members are the party's and follow it, so they can't be changed here. It ends
     * when the party does, and its balance then goes to the party's owner.
     */
    PARTY
}
