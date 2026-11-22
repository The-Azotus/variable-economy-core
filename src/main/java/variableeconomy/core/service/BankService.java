package variableeconomy.core.service;

import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.PaymentResult;
import variableeconomy.core.api.Reason;
import net.minecraft.resources.ResourceLocation;
import java.util.OptionalLong;
import variableeconomy.core.bank.Bank;
import variableeconomy.core.bank.CoinMath;
import variableeconomy.core.bank.Denomination;
import variableeconomy.core.bank.WorldBank;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Moves money between a player's coins and an account they may use (their active account unless one is named), through
 * core's money service: the only way coins become money and back. The market itself never pays or takes coins.
 * <p>
 * Same rules as every money movement: server thread only, every check before anything changes, and once something has
 * changed no remaining step can fail. Only the main inventory is read or written, only plain coins (no renamed or
 * otherwise changed copies) count, and nothing is ever dropped on the ground.
 */
public final class BankService {
    private BankService() {
    }

    /** What coins paid in are, in core's money service. */
    public static final ResourceLocation COINS_IN = ResourceLocation.fromNamespaceAndPath(CoreModule.MOD_ID, "coin_deposit");
    /** What coins taken out are, in core's money service. */
    public static final ResourceLocation COINS_OUT = ResourceLocation.fromNamespaceAndPath(CoreModule.MOD_ID, "coin_withdrawal");

    public enum Result {
        OK,
        /** The main inventory holds none of the bank's coins. */
        NO_COINS,
        NO_FUNDS,
        /** The coins wouldn't all fit in the main inventory. */
        NO_SPACE,
        /** More than the account's balance can hold. */
        TOO_LARGE,
        /** Zero or negative. */
        INVALID_AMOUNT,
        /** The player may not do that with this account: they aren't a member, or a deposit-only member withdraws. */
        NOT_ALLOWED,
        /** The world's bank can't be reached. The mod's own bank is always reachable, so this isn't returned today. */
        BANK_UNAVAILABLE
    }

    /** {@code amount} is in bits: what was deposited or withdrawn, or 0 if nothing was. */
    public record Outcome(Result result, long amount) {
        static Outcome fail(Result result) {
            return new Outcome(result, 0);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    /** Pays every plain coin of the bank's coin set in the player's main inventory into their active account. */
    public static Outcome depositAll(ServerPlayer player) {
        return depositAll(player, CoreApi.accounts().active(player));
    }

    /** Pays every plain coin in the player's main inventory into {@code account}, which they must be a member of. */
    public static Outcome depositAll(ServerPlayer player, AccountId account) {
        requireServerThread(player);
        if (!allowed(player, account, false)) {
            return Outcome.fail(Result.NOT_ALLOWED);
        }
        List<Denomination> coins = WorldBank.of(player.server).coins();
        OptionalLong balance = CoreApi.money().balance(player.server, account);
        if (balance.isEmpty() || coins.isEmpty()) {
            return Outcome.fail(Result.BANK_UNAVAILABLE);
        }
        NonNullList<ItemStack> items = player.getInventory().items;
        long total = 0;
        for (ItemStack stack : items) {
            Denomination coin = coinOf(coins, stack);
            if (coin != null) {
                total += (long) coin.value() * stack.getCount();
            }
        }
        if (total == 0) {
            return Outcome.fail(Result.NO_COINS);
        }
        if (balance.getAsLong() > Long.MAX_VALUE - total) {
            return Outcome.fail(Result.TOO_LARGE);
        }

        // The payment is the first change, and changes nothing if it is refused. Taking the coins after it can't fail.
        PaymentResult paid = CoreApi.money().pay(player.server, account, total, Reason.by(COINS_IN, player.getUUID()));
        if (!paid.ok()) {
            return Outcome.fail(switch (paid.result()) {
                case TOO_LARGE -> Result.TOO_LARGE;
                case NO_ACCOUNT, NOT_ALLOWED -> Result.NOT_ALLOWED;
                default -> Result.BANK_UNAVAILABLE;
            });
        }
        takeCoins(player, coins);
        return new Outcome(Result.OK, total);
    }

    /** Removes every plain coin of {@code coins} from the player's main inventory. */
    private static void takeCoins(ServerPlayer player, List<Denomination> coins) {
        NonNullList<ItemStack> items = player.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            if (coinOf(coins, items.get(slot)) != null) {
                items.set(slot, ItemStack.EMPTY);
            }
        }
        player.getInventory().setChanged();
    }

    /**
     * Takes {@code amount} from the player's active account and gives it to them as the fewest coins, only if they all
     * fit in the main inventory.
     */
    public static Outcome withdraw(ServerPlayer player, long amount) {
        return withdraw(player, amount, CoreApi.accounts().active(player));
    }

    /** As {@link #withdraw(ServerPlayer, long)}, from {@code account}, which the player must be able to spend from. */
    public static Outcome withdraw(ServerPlayer player, long amount, AccountId account) {
        requireServerThread(player);
        if (amount <= 0) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        List<Denomination> coins = WorldBank.of(player.server).coins();
        if (coins.isEmpty()) {
            return Outcome.fail(Result.BANK_UNAVAILABLE);
        }
        return pay(player, account, coins, amount, CoinMath.breakdown(coins, amount));
    }

