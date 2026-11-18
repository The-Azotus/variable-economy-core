package variableeconomy.core.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.TreeMap;

/**
 * How much money each source has created and destroyed, saved with the world: every {@code pay} counts as created
 * and every {@code charge} as destroyed, under its reason; transfers move money and count as neither. Counting starts
 * when a world first runs this version, or at the last reset.
 * <p>
 * Server thread only.
 */
public final class MoneySupplyStats extends SavedData {
    public static final String ID = "variable_economy_money_supply";
    private static final SavedData.Factory<MoneySupplyStats> FACTORY =
            new SavedData.Factory<>(MoneySupplyStats::new, MoneySupplyStats::fromTag, null);

    /** One source's totals: bits created and destroyed, and how many payments and charges made them. */
    public record Totals(long created, long destroyed, long count) {
        Totals add(long createdMore, long destroyedMore) {
            return new Totals(plus(created, createdMore), plus(destroyed, destroyedMore), plus(count, 1));
        }

        /** Created less destroyed: what this source added to the economy. */
        public long net() {
            return created - destroyed;
        }
    }

    private final Map<ResourceLocation, Totals> sources = new TreeMap<>();
    private long since = System.currentTimeMillis();

    public static MoneySupplyStats get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** {@code amount} bits created for {@code reason}. */
    public void created(ResourceLocation reason, long amount) {
        sources.merge(reason, new Totals(amount, 0, 1), (old, added) -> old.add(amount, 0));
        setDirty();
    }

    /** {@code amount} bits destroyed for {@code reason}. */
    public void destroyed(ResourceLocation reason, long amount) {
        sources.merge(reason, new Totals(0, amount, 1), (old, added) -> old.add(0, amount));
        setDirty();
    }

    /** Every source's totals, by reason. */
    public Map<ResourceLocation, Totals> sources() {
        return Map.copyOf(sources);
    }

    /** When counting started, in milliseconds since the epoch. */
    public long since() {
        return since;
    }

    /** Forgets every total and starts counting again now. */
    public void reset() {
        sources.clear();
        since = System.currentTimeMillis();
        setDirty();
    }

    /** Adds without wrapping: totals stop at {@link Long#MAX_VALUE}. */
    private static long plus(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    public static MoneySupplyStats fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        MoneySupplyStats stats = new MoneySupplyStats();
        if (tag.contains("since")) {
            stats.since = tag.getLong("since");
        }
        for (Tag element : tag.getList("sources", Tag.TAG_COMPOUND)) {
            CompoundTag source = (CompoundTag) element;
            ResourceLocation reason = ResourceLocation.tryParse(source.getString("reason"));
            if (reason != null) {
                stats.sources.put(reason, new Totals(source.getLong("created"), source.getLong("destroyed"), source.getLong("count")));
            }
        }
        return stats;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("since", since);
        ListTag list = new ListTag();
        sources.forEach((reason, totals) -> {
            CompoundTag source = new CompoundTag();
            source.putString("reason", reason.toString());
            source.putLong("created", totals.created());
            source.putLong("destroyed", totals.destroyed());
            source.putLong("count", totals.count());
            list.add(source);
        });
        tag.put("sources", list);
        return tag;
    }
}
