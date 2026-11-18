package variableeconomy.core.compat;

import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.event.KubeEvent;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.PaymentResult;
import variableeconomy.core.api.Reason;
import variableeconomy.core.api.event.BalanceChangedEvent;

import java.util.List;
import java.util.UUID;

/**
 * The only class allowed to import KubeJS, an optional dependency. Without it nothing here touches its classes: they
 * are only referenced from {@link Present}, which loads only when KubeJS does ({@code kubejs.plugins.txt} names it as
 * a KubeJS plugin, and {@link #balanceChanged} reaches it only when KubeJS is loaded).
 * <p>
 * Server scripts get a binding, {@code VariableEconomy} ({@link Bindings}): read a balance, pay, charge, format an
 * amount. They also get an event, {@code VariableEconomyEvents.balanceChanged}, fired at the end of each tick for every
 * account whose balance changed, from core's {@link BalanceChangedEvent}. Everything goes through the core API, so
 * every money rule holds, and scripts' payments are audited like any other mod's. Checked against 2101.7.2-build.381.
 */
public final class KubeJsBridge {
    public static final String MOD_ID = "kubejs";
    /** The reason a script's payment or charge has when it names none. */
    public static final ResourceLocation SCRIPT = ResourceLocation.fromNamespaceAndPath(MOD_ID, "script");
    private static final boolean LOADED = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
    private static boolean failed;

    private KubeJsBridge() {
    }

    /** Whether KubeJS is installed, so scripts get the binding and the event. */
    public static boolean isLoaded() {
        return LOADED;
    }

    /** Hands a balance change to server scripts listening for it, when KubeJS is installed. */
    public static void balanceChanged(BalanceChangedEvent event) {
        if (!LOADED || failed) {
            return;
        }
        try {
            Present.post(event);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            CoreModule.LOGGER.error("Couldn't hand a balance change to KubeJS scripts; balanceChanged events stop until restart", e);
        }
    }

    /**
     * {@code VariableEconomy} in server scripts. Plain Java over the core API: no KubeJS classes, so scripts and tests
     * call the same thing. Amounts are bits; a player's money is their active account's. Server thread only, as every
     * money call: server scripts run there.
     */
    public static final class Bindings {
        /** The balance of the player's active account. */
        public long balance(ServerPlayer player) {
            return CoreApi.money().balance(player.server, CoreApi.accounts().active(player)).orElse(0L);
        }

        /** The balance of the player's personal account. */
        public long personalBalance(ServerPlayer player) {
            CoreApi.accounts().active(player);
            return CoreApi.money().balance(player.server, CoreApi.accounts().personal(player.getUUID())).orElse(0L);
        }

        /** Pays the player's active account {@code amount} new bits, for {@code kubejs:script}. Returns whether it was paid. */
        public boolean pay(ServerPlayer player, long amount) {
            return pay(player, amount, SCRIPT.toString());
        }

        /**
         * Pays the player's active account {@code amount} new bits. {@code reason} is an ID such as
         * {@code "mypack:quest_reward"}; it names the payment in the account's history and the audit log. This creates
         * money: a script that pays for items should mind the market's prices, as the core API's money rule says.
         */
        public boolean pay(ServerPlayer player, long amount, String reason) {
            return result(CoreApi.money().pay(player.server, CoreApi.accounts().active(player), amount, reason(player, reason)));
        }

        /** Takes {@code amount} from the player's active account, all or nothing, for {@code kubejs:script}. */
        public boolean charge(ServerPlayer player, long amount) {
            return charge(player, amount, SCRIPT.toString());
        }

        /** Takes {@code amount} from the player's active account, all or nothing. Returns false, changing nothing, if it can't. */
        public boolean charge(ServerPlayer player, long amount, String reason) {
            return result(CoreApi.money().charge(player.server, CoreApi.accounts().active(player), amount, reason(player, reason)));
        }

        /** An amount as players read it, such as "1,024 bits": a text component, translated on each player's client. */
        public Component format(long amount) {
            return CoreApi.formatMoney(amount);
        }

        private static Reason reason(ServerPlayer player, String reason) {
            return Reason.by(ResourceLocation.parse(reason), player.getUUID());
        }

        private static boolean result(PaymentResult result) {
            return result.ok();
        }
    }

    /** The KubeJS plugin: everything that touches KubeJS classes. Loaded only when KubeJS is installed. */
    public static final class Present implements KubeJSPlugin {
        static final EventGroup GROUP = EventGroup.of("VariableEconomyEvents");
        static final EventHandler BALANCE_CHANGED = GROUP.server("balanceChanged", () -> BalanceChanged.class);

        @Override
        public void registerEvents(EventGroupRegistry registry) {
            registry.register(GROUP);
        }

        @Override
        public void registerBindings(BindingRegistry bindings) {
            if (bindings.type().isServer()) {
                bindings.add("VariableEconomy", new Bindings());
            }
        }

        static void post(BalanceChangedEvent event) {
            if (BALANCE_CHANGED.hasListeners()) {
                BALANCE_CHANGED.post(new BalanceChanged(event));
            }
        }

        /** {@code VariableEconomyEvents.balanceChanged(event => ...)}: one account's changes this tick. */
        public static final class BalanceChanged implements KubeEvent {
            private final BalanceChangedEvent event;

            BalanceChanged(BalanceChangedEvent event) {
                this.event = event;
            }

            public MinecraftServer getServer() {
                return event.getServer();
            }

            /** The account, as its ID. */
            public AccountId getAccount() {
                return event.getAccount();
            }

            /** Who owns the account, or null if it has ended. */
            @Nullable
            public UUID getOwner() {
                return CoreApi.accounts().get(event.getServer(), event.getAccount()).map(info -> info.owner()).orElse(null);
            }

            /** The owner, if they're online. */
            @Nullable
            public ServerPlayer getPlayer() {
                UUID owner = getOwner();
                return owner == null ? null : event.getServer().getPlayerList().getPlayer(owner);
            }

            public long getBefore() {
                return event.getBefore();
            }

            public long getAfter() {
                return event.getAfter();
            }

            /** Each change, in order: {@code amount} (signed), {@code reason} (its ID) and {@code actor} (a UUID, or null). */
            public List<Change> getChanges() {
                return event.getChanges().stream().map(Change::new).toList();
            }
        }

        /** One change as scripts read it: getters, which KubeJS shows scripts as properties. */
        public static final class Change {
            private final BalanceChangedEvent.Change change;

            Change(BalanceChangedEvent.Change change) {
                this.change = change;
            }

            public long getAmount() {
                return change.amount();
            }

            public String getReason() {
                return change.reason().id().toString();
            }

            @Nullable
            public UUID getActor() {
                return change.reason().actor().orElse(null);
            }
        }
    }
}
