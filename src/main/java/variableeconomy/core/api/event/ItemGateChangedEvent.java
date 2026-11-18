package variableeconomy.core.api.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Which items are locked may have changed, for one player or for everyone: their ProgressiveStages stages changed, or
 * a rule was added, removed or refreshed. Each module listens for it and sends the player (or everyone) what they may
 * now see. Posted at the end of the current or next tick, at most once per player per tick, however many changes there
 * were.
 * <p>
 * Posted by Variable Economy; add-ons only listen for it.
 */
public final class ItemGateChangedEvent extends Event {
    private final MinecraftServer server;
    @Nullable
    private final ServerPlayer player;

    /**
     * Posted by Variable Economy.
     *
     * @param server the server
     * @param player the player whose locks may have changed, or null for everyone
     */
    public ItemGateChangedEvent(MinecraftServer server, @Nullable ServerPlayer player) {
        this.server = server;
        this.player = player;
    }

    /**
     * The server.
     *
     * @return the server
     */
    public MinecraftServer getServer() {
        return server;
    }

    /**
     * The player whose locks may have changed.
     *
     * @return the player, or empty when it may be anyone
     */
    public Optional<ServerPlayer> getPlayer() {
        return Optional.ofNullable(player);
    }
}
