package variableeconomy.core.gametest;

import variableeconomy.core.CoreModule;
import variableeconomy.core.bank.Bank;
import variableeconomy.core.bank.CoinMath;
import variableeconomy.core.bank.Denomination;
import variableeconomy.core.bank.OwnBank;
import variableeconomy.core.bank.WorldBank;
import variableeconomy.core.coin.Coin;
import variableeconomy.core.coin.Coins;
import variableeconomy.core.compat.FtbTeamsBridge;
import variableeconomy.core.command.AdminBankCommands;
import variableeconomy.core.service.AccountsService;
import variableeconomy.core.service.BankService;
import variableeconomy.core.service.CoreServices;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * The bank and its coins: deposits, withdrawals, coin recipes, team accounts and the Bank Card.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BankTests {
    private BankTests() {
    }


    // ---- Coins -------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void coinsAreWorthEightOfTheOneBelow(GameTestHelper helper) {
        helper.assertValueEqual(Coin.BIT.value(), 1, "a Bit is one bit");
        for (Coin coin : Coin.values()) {
            coin.next().ifPresent(next -> helper.assertValueEqual(next.value(), coin.value() * 8, next + " is 8 " + coin));
            helper.assertValueEqual(Coins.item(coin).coin(), coin, "registered as " + coin.id());
            helper.assertTrue(Coins.isCoin(Coins.item(coin)), coin + " is a coin");
        }
        helper.assertValueEqual(Coin.CROWN.value(), 4_096, "Crown");
        helper.assertValueEqual(Coin.SUN.value(), 32_768, "Sun");
        helper.assertValueEqual(Coin.OMEGA.value(), 2_097_152, "Omega");
        helper.assertFalse(Coins.isCoin(Items.GOLD_NUGGET), "other items aren't coins");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void numismaticsCoinsAreKnownById(GameTestHelper helper) {
        // The test runs don't have Numismatics, so its coins are checked by ID: a pack that has it can't sell them.
        for (String path : List.of("spur", "bevel", "sprocket", "cog", "crown", "sun")) {
            helper.assertTrue(Coins.isCoinId(ResourceLocation.fromNamespaceAndPath("numismatics", path)), "numismatics:" + path + " is a coin");
        }
        helper.assertTrue(Coins.isCoinId(ResourceLocation.fromNamespaceAndPath(CoreModule.MOD_ID, "bit")), "and so is our Bit");
        for (String id : List.of("numismatics:bank_terminal", CoreModule.MOD_ID + ":bank_card", "minecraft:gold_nugget")) {
            helper.assertFalse(Coins.isCoinId(ResourceLocation.parse(id)), id + " isn't a coin");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void coinsChangeEightForOne(GameTestHelper helper) {
        for (Coin coin : Coin.values()) {
            if (coin.next().isEmpty()) {
                continue;
            }
            Item small = Coins.item(coin);
            Item big = Coins.item(coin.next().get());
            Optional<ItemStack> up = craft(helper, small, small, small, small, small, small, small, small, Items.AIR);
            Optional<ItemStack> down = craft(helper, big, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR);
            helper.assertTrue(up.isPresent() && up.get().is(big) && up.get().getCount() == 1, "8 " + coin + " make one " + big + ": " + up);
            helper.assertTrue(down.isPresent() && down.get().is(small) && down.get().getCount() == 8, "and back into 8: " + down);
        }
        // Seven coins of one kind make nothing: the exchange never rounds.
        Item bit = Coins.item(Coin.BIT);
        helper.assertTrue(craft(helper, bit, bit, bit, bit, bit, bit, bit, Items.AIR, Items.AIR).isEmpty(), "7 Bits make nothing");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void coinsAreInTheCreativeTab(GameTestHelper helper) {
        // The coins are always in use, so they're always listed; tab contents are built the same way on a server.
        CreativeModeTabs.tryRebuildTabContents(helper.getLevel().enabledFeatures(), true, helper.getLevel().registryAccess());
        CreativeModeTab ingredients = BuiltInRegistries.CREATIVE_MODE_TAB.getOrThrow(CreativeModeTabs.INGREDIENTS);
        for (Coin coin : Coin.values()) {
            helper.assertTrue(ingredients.contains(new ItemStack(Coins.item(coin))), coin + " is in the Ingredients tab");
        }
        helper.assertTrue(BuiltInRegistries.CREATIVE_MODE_TAB.getOrThrow(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .contains(new ItemStack(Coins.BANK_CARD.get())), "the Bank Card is in the Tools and Utilities tab");
        helper.succeed();
    }

    // ---- Bank screen -------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void withdrawalsUseTheFewestCoins(GameTestHelper helper) {
        List<Denomination> ours = Coins.denominations();
        helper.assertValueEqual(new OwnBank().coins(), ours, "the bank pays in the mod's coins");
        helper.assertValueEqual(ours.get(0).item(), Coins.item(Coin.OMEGA), "ours run from Omega");
        helper.assertValueEqual(ours.get(ours.size() - 1).item(), Coins.item(Coin.BIT), "down to Bit");
        int limit = 20_000;
        int[] fewest = fewestCoins(ours, limit);
        for (int amount = 0; amount <= limit; amount++) {
            long[] counts = CoinMath.breakdown(ours, amount);
            helper.assertValueEqual(CoinMath.value(ours, counts), (long) amount, "exactly " + amount);
            helper.assertValueEqual(java.util.Arrays.stream(counts).sum(), (long) fewest[amount], "fewest coins for " + amount);
        }
        long[] mixed = CoinMath.breakdown(ours, 4_096 + 3 * 512 + 7);
        helper.assertValueEqual(java.util.Arrays.toString(mixed), "[0, 0, 0, 1, 3, 0, 0, 7]", "a Crown, 3 Marks and 7 Bits");
        helper.succeed();
    }

    /** The fewest coins for every amount up to {@code limit}, worked out the slow, sure way. */
    private static int[] fewestCoins(List<Denomination> coins, int limit) {
        int[] fewest = new int[limit + 1];
        for (int amount = 1; amount <= limit; amount++) {
            fewest[amount] = Integer.MAX_VALUE;
            for (Denomination coin : coins) {
                if (coin.value() <= amount && fewest[amount - coin.value()] + 1 < fewest[amount]) {
                    fewest[amount] = fewest[amount - coin.value()] + 1;
                }
            }
        }
        return fewest;
    }

    @GameTest(template = EMPTY)
    public static void depositThenWithdrawReturnsTheSameValue(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        List<Denomination> coins = f.bank().coins();
        helper.assertFalse(coins.isEmpty(), "the world's bank has coins");
        // A few of every coin, worth well within a test balance.
        long carried = 0;
        for (int i = 0; i < coins.size(); i++) {
            int count = coins.size() - i;
            f.give(new ItemStack(coins.get(i).item(), count));
            carried += (long) count * coins.get(i).value();
        }
        int before = f.balance();
        BankService.Outcome in = BankService.depositAll(f.player);
        helper.assertTrue(in.ok() && in.amount() == carried, "deposited what was carried: " + in);
        helper.assertValueEqual((long) f.balance(), before + carried, "balance");
        helper.assertValueEqual(coinValue(f, coins), 0L, "no coins left");

        BankService.Outcome out = BankService.withdraw(f.player, carried);
        helper.assertTrue(out.ok() && out.amount() == carried, "withdrew it all: " + out);
        helper.assertValueEqual(f.balance(), before, "balance back where it was");
        helper.assertValueEqual(coinValue(f, coins), carried, "and exactly the same value in coins");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void depositTakesOnlyPlainCoinsFromTheMainInventory(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        Denomination smallest = smallestCoin(f);
        ItemStack renamed = new ItemStack(smallest.item(), 5);
        renamed.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("keepsake"));
        f.give(renamed);
        f.player.getInventory().offhand.set(0, new ItemStack(smallest.item(), 7));
        f.assertRejectedUnchanged(BankService.Result.NO_COINS, () -> BankService.depositAll(f.player));
        f.give(new ItemStack(smallest.item(), 3));
        BankService.Outcome outcome = BankService.depositAll(f.player);
        helper.assertTrue(outcome.ok() && outcome.amount() == 3L * smallest.value(), "only the 3 plain coins: " + outcome);
        helper.assertTrue(f.player.getInventory().items.stream().anyMatch(stack -> stack.is(smallest.item())
                && stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME) && stack.getCount() == 5), "the renamed coins stay");
        helper.assertValueEqual(f.player.getInventory().offhand.get(0).getCount(), 7, "and so does the offhand");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void withdrawRefusesWhatItCantDoAndChangesNothing(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        Denomination smallest = smallestCoin(f);
        f.assertRejectedUnchanged(BankService.Result.NO_FUNDS, () -> BankService.withdraw(f.player, 10));
        f.assertRejectedUnchanged(BankService.Result.INVALID_AMOUNT, () -> BankService.withdraw(f.player, 0));
        f.assertRejectedUnchanged(BankService.Result.NO_FUNDS, () -> BankService.withdraw(f.player, 3_000_000_000L));

        f.deposit(1000);
        // A full inventory: the coins wouldn't fit, so nothing is taken and nothing is dropped.
        f.giveMany(Items.COBBLESTONE, 36 * 64);
        f.assertRejectedUnchanged(BankService.Result.NO_SPACE, () -> BankService.withdraw(f.player, 9));
        // One free slot fits one kind of coin, but not two.
        f.player.getInventory().items.set(0, ItemStack.EMPTY);
        f.assertRejectedUnchanged(BankService.Result.NO_SPACE, () -> BankService.withdraw(f.player, smallest.value() + coinAbove(f, smallest).value()));
        BankService.Outcome one = BankService.withdraw(f.player, smallest.value());
        helper.assertTrue(one.ok(), "one coin fits the free slot: " + one);
        // A part-filled stack of that coin is room too, even with no slot free.
        BankService.Outcome topUp = BankService.withdraw(f.player, smallest.value());
        helper.assertTrue(topUp.ok() && f.count(smallest.item()) == 2, "tops up the stack: " + topUp);
        helper.assertValueEqual(f.balance(), 1000 - 2 * smallest.value(), "paid for both");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void withdrawingOneCoinGivesExactlyThatCoin(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        Denomination smallest = smallestCoin(f);
        Denomination above = coinAbove(f, smallest);
        f.assertRejectedUnchanged(BankService.Result.NO_FUNDS, () -> BankService.withdrawCoins(f.player, smallest.item(), 1));
        f.assertRejectedUnchanged(BankService.Result.INVALID_AMOUNT, () -> BankService.withdrawCoins(f.player, smallest.item(), 0));
        f.assertRejectedUnchanged(BankService.Result.INVALID_AMOUNT, () -> BankService.withdrawCoins(f.player, Items.DIAMOND, 1));
        f.assertRejectedUnchanged(BankService.Result.NO_SPACE, () -> BankService.withdrawCoins(f.player, above.item(), Integer.MAX_VALUE));

        // Enough small coins to make the one above: the fewest-coins withdrawal would hand that over instead.
        int count = above.value() / smallest.value();
        f.deposit(above.value());
        BankService.Outcome out = BankService.withdrawCoins(f.player, smallest.item(), count);
        helper.assertTrue(out.ok() && out.amount() == above.value(), "paid what the coins are worth: " + out);
        helper.assertValueEqual(f.count(smallest.item()), (long) count, "exactly that many of that coin");
        helper.assertValueEqual(f.count(above.item()), 0L, "none of the coin above");
        helper.assertValueEqual(f.balance(), 0, "the balance paid for them");

        // No room: nothing is taken and nothing is dropped.
        f.deposit(above.value());
        f.player.getInventory().items.clear();
        f.giveMany(Items.COBBLESTONE, 36 * 64);
        f.assertRejectedUnchanged(BankService.Result.NO_SPACE, () -> BankService.withdrawCoins(f.player, above.item(), 1));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankCardCraftsFromACoinWorthEight(GameTestHelper helper) {
        Item chip = Coins.item(Coin.CHIP);
        Optional<ItemStack> fromChip = craft(helper, Items.PAPER, Items.GOLD_INGOT, chip, Items.AIR, Items.AIR, Items.AIR,
                Items.AIR, Items.AIR, Items.AIR);
        helper.assertTrue(fromChip.isPresent() && fromChip.get().is(Coins.BANK_CARD.get()), "paper, gold and a Chip: " + fromChip);
        helper.assertFalse(helper.getLevel().getServer().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(
                CoreModule.MOD_ID, "bank_card_from_bevel")).isPresent(), "the recipe that took Numismatics' Bevel is gone");
        helper.succeed();
    }

    private static Denomination smallestCoin(CoreFixture f) {
        List<Denomination> coins = f.bank().coins();
        return coins.get(coins.size() - 1);
    }

    private static Denomination coinAbove(CoreFixture f, Denomination coin) {
        List<Denomination> coins = f.bank().coins();
        return coins.get(coins.indexOf(coin) - 1);
    }

    /** What the plain coins of {@code coins} in the main inventory are worth. */
    private static long coinValue(CoreFixture f, List<Denomination> coins) {
        long total = 0;
        for (ItemStack stack : f.player.getInventory().items) {
            for (Denomination coin : coins) {
                if (BankService.isPlain(stack, coin)) {
                    total += (long) stack.getCount() * coin.value();
                }
            }
        }
        return total;
    }

    // ---- Accounts in the bank -----------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void closingAnAccountNeverLosesMoney(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        UUID shared = UUID.randomUUID();
        UUID heir = UUID.randomUUID();
        bank.accountOf(heir).deposit(5);
        bank.accountOf(shared).deposit(1000);
        helper.assertValueEqual(bank.accountOf(shared).balance(), 1000L, "each account has its own balance");
        helper.assertValueEqual(bank.balances(), java.util.Map.of(heir, 5L, shared, 1000L), "every balance, by account");

        helper.assertValueEqual(bank.close(shared, heir), 1000L, "closing pays out the whole balance");
        helper.assertValueEqual(bank.accountOf(heir).balance(), 1005L, "to the heir");
        helper.assertFalse(bank.balances().containsKey(shared), "and the closed account is gone");
        helper.assertValueEqual(bank.close(shared, heir), 0L, "closing an empty account pays nothing");
        helper.assertValueEqual(bank.close(heir, heir), 0L, "an account can't be closed into itself");
        helper.assertValueEqual(bank.accountOf(heir).balance(), 1005L, "which changes nothing");

        // Saved with the world.
        bank.accountOf(shared).deposit(7);
        OwnBank loaded = OwnBank.fromTag(bank.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.balances(), bank.balances(), "balances survive a save");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ftbTeamsPartiesHaveAccountsWhenInstalled(GameTestHelper helper) {
        boolean installed = net.neoforged.fml.ModList.get().isLoaded(FtbTeamsBridge.MOD_ID);
        helper.assertValueEqual(FtbTeamsBridge.isAvailable(), installed, "its API matches 2101.1.11 when installed");
        helper.assertValueEqual(AccountsService.partyAccountsOn(), installed, "and then its parties have accounts, by default");
        // A player in no party has only FTB Teams' personal team, which isn't a shared account.
        CoreFixture f = new CoreFixture(helper);
        helper.assertTrue(FtbTeamsBridge.teamOf(f.player.getUUID()).isEmpty(), "no party");
        helper.assertTrue(CoreServices.ACCOUNTS.partyAccountOf(helper.getLevel().getServer(), f.player.getUUID()).isEmpty(), "no party account");
        helper.succeed();
    }

    // ---- The bank ----------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void everyWorldUsesTheModsOwnBank(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(WorldBank.of(server) == OwnBank.get(server), "the world's bank is the mod's own");
        helper.assertValueEqual(WorldBank.of(server).coins(), Coins.denominations(), "with the mod's coins");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankAdminCommandOnlyReports(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        f.deposit(5);
        try {
            // Read the totals just before the command: the GameTest world keeps other tests' accounts.
            java.util.Map<variableeconomy.core.api.AccountKind, long[]> totals = AdminBankCommands.totals(server);
            List<String> info = runCommand(helper, "bank admin info");
            helper.assertValueEqual(info.size(), 3, "one total per kind of account: " + info);
            long[] personal = totals.get(variableeconomy.core.api.AccountKind.PERSONAL);
            helper.assertValueEqual(info.get(0), "%d personal account(s) hold %s bits".formatted(personal[0],
                    String.format(Locale.ROOT, "%,d", personal[1])), "personal accounts and their total, in bits");
            helper.assertTrue(personal[1] >= 5, "this player's money counts");
            helper.assertTrue(info.get(1).contains("named account(s)") && info.get(2).contains("party account(s)"), "then named and party");
            boolean refused = false;
            try {
                runCommand(helper, "bank admin info switch own confirm");
            } catch (GameTestAssertException e) {
                refused = true;
            }
            helper.assertTrue(refused, "there's no bank to switch to");
        } finally {
            // The GameTest world persists: an emptied account is forgotten.
            f.account().deduct(f.balance());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownBankDeductsAllOrNothing(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        UUID owner = UUID.randomUUID();
        Bank.Account account = bank.accountOf(owner);
        helper.assertValueEqual(account.balance(), 0L, "a new account is empty");
        helper.assertFalse(bank.balances().containsKey(owner), "and not listed");
        account.deposit(100);
        helper.assertFalse(account.deduct(101), "can't deduct more than the balance");
        helper.assertValueEqual(account.balance(), 100L, "and a failed deduction changes nothing");
        helper.assertTrue(account.deduct(100), "can deduct exactly the balance");
        helper.assertValueEqual(account.balance(), 0L, "leaving nothing");
        helper.assertFalse(bank.balances().containsKey(owner), "an emptied account is forgotten");
        helper.assertTrue(account.deduct(0), "deducting nothing always works");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownBankHoldsMoreThanAnInt(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        UUID owner = UUID.randomUUID();
        Bank.Account account = bank.accountOf(owner);
        long expected = 3L * Integer.MAX_VALUE;
        account.deposit(expected);
        helper.assertValueEqual(account.balance(), expected, "balances and amounts are longs");
        helper.assertTrue(!account.deduct(expected + 1), "a deduction it can't cover changes nothing");
        helper.assertValueEqual(account.balance(), expected, "unchanged");
        helper.assertTrue(account.deduct(Integer.MAX_VALUE), "spent");
        helper.assertValueEqual(account.balance(), expected - Integer.MAX_VALUE, "exactly");

        // Saved and loaded back with the world.
        OwnBank loaded = OwnBank.fromTag(bank.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.accountOf(owner).balance(), expected - Integer.MAX_VALUE, "the balance survives a save");
        helper.assertValueEqual(loaded.balances(), bank.balances(), "as does every account");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownBankSavesOnlyWhenChanged(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        Bank.Account account = bank.accountOf(UUID.randomUUID());
        account.balance();
        account.deduct(5);
        helper.assertFalse(bank.isDirty(), "reading or a refused deduction changes nothing to save");
        account.deposit(5);
        helper.assertTrue(bank.isDirty(), "a deposit is saved");
        helper.succeed();
    }
}
