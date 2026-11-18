package variableeconomy.core.bank;

import net.minecraft.server.MinecraftServer;

/**
 * The world's bank: always the mod's own ({@link OwnBank}). Everything that moves money gets it here, typed as
 * {@link Bank}, so nothing reaches past that interface. Server thread only.
 */
public final class WorldBank {
    private WorldBank() {
    }

    /** The world's bank. */
    public static Bank of(MinecraftServer server) {
        return OwnBank.get(server);
    }
}
