/**
 * Events Variable Economy's core posts on {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS}: balances changing,
 * accounts and their members changing, and item locks changing.
 * <ul>
 *   <li>Every event is posted <em>after</em> its change is complete. A balance change is posted at the end of the
 *       current or next tick, so an action with several steps (a market purchase takes the money, then gives the items) has finished
 *       before anyone hears of it.</li>
 *   <li>No event is cancellable. To stop something before it happens, lock the item with an
 *       {@link variableeconomy.core.api.ItemGate} rule, or decide in your own code whether to call the API.</li>
 *   <li>Events are posted only for changes that happened, and always on the server thread.</li>
 *   <li>A listener that throws is logged and ignored, so it can't undo or interrupt a change. Listeners after it on
 *       the same event may then not run.</li>
 * </ul>
 * Listen as with any NeoForge event, for example
 * {@code NeoForge.EVENT_BUS.addListener((BalanceChangedEvent event) -> ...)}.
 */
@ApiStatus.Experimental
package variableeconomy.core.api.event;

import org.jetbrains.annotations.ApiStatus;