    /**
     * Takes {@code count} of one coin's worth from the player's active account and gives exactly that many of that
     * coin, only if they all fit in the main inventory. {@code coin} must be one of the bank's coins.
     */
    public static Outcome withdrawCoins(ServerPlayer player, Item coin, int count) {
        return withdrawCoins(player, coin, count, CoreApi.accounts().active(player));
    }

    /** As {@link #withdrawCoins(ServerPlayer, Item, int)}, from {@code account}, which the player must be able to spend from. */
    public static Outcome withdrawCoins(ServerPlayer player, Item coin, int count, AccountId account) {
        requireServerThread(player);
        List<Denomination> coins = WorldBank.of(player.server).coins();
        if (coins.isEmpty()) {
            return Outcome.fail(Result.BANK_UNAVAILABLE);
        }
        int index = -1;
        for (int i = 0; i < coins.size(); i++) {
            if (coins.get(i).item() == coin) {
                index = i;
            }
        }
        if (count <= 0 || index < 0) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        long amount = (long) coins.get(index).value() * count;
        long[] counts = new long[coins.size()];
        counts[index] = count;
        return pay(player, account, coins, amount, counts);
    }

    /** Takes {@code amount} and gives {@code counts} of each coin, worth as much. */
    private static Outcome pay(ServerPlayer player, AccountId account, List<Denomination> coins, long amount, long[] counts) {
        if (!allowed(player, account, true)) {
            return Outcome.fail(Result.NOT_ALLOWED);
        }
        NonNullList<ItemStack> items = player.getInventory().items;
        if (!CoinMath.fits(coins, counts, emptySlots(items), coin -> stackRoom(items, coin), BankService::maxStackSize)) {
            return Outcome.fail(Result.NO_SPACE);
        }
        // The charge is itself the funds check: it changes nothing unless it succeeds.
        PaymentResult charged = CoreApi.money().charge(player.server, account, amount,
                Reason.by(COINS_OUT, player.getUUID()));
        if (!charged.ok()) {
            return Outcome.fail(charged.result() == BankResult.NO_FUNDS ? Result.NO_FUNDS : Result.BANK_UNAVAILABLE);
        }

        // Money has been taken. Everything below always succeeds: the room was checked above.
        for (int i = 0; i < coins.size(); i++) {
            insert(items, coins.get(i), counts[i]);
        }
        player.getInventory().setChanged();
        return new Outcome(Result.OK, amount);
    }

    /** Whether the player may put money into ({@code spend} false) or take it out of an account. */
    private static boolean allowed(ServerPlayer player, AccountId account, boolean spend) {
        return CoreApi.accounts().role(player.server, account, player.getUUID())
                .filter(role -> spend ? role.canSpend() : role.canDeposit()).isPresent();
    }

    /** The coin a stack is, if it's a plain coin of this set. */
    @Nullable
    private static Denomination coinOf(List<Denomination> coins, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        for (Denomination coin : coins) {
            if (isPlain(stack, coin)) {
                return coin;
            }
        }
        return null;
    }

    /** Whether {@code stack} is {@code coin} with nothing changed about it, so it stacks with a fresh one. */
    public static boolean isPlain(ItemStack stack, Denomination coin) {
        return stack.is(coin.item()) && ItemStack.isSameItemSameComponents(stack, new ItemStack(coin.item()));
    }

    public static int maxStackSize(Denomination coin) {
        return new ItemStack(coin.item()).getMaxStackSize();
    }

    public static int emptySlots(NonNullList<ItemStack> items) {
        int empty = 0;
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    /** Room left in the existing plain stacks of {@code coin}. */
    public static int stackRoom(NonNullList<ItemStack> items, Denomination coin) {
        int room = 0;
        for (ItemStack stack : items) {
            if (isPlain(stack, coin)) {
                room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return room;
    }

    /** Tops up existing stacks first, then fills empty slots. The caller has checked the room. */
    private static void insert(NonNullList<ItemStack> items, Denomination coin, long count) {
        int maxStack = maxStackSize(coin);
        long remaining = count;
        for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
            ItemStack stack = items.get(slot);
            if (isPlain(stack, coin) && stack.getCount() < maxStack) {
                int added = (int) Math.min(remaining, maxStack - stack.getCount());
                stack.grow(added);
                remaining -= added;
            }
        }
        for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
            if (items.get(slot).isEmpty()) {
                int added = (int) Math.min(remaining, maxStack);
                items.set(slot, new ItemStack(coin.item(), added));
                remaining -= added;
            }
        }
        if (remaining > 0) {
            throw new IllegalStateException("Coin room check and insert disagree; " + remaining + " coins lost");
        }
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.server.isSameThread()) {
            throw new IllegalStateException("Bank transactions must run on the server thread");
        }
    }
}
