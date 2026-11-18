package variableeconomy.core.service;

import variableeconomy.core.CoreSettings;
import com.google.gson.JsonElement;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import variableeconomy.core.coin.Coins;
import variableeconomy.core.api.Audit;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.data.AuditLog;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Core's services, one each for the whole game, installed into {@link CoreApi} when the mod is constructed. Core's own
 * code reaches the concrete classes here, for the parts add-ons don't see (owed payments, ending a team, flushing
 * events); everything else goes through {@link CoreApi}.
 */
public final class CoreServices implements CoreApi.Services {
    public static final AccountsService ACCOUNTS = new AccountsService();
    public static final MoneyService MONEY = new MoneyService(ACCOUNTS);
    public static final ItemGateService ITEM_GATE = new ItemGateService();
    public static final NetWorthService NET_WORTH = new NetWorthService();
    private static final Audit AUDIT = new Audit() {
        @Override
        public void record(CommandSourceStack source, String action, String target, String result,
                           @Nullable JsonElement before, @Nullable JsonElement after) {
            requireServerThread(source.getServer());
            AuditLog.record(source, Objects.requireNonNull(action, "action"), Objects.requireNonNull(target, "target"),
                    Objects.requireNonNull(result, "result"), before, after);
        }

        @Override
        public long countSince(MinecraftServer server, java.time.Instant since) {
            requireServerThread(server);
            Objects.requireNonNull(since, "since");
            return AuditLog.recent(AuditLog.file(server), 1000).stream().filter(entry -> {
                try {
                    return entry.has("time") && java.time.Instant.parse(entry.get("time").getAsString()).isAfter(since);
                } catch (RuntimeException e) {
                    return false;
                }
            }).count();
        }
    };

    private CoreServices() {
    }

    /** Installs the services into {@link CoreApi}. Once, while the mod is constructed. */
    public static void install() {
        CoreApi.install(new CoreServices());
    }

    @Override
    public variableeconomy.core.api.Accounts accounts() {
        return ACCOUNTS;
    }

    @Override
    public variableeconomy.core.api.Money money() {
        return MONEY;
    }

    @Override
    public variableeconomy.core.api.ItemGate itemGate() {
        return ITEM_GATE;
    }

    @Override
    public variableeconomy.core.api.NetWorth netWorth() {
        return NET_WORTH;
    }

    @Override
    public Audit audit() {
        return AUDIT;
    }

    @Override
    public boolean isCoin(Item item) {
        return Coins.isCoin(item);
    }

    @Override
    public boolean isPlain(ItemStack stack, Item item) {
        return plain(stack, item, CoreSettings.ignoredComponents());
    }

    /** Whether {@code stack} is {@code item} with no changed components other than {@code ignored} ones. */
    public static boolean plain(ItemStack stack, Item item, Set<ResourceLocation> ignored) {
        if (!stack.is(item)) {
            return false;
        }
        for (Map.Entry<DataComponentType<?>, Optional<?>> changed : stack.getComponentsPatch().entrySet()) {
            if (!ignored.contains(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(changed.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Every call that reads or changes money, accounts or locks runs on the server thread. */
    public static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Variable Economy's core must be called on the server thread");
        }
    }
}
