package variableeconomy.core.api;

import net.minecraft.resources.ResourceLocation;

/**
 * Where add-ons register the money they hold for accounts, so the leaderboard counts it. Get it from
 * {@link CoreApi#netWorth()}. The leaderboard adds each account's balance and what every contributor holds for it,
 * then ranks by account or by owner, as the server's config says. Until that setting arrives in a later 0.x release, it
 * ranks players by their personal account alone, so money held for a team's account isn't counted.
 * <p>
 * Any thread, also while mods load.
 */
public interface NetWorth {
    /**
     * Adds a contributor, replacing any with the same ID.
     *
     * @param id          the contributor's ID, in the add-on's namespace
     * @param contributor the contributor
     */
    void register(ResourceLocation id, NetWorthContributor contributor);

    /**
     * Removes the contributor with this ID.
     *
     * @param id the contributor's ID
     * @return whether there was such a contributor
     */
    boolean unregister(ResourceLocation id);
}
