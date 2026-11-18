package variableeconomy.core.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.core.CoreConfig;
import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.Reason;
import variableeconomy.core.coin.Coin;
import variableeconomy.core.coin.Coins;
import variableeconomy.core.data.TransactionHistory;
import variableeconomy.core.network.BankPayloads;
import variableeconomy.core.network.BankSync;
import variableeconomy.core.service.BankService;
import variableeconomy.core.service.CoreServices;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * Each account's transaction history: a line for every balance change, kept to the configured size, read by the
 * account's owner and members only, by command and in the bank screen.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HistoryTests {
    private HistoryTests() {
    }

    private static List<TransactionHistory.Line> history(MinecraftServer server, AccountId account) {
        return TransactionHistory.get(server).of(account.value());
    }

    @GameTest(template = EMPTY)
    public static void everyBalanceChangeWritesOneLine(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        CoreFixture other = new CoreFixture(helper);
        AccountId mine = CoreApi.accounts().personal(f.player.getUUID());
        AccountId theirs = CoreApi.accounts().personal(other.player.getUUID());
        Reason reward = Reason.by(rl("reward"), other.player.getUUID());
        Reason fee = Reason.of(rl("fee"));
        try {
            CoreApi.money().pay(server, mine, 500, reward);
            List<TransactionHistory.Line> lines = history(server, mine);
            helper.assertValueEqual(lines.size(), 1, "one line for a payment");
            TransactionHistory.Line paid = lines.get(0);
            helper.assertValueEqual(paid.amount(), 500L, "the amount, signed");
            helper.assertValueEqual(paid.reason(), rl("reward"), "the reason");
            helper.assertValueEqual(paid.actor(), Optional.of(other.player.getUUID()), "who caused it");
            helper.assertValueEqual(paid.balance(), 500L, "the balance after");

            CoreApi.money().charge(server, mine, 200, fee);
            helper.assertValueEqual(history(server, mine).get(0).amount(), -200L, "a charge is negative, newest first");
            CoreApi.money().transfer(server, mine, theirs, 100, fee);
            helper.assertValueEqual(history(server, mine).get(0).amount(), -100L, "a transfer out");
            helper.assertValueEqual(history(server, theirs).get(0).amount(), 100L, "and in, on the other account");

            int bit = Coins.denominations().stream().filter(d -> d.item() == Coins.item(Coin.BIT)).findFirst().orElseThrow().value();
            f.give(new ItemStack(Coins.item(Coin.BIT), 3));
            BankService.depositAll(f.player);
            TransactionHistory.Line coins = history(server, mine).get(0);
            helper.assertValueEqual(coins.reason(), BankService.COINS_IN, "coins deposited");
            helper.assertValueEqual(coins.amount(), 3L * bit, "their worth");
            helper.assertValueEqual(coins.actor(), Optional.of(f.player.getUUID()), "by the player");
            helper.assertValueEqual(history(server, mine).size(), 4, "four changes, four lines");

            TransactionHistory loaded = TransactionHistory.fromTag(TransactionHistory.get(server).save(new CompoundTag(),
                    helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
            helper.assertValueEqual(loaded.of(mine.value()), history(server, mine), "history survives a save");
        } finally {
            f.account().deduct(f.account().balance());
            other.account().deduct(other.account().balance());
        }
        helper.succeed();
    }

    /** The screen asks for the history at every balance change; replies are limited to one a second per player. */
    @GameTest(template = EMPTY)
    public static void historyRepliesWaitForTheirCooldown(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        helper.assertTrue(BankSync.historyDue(player, 1000), "the first reply goes at once");
        BankSync.historySentForTesting(player, 1000);
        helper.assertFalse(BankSync.historyDue(player, 1001), "the next tick it waits");
        helper.assertFalse(BankSync.historyDue(player, 1000 + BankSync.HISTORY_COOLDOWN_TICKS - 1), "for the whole cooldown");
        helper.assertTrue(BankSync.historyDue(player, 1000 + BankSync.HISTORY_COOLDOWN_TICKS), "and goes once it ends");
        helper.assertTrue(BankSync.historyDue(player, 999), "a tick count that went back (a restart) never blocks it");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void historyKeepsOnlyTheNewestLines(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        AccountId mine = CoreApi.accounts().personal(f.player.getUUID());
        int size = CoreConfig.HISTORY_SIZE.get();
        try {
            CoreConfig.HISTORY_SIZE.set(3);
            for (int i = 1; i <= 5; i++) {
                CoreApi.money().pay(server, mine, i, Reason.of(rl("count")));
            }
            List<TransactionHistory.Line> lines = history(server, mine);
            helper.assertValueEqual(lines.stream().map(TransactionHistory.Line::amount).toList(), List.of(5L, 4L, 3L), "the newest three");
            CoreConfig.HISTORY_SIZE.set(0);
            CoreApi.money().pay(server, mine, 6, Reason.of(rl("count")));
            helper.assertValueEqual(history(server, mine).size(), 3, "with history off, nothing more is written");
        } finally {
            CoreConfig.HISTORY_SIZE.set(size);
            f.account().deduct(f.account().balance());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void onlyOwnersAndMembersReadAnAccountsHistory(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture owner = new CoreFixture(helper);
        CoreFixture member = new CoreFixture(helper);
        CoreFixture payer = new CoreFixture(helper);
        CoreFixture stranger = new CoreFixture(helper);
        String name = accountName("h");
        withAccounts(() -> {
            AccountId account = CoreApi.accounts().create(server, owner.player.getUUID(), name).account().orElseThrow();
            CoreApi.accounts().setMember(server, account, member.player.getUUID(), AccountRole.MEMBER);
            CoreApi.accounts().setMember(server, account, payer.player.getUUID(), AccountRole.DEPOSIT_ONLY);
            CoreApi.money().pay(server, account, 40, Reason.of(rl("history")));
            try {
                helper.assertValueEqual(BankSync.historyFor(owner.player, account).access(), BankPayloads.HistoryAccess.OK, "the owner reads it");
                BankPayloads.History seen = BankSync.historyFor(member.player, account);
                helper.assertValueEqual(seen.access(), BankPayloads.HistoryAccess.OK, "a member does");
                helper.assertValueEqual(seen.lines().get(0).amount(), 40L, "and sees the payment");
                helper.assertValueEqual(BankSync.historyFor(payer.player, account).access(), BankPayloads.HistoryAccess.NOT_ALLOWED,
                        "a deposit-only member doesn't");
                helper.assertValueEqual(BankSync.historyFor(stranger.player, account).access(), BankPayloads.HistoryAccess.NOT_ALLOWED,
                        "nor does a stranger");
                helper.assertTrue(BankSync.historyFor(payer.player, account).lines().isEmpty(), "who get no lines");

                String key = "command.variable_economy.history.";
                List<String> read = commandKeysAs(helper, owner.player, "bank history " + name);
                helper.assertValueEqual(read.get(0), key + "header", "a header");
                helper.assertValueEqual(read.size(), 2, "then the one line: " + read);
                helper.assertValueEqual(commandKeysAs(helper, payer.player, "bank history " + name), List.of(key + "not_allowed"),
                        "deposit-only members are refused");
                helper.assertValueEqual(commandKeysAs(helper, stranger.player, "bank history " + name),
                        List.of("command.variable_economy.account.not_yours"), "strangers don't find it");
                helper.assertTrue(runCommand(helper, "bank admin history account " + name).size() == 2, "an admin reads any account's");
            } finally {
                long left = CoreApi.money().balance(server, account).orElse(0L);
                if (left > 0) {
                    CoreApi.money().charge(server, account, left, Reason.of(rl("history")));
                }
                CoreServices.ACCOUNTS.closeAccount(server, account, owner.player.getUUID());
            }
        });
        helper.succeed();
    }
}
