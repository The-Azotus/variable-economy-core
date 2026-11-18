package variableeconomy.core.api;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * The way into Variable Economy's core: its services, and a few helpers. Each service is one object for the whole
 * game, so it may be kept in a field; its methods take the server they act on.
 * <p>
 * The services are ready once Variable Economy has been constructed, which NeoForge does before any mod that depends
 * on it. Called earlier, every method but {@link #formatMoney} throws {@link IllegalStateException}.
 */
public final class CoreApi {
    private static volatile Services services;

    private CoreApi() {
    }

    /**
     * Reading, creating and sharing accounts, and each player's active account. Any thread.
     *
     * @return the accounts service
     */
    public static Accounts accounts() {
        return services().accounts();
    }

    /**
     * Balances, payments, charges and transfers. Any thread.
     *
     * @return the money service
     */
    public static Money money() {
        return services().money();
    }

    /**
     * Which items each player has unlocked. Any thread.
     *
     * @return the item gate
     */
    public static ItemGate itemGate() {
        return services().itemGate();
    }

    /**
     * Where add-ons register the money they hold for accounts, so the leaderboard counts it. Any thread.
     *
     * @return the net-worth registry
     */
    public static NetWorth netWorth() {
        return services().netWorth();
    }

    /**
     * The admin audit log, for add-ons' own admin actions. Any thread.
     *
     * @return the audit log
     */
    public static Audit audit() {
        return services().audit();
    }

    /**
     * Whether {@code item} is money: one of Variable Economy's eight coins, or one of Create: Numismatics' coins should
     * a pack have that mod. Coins can never be market, exchange or contract items, since selling them would create
     * money from nothing; an add-on that pays for items must refuse them too. Any thread.
     *
     * @param item the item to check
     * @return whether it is a coin
     */
    public static boolean isCoin(Item item) {
        return services().isCoin(Objects.requireNonNull(item, "item"));
    }

    /**
     * Whether {@code stack} is a plain copy of {@code item}: as it comes fresh, not enchanted, damaged or renamed. Data
     * the server's {@code ignored_components} setting lists doesn't count, since some mods add it to every copy a
     * player holds. Every module takes items for money only as plain copies. Any thread.
     *
     * @param stack the stack to check
     * @param item  the item it should be
     * @return whether it is a plain copy of the item
     */
    public static boolean isPlain(ItemStack stack, Item item) {
        return services().isPlain(Objects.requireNonNull(stack, "stack"), Objects.requireNonNull(item, "item"));
    }

    /**
     * An amount as chat shows it, such as "1,234 bits", translated on the player's side. Any thread, also before
     * Variable Economy is constructed.
     *
     * @param amount the amount, in bits
     * @return the text to show
     */
    public static Component formatMoney(long amount) {
        return Component.translatable("money.variable_economy.bits", String.format("%,d", amount));
    }

    /**
     * Hands the API its services. Variable Economy calls this once, while it is constructed; a second call throws.
     *
     * @param services the services
     */
    @ApiStatus.Internal
    public static void install(Services services) {
        Objects.requireNonNull(services, "services");
        synchronized (CoreApi.class) {
            if (CoreApi.services != null) {
                throw new IllegalStateException("Variable Economy's core API is already installed");
            }
            CoreApi.services = services;
        }
    }

    private static Services services() {
        Services installed = services;
        if (installed == null) {
            throw new IllegalStateException("Variable Economy's core isn't constructed yet");
        }
        return installed;
    }

    /** What Variable Economy installs: the implementations behind {@link CoreApi}'s methods. Not for add-ons. */
    @ApiStatus.Internal
    public interface Services {
        /**
         * The accounts service.
         *
         * @return the accounts service
         */
        Accounts accounts();

        /**
         * The money service.
         *
         * @return the money service
         */
        Money money();

        /**
         * The item gate.
         *
         * @return the item gate
         */
        ItemGate itemGate();

        /**
         * The net-worth registry.
         *
         * @return the net-worth registry
         */
        NetWorth netWorth();

        /**
         * The audit log.
         *
         * @return the audit log
         */
        Audit audit();

        /**
         * Whether the item is a coin.
         *
         * @param item the item
         * @return whether it is a coin
         */
        boolean isCoin(Item item);

        /**
         * Whether the stack is a plain copy of the item.
         *
         * @param stack the stack
         * @param item  the item
         * @return whether it is plain
         */
        boolean isPlain(ItemStack stack, Item item);
    }
}
