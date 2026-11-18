package variableeconomy.core.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/**
 * Which items each player has unlocked. Get it from {@link CoreApi#itemGate()}. Every module respects it: no module
 * lets a player trade, claim or post an item that is locked for them. What "locked" looks like is each module's
 * choice: the market hides the item, the contract board shows it greyed out, the exchange refuses it. Prices and
 * stock never depend on it.
 * <p>
 * An item is locked for a player when any rule locks it. ProgressiveStages' item locks (the player lacks a stage the
 * item needs) are one rule, applied when that mod is installed; add-ons add their own with {@link #addRule}.
 */
public interface ItemGate {
    /**
     * Whether {@code item} is locked for {@code player}. Server thread only.
     *
     * @param player the player
     * @param item   the item
     * @return true if any rule locks it
     */
    boolean isLocked(ServerPlayer player, Item item);

    /**
     * Whether anything can be locked for anyone: ProgressiveStages is installed or a rule is registered. When false,
     * every player has every item unlocked, so a module may send all players the same list. Any thread.
     *
     * @return whether any rule exists
     */
    boolean canLockAnything();

    /**
     * Whether {@code player} has a ProgressiveStages stage, for features such as a contract that needs one. True when
     * ProgressiveStages isn't installed, or its checks have failed and been turned off (logged), since nothing is
     * locked then either. Server thread only.
     *
     * @param player the player
     * @param stage  the stage, as ProgressiveStages names it
     * @return whether they have it
     */
    boolean hasStage(ServerPlayer player, String stage);

    /**
     * Adds a rule, replacing any rule with the same ID. When a server is running, an {@link
     * variableeconomy.core.api.event.ItemGateChangedEvent} for everyone follows at the end of the current or next
     * tick. Any thread, also while mods load.
     *
     * @param id   the rule's ID, in the add-on's namespace
     * @param rule the rule
     */
    void addRule(ResourceLocation id, GateRule rule);

    /**
     * Removes the rule with this ID. When a server is running and there was such a rule, an {@link
     * variableeconomy.core.api.event.ItemGateChangedEvent} for everyone follows at the end of the current or next
     * tick. Any thread.
     *
     * @param id the rule's ID
     * @return whether there was such a rule
     */
    boolean removeRule(ResourceLocation id);

    /**
     * Call when a rule's answers changed for {@code player}. An {@link
     * variableeconomy.core.api.event.ItemGateChangedEvent} for them follows at the end of the current or next tick,
     * once however many calls are made, so each module refreshes what it shows them. Server thread only.
     *
     * @param player the player whose locks may have changed
     */
    void refresh(ServerPlayer player);

    /**
     * Call when a rule's answers may have changed for anyone, as {@link #refresh(ServerPlayer)} for every player.
     * Server thread only.
     *
     * @param server the server
     */
    void refreshAll(MinecraftServer server);
}
