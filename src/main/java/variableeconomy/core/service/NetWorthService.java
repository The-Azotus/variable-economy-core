package variableeconomy.core.service;

import variableeconomy.core.CoreModule;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.NetWorth;
import variableeconomy.core.api.NetWorthContributor;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** The net-worth contributors add-ons register, and what they hold in all. */
public final class NetWorthService implements NetWorth {
    private final Map<ResourceLocation, NetWorthContributor> contributors = new ConcurrentHashMap<>();

    @Override
    public void register(ResourceLocation id, NetWorthContributor contributor) {
        contributors.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(contributor, "contributor"));
    }

    @Override
    public boolean unregister(ResourceLocation id) {
        return contributors.remove(Objects.requireNonNull(id, "id")) != null;
    }

    /**
     * What every contributor holds now, added up by account. Amounts of zero or less are left out, and a contributor
     * that throws is logged and counts as holding nothing. Server thread only.
     */
    public Map<AccountId, Long> held(MinecraftServer server) {
        Map<AccountId, Long> total = new HashMap<>();
        for (Map.Entry<ResourceLocation, NetWorthContributor> contributor : contributors.entrySet()) {
            Map<AccountId, Long> held;
            try {
                held = contributor.getValue().held(server);
            } catch (RuntimeException | LinkageError e) {
                CoreModule.LOGGER.error("Net-worth contributor {} threw; it counts as holding nothing", contributor.getKey(), e);
                continue;
            }
            if (held == null) {
                continue;
            }
            held.forEach((account, amount) -> {
                if (account != null && amount != null && amount > 0) {
                    total.merge(account, amount, Math::addExact);
                }
            });
        }
        return total;
    }
}
