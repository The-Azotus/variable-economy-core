/**
 * Variable Economy's core API: accounts, money, item gating and the leaderboard's net-worth contributors. Every
 * add-on, Variable Economy's own included, uses only this package and {@code variableeconomy.core.api.event} to reach
 * core. Start at {@link variableeconomy.core.api.CoreApi}.
 * <p>
 * <b>Money.</b> Amounts are in bits, as {@code long}. Money lives in accounts: every player has a personal account,
 * and may own or share others. All money moves through {@link variableeconomy.core.api.Money}, and each movement
 * names a {@link variableeconomy.core.api.Reason}. Item prices aren't here: they belong to the market add-on's API.
 * <p>
 * <b>Rules.</b> Every call checks everything before it changes anything, so a result other than {@code OK} changed
 * nothing. Events are posted only after a change is complete, and are never cancellable.
 * <p>
 * <b>Threads.</b> Every method that reads or changes accounts, money or item locks must run on the server thread, and
 * throws {@link java.lang.IllegalStateException} otherwise: the bank isn't synchronized. From a packet handler, use
 * {@code IPayloadContext.enqueueWork}; from another thread, {@code server.execute}. Methods that say they may be
 * called from any thread are the exception.
 * <p>
 * <b>Stability.</b> Experimental until 1.0: it may still change in a 0.x minor release, and every change is listed in
 * the add-on guide's changelog ({@code docs/api.md}). Every package outside the {@code api} packages is internal and
 * may change in any release.
 */
@ApiStatus.Experimental
package variableeconomy.core.api;

import org.jetbrains.annotations.ApiStatus;
