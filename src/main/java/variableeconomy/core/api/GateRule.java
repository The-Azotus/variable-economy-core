package variableeconomy.core.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * One reason items can be locked for players, registered with {@link ItemGate#addRule}.
 * <p>
 * It runs on the server thread, very often (for every listed item each time a module sends a player its list, and on
 * every trade), so it must be cheap and have no side effects. A rule that throws is removed and logged, and the items
 * it locked are unlocked again. When its answers change, call {@link ItemGate#refresh} or {@link ItemGate#refreshAll}.
 */
@FunctionalInterface
public interface GateRule {
    /**
     * Whether this rule locks {@code item} for {@code player}.
     *
     * @param player the player
     * @param item   the item
     * @return true if the player must not trade, claim or post it
     */
    boolean locks(ServerPlayer player, Item item);
}
