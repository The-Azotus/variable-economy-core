package variableeconomy.core.gametest;

import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.core.CoreConfig;
import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.Reason;
import variableeconomy.core.command.PayCommand;
import variableeconomy.core.data.TransactionHistory;
import variableeconomy.core.service.CoreServices;

import java.util.List;
import java.util.UUID;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * {@code /pay}: the amount and its fee in one step, the fee destroyed, the server's switches, and where the money goes.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PayTests {
    private PayTests() {
    }

    @GameTest(template = EMPTY)
    public static void payMovesTheAmountAndDestroysTheFee(GameTestHelper helper) {
        CoreFixture sender = new CoreFixture(helper);
        CoreFixture recipient = new CoreFixture(helper);
        double percent = CoreConfig.PAY_FEE_PERCENT.get();
        try {
            sender.deposit(1000);
            PayCommand.Attempt free = PayCommand.attempt(sender.player, recipient.player.getUUID(), 100);
            helper.assertTrue(free.ok() && free.key().equals("paid") && free.fee() == 0, "paid with no fee: " + free);
            helper.assertValueEqual(sender.balance(), 900, "taken from the sender");
            helper.assertValueEqual(recipient.balance(), 100, "given to the recipient");

            CoreConfig.PAY_FEE_PERCENT.set(10.0);
            PayCommand.Attempt charged = PayCommand.attempt(sender.player, recipient.player.getUUID(), 105);
            helper.assertTrue(charged.ok() && charged.key().equals("paid_fee"), "paid with a fee: " + charged);
            helper.assertValueEqual(charged.fee(), 11L, "10% of 105, rounded up");
            helper.assertValueEqual(sender.balance(), 900 - 105 - 11, "the sender pays the fee on top");
            helper.assertValueEqual(recipient.balance(), 205, "the recipient gets the whole amount");
            TransactionHistory.Line feeLine = TransactionHistory.get(sender.player.server).of(sender.player.getUUID()).get(0);
            helper.assertValueEqual(feeLine.reason(), PayCommand.FEE, "the fee is its own line");
            helper.assertValueEqual(feeLine.amount(), -11L, "and destroyed");

            PayCommand.Attempt tooMuch = PayCommand.attempt(sender.player, recipient.player.getUUID(), 780);
            helper.assertTrue(!tooMuch.ok() && tooMuch.key().equals("no_funds"), "784 left can't cover 780 and its 78 fee: " + tooMuch);
            helper.assertValueEqual(sender.balance(), 784, "nothing taken");
            helper.assertValueEqual(recipient.balance(), 205, "nothing given");
            helper.assertValueEqual(PayCommand.fee(1, 0.5), 1L, "any fee is at least a bit");
            helper.assertValueEqual(PayCommand.fee(1000, 0), 0L, "no fee, nothing");
        } finally {
            CoreConfig.PAY_FEE_PERCENT.set(percent);
            sender.account().deduct(sender.account().balance());
            recipient.account().deduct(recipient.account().balance());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void payFollowsTheServersRules(GameTestHelper helper) {
        CoreFixture sender = new CoreFixture(helper);
        CoreFixture recipient = new CoreFixture(helper);
        long min = CoreConfig.PAY_MIN_AMOUNT.get();
        try {
            sender.deposit(500);
            helper.assertValueEqual(PayCommand.attempt(sender.player, sender.player.getUUID(), 5).key(), "self", "not yourself");
            helper.assertValueEqual(PayCommand.attempt(sender.player, UUID.randomUUID(), 5).key(), "unknown",
                    "not a player the server has never seen");
            CoreConfig.PAY_MIN_AMOUNT.set(50L);
            helper.assertValueEqual(PayCommand.attempt(sender.player, recipient.player.getUUID(), 49).key(), "too_small", "the minimum");
            helper.assertTrue(PayCommand.attempt(sender.player, recipient.player.getUUID(), 50).ok(), "the minimum itself is fine");
            CoreConfig.PAY_ENABLED.set(false);
            helper.assertValueEqual(PayCommand.attempt(sender.player, recipient.player.getUUID(), 50).key(), "off", "turned off");
            helper.assertValueEqual(commandKeysAs(helper, sender.player, "pay " + recipient.player.getGameProfile().getName() + " 5").size(), 1,
                    "the command answers too");
            helper.assertValueEqual(sender.balance(), 450, "only the one payment went through");
        } finally {
            CoreConfig.PAY_MIN_AMOUNT.set(min);
            CoreConfig.PAY_ENABLED.set(true);
            sender.account().deduct(sender.account().balance());
            recipient.account().deduct(recipient.account().balance());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void payGoesIntoTheRecipientsActiveAccount(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture sender = new CoreFixture(helper);
        CoreFixture recipient = new CoreFixture(helper);
        withAccounts(() -> {
            AccountId shared = CoreApi.accounts().create(server, recipient.player.getUUID(), accountName("pay")).account().orElseThrow();
            CoreApi.accounts().setMember(server, shared, sender.player.getUUID(), AccountRole.MEMBER);
            try {
                sender.deposit(300);
                CoreApi.accounts().setActive(recipient.player, shared);
                PayCommand.Attempt into = PayCommand.attempt(sender.player, recipient.player.getUUID(), 40);
                helper.assertTrue(into.ok() && into.to().equals(shared), "into the account the recipient uses: " + into);
                JsonObject lang = lang(CoreModule.MOD_ID);
                for (String key : List.of("paid_account", "paid_fee_account", "received_account")) {
                    helper.assertTrue(lang.has("command.variable_economy.pay." + key), "the messages name that account: " + key);
                }
                helper.assertValueEqual(CoreApi.money().balance(server, shared).orElseThrow(), 40L, "it's there");
                CoreApi.accounts().setActive(sender.player, shared);
                helper.assertValueEqual(PayCommand.attempt(sender.player, recipient.player.getUUID(), 10).key(), "same_account",
                        "paying from the same account moves nothing");
                helper.assertValueEqual(CoreApi.money().balance(server, shared).orElseThrow(), 40L, "unchanged");
            } finally {
                CoreApi.accounts().setActive(sender.player, CoreApi.accounts().personal(sender.player.getUUID()));
                CoreApi.accounts().setActive(recipient.player, CoreApi.accounts().personal(recipient.player.getUUID()));
                CoreApi.money().charge(server, shared, 40, Reason.of(rl("pay_cleanup")));
                CoreServices.ACCOUNTS.closeAccount(server, shared, recipient.player.getUUID());
                sender.account().deduct(sender.account().balance());
            }
        });
        helper.succeed();
    }
}
