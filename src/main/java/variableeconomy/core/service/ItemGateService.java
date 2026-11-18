package variableeconomy.core.service;

import variableeconomy.core.CoreModule;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import variableeconomy.core.compat.StagesBridge;
import variableeconomy.core.api.GateRule;
import variableeconomy.core.api.ItemGate;
import variableeconomy.core.api.event.ItemGateChangedEvent;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Which items each player has unlocked. An item is locked when ProgressiveStages locks it (or the GameTests' locks do)
 * or when any add-on rule says so. Prices and stock are the same for everyone; only what a player may trade differs.
 * <p>
 * Changes are collected and posted as {@link ItemGateChangedEvent}s at the end of the tick, at most one per player (or
 * one for everyone), however many changes there were.
 */
public final class ItemGateService implements ItemGate {
    /** Set by GameTests to lock items without ProgressiveStages; null otherwise. */
    @Nullable
    private static volatile BiPredicate<ServerPlayer, Item> testLocks;
    @Nullable
    private static volatile BiPredicate<ServerPlayer, String> testStages;
    private final Map<ResourceLocation, GateRule> rules = new ConcurrentHashMap<>();
    /** Players whose locks may have changed since the last flush; rules may change from any thread. */
    private final Map<UUID, ServerPlayer> stale = new ConcurrentHashMap<>();
    private volatile boolean allStale;

    @Override
    public boolean isLocked(ServerPlayer player, Item item) {
        CoreServices.requireServerThread(player.server);
        Objects.requireNonNull(item, "item");
        BiPredicate<ServerPlayer, Item> locks = testLocks;
        if (locks != null ? locks.test(player, item) : StagesBridge.isLocked(player, item)) {
            return true;
        }
        if (rules.isEmpty()) {
            return false;
        }
        for (Map.Entry<ResourceLocation, GateRule> rule : rules.entrySet()) {
            try {
                if (rule.getValue().locks(player, item)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError e) {
                // Removed only if it's still the rule that threw, so the error is logged once per rule.
                if (rules.remove(rule.getKey(), rule.getValue())) {
                    CoreModule.LOGGER.error("Item gate rule {} threw an exception and was removed; the items it locked "
                            + "are unlocked again", rule.getKey(), e);
                    allStale = true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean canLockAnything() {
        return testLocks != null || StagesBridge.isAvailable() || !rules.isEmpty();
    }

    @Override
    public boolean hasStage(ServerPlayer player, String stage) {
        CoreServices.requireServerThread(player.server);
        Objects.requireNonNull(stage, "stage");
        return testStages != null ? testStages.test(player, stage) : StagesBridge.hasStage(player, stage);
    }

    @Override
    public void addRule(ResourceLocation id, GateRule rule) {
        rules.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(rule, "rule"));
        changedForAnyone();
    }

    @Override
    public boolean removeRule(ResourceLocation id) {
        boolean removed = rules.remove(Objects.requireNonNull(id, "id")) != null;
        if (removed) {
            changedForAnyone();
        }
        return removed;
    }

    @Override
    public void refresh(ServerPlayer player) {
        CoreServices.requireServerThread(player.server);
        stale.put(player.getUUID(), player);
    }

    @Override
    public void refreshAll(MinecraftServer server) {
        CoreServices.requireServerThread(server);
        allStale = true;
    }

    /** From ProgressiveStages: a player's stages changed, or anyone's ({@code null}). */
    public void stagesChanged(@Nullable ServerPlayer player) {
        if (player == null) {
            allStale = true;
        } else {
            stale.put(player.getUUID(), player);
        }
    }

    private void changedForAnyone() {
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            allStale = true;
        }
    }

    /** Posts what changed since the last flush: one event for everyone, or one per player still online. End of tick. */
    public void flush(MinecraftServer server) {
        if (allStale) {
            allStale = false;
            stale.clear();
            CoreEvents.post(new ItemGateChangedEvent(server, null));
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        List<ServerPlayer> due = List.copyOf(stale.values());
        due.forEach(player -> stale.remove(player.getUUID(), player));
        for (ServerPlayer player : due) {
            if (!player.hasDisconnected()) {
                CoreEvents.post(new ItemGateChangedEvent(server, player));
            }
        }
    }

    /** Forgets pending changes when the server stops. */
    public void clear() {
        allStale = false;
        stale.clear();
    }

    /** GameTests only: answer {@link #hasStage} by this rule instead of ProgressiveStages. Null goes back to normal. */
    public static void setTestStages(@Nullable BiPredicate<ServerPlayer, String> stages) {
        testStages = stages;
    }

    /** GameTests only: lock items by this rule instead of ProgressiveStages' locks. Null goes back to normal. */
    public static void setTestLocks(@Nullable BiPredicate<ServerPlayer, Item> locks) {
        testLocks = locks;
    }
}
