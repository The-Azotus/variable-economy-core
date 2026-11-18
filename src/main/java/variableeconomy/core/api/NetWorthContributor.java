package variableeconomy.core.api;

import net.minecraft.server.MinecraftServer;

import java.util.Map;

/**
 * Money an add-on holds that belongs to accounts but isn't in them: escrow for open orders, contract deposits,
 * payments owed. Registered with {@link NetWorth#register}.
 * <p>
 * Count only money outside every account. Money still in an account is already counted, so counting it here too
 * would count it twice. Key each amount by the account it will be paid into: when that account has ended, the account
 * that will now get it (usually a player's personal account), or it isn't counted.
 */
@FunctionalInterface
public interface NetWorthContributor {
    /**
     * What this add-on holds now, for each account it belongs to. Called on the server thread each time the
     * leaderboard is ranked, so it must be cheap and change nothing. Accounts it holds nothing for may be left out;
     * amounts of zero or less are ignored, as are accounts that no longer exist. A contributor that throws is logged
     * and counts as holding nothing.
     *
     * @param server the server
     * @return bits held, by account
     */
    Map<AccountId, Long> held(MinecraftServer server);
}
