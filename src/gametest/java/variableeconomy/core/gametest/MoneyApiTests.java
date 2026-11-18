package variableeconomy.core.gametest;

import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.PaymentResult;
import variableeconomy.core.api.Reason;
import variableeconomy.core.compat.StagesBridge;
import variableeconomy.core.data.AccountRegistry;
import variableeconomy.core.data.AuditLog;
import variableeconomy.core.data.OwedPayments;
import variableeconomy.core.service.BankService;
import variableeconomy.core.service.CoreServices;
import variableeconomy.core.service.Leaderboard;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * Core's money API, gate rules and {@code /bank admin money}.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MoneyApiTests {
    private MoneyApiTests() {
    }

    static int auditSize(MinecraftServer server) {
        return AuditLog.recent(AuditLog.file(server), 100_000).size();
    }

    static JsonObject newestAudit(MinecraftServer server) {
        return AuditLog.recent(AuditLog.file(server), 1).get(0);
    }

    private static AccountId own(CoreFixture f) {
        return CoreApi.accounts().personal(f.player.getUUID());
    }

    /** Asserts that a money request was refused with {@code expected} and changed no balance and no audit line. */
    private static void assertPaymentRefused(GameTestHelper helper, CoreFixture f, BankResult expected, Supplier<PaymentResult> request) {
        MinecraftServer server = f.player.server;
        int balance = f.balance();
        int lines = auditSize(server);
        PaymentResult result = request.get();
        helper.assertValueEqual(result.result(), expected, "payment result");
        helper.assertFalse(result.ok(), "not ok");
        helper.assertFalse(result.owed(), "nothing owed");
        helper.assertValueEqual(f.balance(), balance, "balance after " + expected);
        helper.assertValueEqual(auditSize(server), lines, "no audit line for a refused " + expected);
    }

    // ---- Money -------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void apiPayAndChargeMoveMoneyAndAuditEachOnce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        CoreFixture other = new CoreFixture(helper);
        UUID id = f.player.getUUID();
        AccountId account = own(f);
        Reason reward = Reason.of(rl("reward"));
        Reason fee = Reason.of(rl("fee"));
        try {
            helper.assertValueEqual(CoreApi.accounts().active(f.player), account, "the player's own account is active");
            int lines = auditSize(server);
            PaymentResult paid = CoreApi.money().pay(server, account, 500, reward);
            helper.assertValueEqual(paid, new PaymentResult(BankResult.OK, 500, false), "paid now, not owed");
            helper.assertTrue(paid.ok(), "ok");
            helper.assertValueEqual(f.balance(), 500, "into the account");
            helper.assertValueEqual(CoreApi.money().balance(server, account), OptionalLong.of(500), "the balance");
            helper.assertValueEqual(auditSize(server), lines + 1, "one audit line");
            JsonObject payLine = newestAudit(server);
            helper.assertValueEqual(payLine.get("actor").getAsString(), "api:api_test:reward", "actor");
            helper.assertValueEqual(payLine.get("action").getAsString(), "api pay", "action");
            helper.assertTrue(payLine.get("target").getAsString().contains(id.toString()), "target: " + payLine);
            helper.assertValueEqual(payLine.get("result").getAsString(), "ok", "result");
            helper.assertValueEqual(payLine.get("after").getAsLong(), 500L, "amount");

            PaymentResult charged = CoreApi.money().charge(server, account, 200, fee);
            helper.assertValueEqual(charged, new PaymentResult(BankResult.OK, 200, false), "charged");
            helper.assertValueEqual(f.balance(), 300, "taken from the account");
            helper.assertValueEqual(auditSize(server), lines + 2, "one more audit line");
            JsonObject chargeLine = newestAudit(server);
            helper.assertValueEqual(chargeLine.get("actor").getAsString(), "api:api_test:fee", "actor");
            helper.assertValueEqual(chargeLine.get("action").getAsString(), "api charge", "action");
            helper.assertValueEqual(chargeLine.get("after").getAsLong(), 200L, "amount");

            AccountId nobody = new AccountId(UUID.randomUUID());
            assertPaymentRefused(helper, f, BankResult.NO_FUNDS, () -> CoreApi.money().charge(server, account, 301, fee));
            assertPaymentRefused(helper, f, BankResult.INVALID_AMOUNT, () -> CoreApi.money().charge(server, account, 0, fee));
            assertPaymentRefused(helper, f, BankResult.INVALID_AMOUNT, () -> CoreApi.money().pay(server, account, -7, reward));
            assertPaymentRefused(helper, f, BankResult.NO_FUNDS, () -> CoreApi.money().charge(server, account, Integer.MAX_VALUE + 1L, fee));
            assertPaymentRefused(helper, f, BankResult.TOO_LARGE, () -> CoreApi.money().pay(server, account, Long.MAX_VALUE, reward));
            assertPaymentRefused(helper, f, BankResult.NO_ACCOUNT, () -> CoreApi.money().pay(server, nobody, 5, reward));
            assertPaymentRefused(helper, f, BankResult.NO_ACCOUNT, () -> CoreApi.money().charge(server, nobody, 5, fee));
            assertPaymentRefused(helper, f, BankResult.NOT_ALLOWED, () -> CoreApi.money().transfer(server, account, account, 5, fee));
            assertPaymentRefused(helper, f, BankResult.NO_FUNDS, () -> CoreApi.money().transfer(server, account, own(other), 301, fee));
            helper.assertValueEqual(CoreApi.money().balance(server, nobody), OptionalLong.empty(), "no balance for no account");

            // A transfer moves money without creating or destroying any.
            helper.assertValueEqual(CoreApi.money().transfer(server, account, own(other), 100, fee), new PaymentResult(BankResult.OK, 100, false),
                    "transferred");
            helper.assertTrue(f.balance() == 200 && other.balance() == 100, "from one to the other");
            helper.assertValueEqual(auditSize(server), lines + 3, "one audit line naming both");
            helper.assertTrue(newestAudit(server).get("target").getAsString().contains(other.player.getUUID().toString()), "the other too");

            // The mod's own routine payments write no line.
            Reason routine = Reason.of(ResourceLocation.fromNamespaceAndPath(CoreModule.MOD_ID, "test_routine"));
            CoreApi.money().pay(server, account, 50, routine);
            CoreApi.money().charge(server, account, 250, Reason.of(ResourceLocation.fromNamespaceAndPath(CoreModule.MOD_ID + "_market", "test")));
            helper.assertValueEqual(f.balance(), 0, "the money moved");
            helper.assertValueEqual(auditSize(server), lines + 3, "but nothing was written");
        } finally {
            f.account().deduct(f.balance());
            other.account().deduct(other.balance());
        }
        helper.succeed();
    }

    /** Owed payments survive a restart, are paid once due, count on the leaderboard, and follow an ended account's owner. */
    @GameTest(template = EMPTY)
    public static void moneyMovesMoreThanAnIntAtOnce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        CoreFixture other = new CoreFixture(helper);
        Reason reason = Reason.of(rl("big"));
        long big = 3L * Integer.MAX_VALUE;
        try {
            helper.assertValueEqual(CoreApi.money().pay(server, own(f), big, reason), new PaymentResult(BankResult.OK, big, false), "paid at once");
            long moved = 2_500_000_000L;
            helper.assertValueEqual(CoreApi.money().transfer(server, own(f), own(other), moved, reason),
                    new PaymentResult(BankResult.OK, moved, false), "transferred at once");
            helper.assertValueEqual(f.account().balance(), big - moved, "taken");
            helper.assertValueEqual(other.account().balance(), moved, "given");

            // Coins worth more than an int come out in one withdrawal, and go back in one deposit.
            BankService.Outcome out = BankService.withdraw(other.player, moved);
            helper.assertTrue(out.ok() && out.amount() == moved, "withdrew: " + out);
            helper.assertValueEqual(other.account().balance(), 0L, "all of it");
            BankService.Outcome in = BankService.depositAll(other.player);
            helper.assertTrue(in.ok() && in.amount() == moved, "deposited: " + in);
            helper.assertValueEqual(other.account().balance(), moved, "all of it back");

            helper.assertValueEqual(CoreApi.money().charge(server, own(other), moved, reason),
                    new PaymentResult(BankResult.OK, moved, false), "charged at once");
            helper.assertValueEqual(other.account().balance(), 0L, "emptied");
        } finally {
            f.account().deduct(f.account().balance());
            other.account().deduct(other.account().balance());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void owedPaymentsArePaidLaterAndCountOnTheLeaderboard(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        UUID id = f.player.getUUID();
        Reason reason = Reason.of(rl("owed"));

        OwedPayments saved = new OwedPayments();
        saved.owe(new OwedPayments.Payment(own(f), 25, reason));
        helper.assertTrue(saved.isDirty(), "owing marks the data to save");
        OwedPayments loaded = OwedPayments.fromTag(saved.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.list(), saved.list(), "owed money survives a restart");
        long tick = server.getTickCount();
        saved.failedAt(tick);
        helper.assertTrue(!saved.retryDue(tick) && saved.retryDue(tick + OwedPayments.RETRY_TICKS), "after a failure it waits");

        // The world's: owed to the player, and to an account that has ended with the player as its last owner.
        UUID team = UUID.randomUUID();
        AccountRegistry.get(server).createNamed(team, id, accountName("gone"));
        AccountRegistry.get(server).end(team, id);
        helper.assertValueEqual(CoreApi.accounts().get(server, new AccountId(team)), Optional.empty(), "an ended account reads as gone");
        helper.assertValueEqual(CoreApi.money().pay(server, new AccountId(team), 5, reason).result(), BankResult.NO_ACCOUNT,
                "and takes no payment");
        OwedPayments world = OwedPayments.get(server);
        OwedPayments.Payment mine = new OwedPayments.Payment(own(f), 70, reason);
        OwedPayments.Payment forTeam = new OwedPayments.Payment(new AccountId(team), 1000, reason);
        world.owe(mine);
        world.owe(forTeam);
        try {
            helper.assertValueEqual(CoreServices.MONEY.owedToPlayers(server).get(id), 1070L, "both count for the player");
            Leaderboard.View view = Leaderboard.viewFor(f.player, uuid -> uuid.equals(id)
                    ? Optional.of(f.player.getGameProfile()) : Optional.empty());
            helper.assertValueEqual(view.ownRank(), 1, "ranked");
            helper.assertValueEqual(view.top().get(0).balance(), 1070L, "the 1070 owed");
            world.retryNowForTesting();
            CoreServices.MONEY.payOwed(server);
            helper.assertTrue(world.list().stream().noneMatch(p -> p == mine || p == forTeam), "paid once due");
            helper.assertValueEqual(f.balance(), 1070, "into the player's account, the team's money too");
        } finally {
            world.paid(mine);
            world.paid(forTeam);
            f.account().deduct(f.balance());
        }
        helper.succeed();
    }

    // ---- Gate rules --------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void gateRulesReplaceAndRemove(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        ResourceLocation id = rl("replaced");
        helper.assertFalse(CoreApi.itemGate().removeRule(rl("never_added")), "an unknown rule isn't removed");
        try {
            CoreApi.itemGate().addRule(id, (player, item) -> item == Items.IRON_INGOT);
            helper.assertTrue(CoreApi.itemGate().isLocked(f.player, Items.IRON_INGOT), "the rule locks iron");
            CoreApi.itemGate().addRule(id, (player, item) -> item == Items.GOLD_INGOT);
            helper.assertFalse(CoreApi.itemGate().isLocked(f.player, Items.IRON_INGOT), "replaced by the same ID");
            helper.assertTrue(CoreApi.itemGate().isLocked(f.player, Items.GOLD_INGOT), "the new rule locks gold");
            helper.assertTrue(CoreApi.itemGate().removeRule(id), "removed");
            helper.assertFalse(CoreApi.itemGate().removeRule(id), "once");
            helper.assertFalse(CoreApi.itemGate().isLocked(f.player, Items.GOLD_INGOT), "gold unlocked again");
        } finally {
            CoreApi.itemGate().removeRule(id);
        }
        helper.succeed();
    }

    /**
     * The gate asks ProgressiveStages exactly when it is loaded. With it, the GameTest worlds' own stage
     * ({@code tools/gametest-config}, which test players never have) locks the dragon egg, and only that; without it,
     * nothing is locked and every stage counts as reached. Either way no check fails along the way.
     */
    @GameTest(template = EMPTY)
    public static void stagesBridgeMatchesTheLoadedMod(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        boolean loaded = ModList.get().isLoaded(StagesBridge.MOD_ID);
        helper.assertTrue(StagesBridge.isAvailable() == loaded, "the bridge is on exactly when ProgressiveStages is loaded");
        if (loaded) {
            helper.assertTrue(CoreApi.itemGate().isLocked(f.player, Items.DRAGON_EGG), "the test stage locks the dragon egg");
            helper.assertFalse(CoreApi.itemGate().isLocked(f.player, Items.IRON_INGOT), "an item no stage needs is open");
            helper.assertFalse(CoreApi.itemGate().hasStage(f.player, "variable_economy_test:gametest"),
                    "the test player lacks the test stage");
            helper.assertFalse(CoreApi.itemGate().hasStage(f.player, "variable_economy_test:no_such_stage"),
                    "a stage that doesn't exist isn't reached");
        } else {
            helper.assertFalse(CoreApi.itemGate().isLocked(f.player, Items.DRAGON_EGG), "nothing is locked");
            helper.assertTrue(CoreApi.itemGate().hasStage(f.player, "any_stage"),
                    "every stage counts as reached without ProgressiveStages");
        }
        helper.assertTrue(StagesBridge.isAvailable() == loaded, "no check failed along the way");
        helper.succeed();
    }

    // ---- /bank admin money -----------------------------------------------------------------------------------

    /**
     * Runs a command at permission 2 (a game master, as the admin commands need) as {@code as}, so {@code @s} names
     * that player. Returns the command's result; {@code output} gets what it printed.
     */
    private static int commandAs(GameTestHelper helper, ServerPlayer as, List<String> output, String command) throws CommandSyntaxException {
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withSource(capture).withPermission(2).withEntity(as);
        return server.getCommands().getDispatcher().execute(command, source);
    }

    @GameTest(template = EMPTY)
    public static void adminMoneyCommandsShowGiveAndTake(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        String target = f.player.getGameProfile().getName() + " (" + f.player.getUUID() + ")";
        List<String> output = new ArrayList<>();
        try {
            f.deposit(40);
            helper.assertValueEqual(commandAs(helper, f.player, output, "bank admin money balance @s[type=player]"), 40, "the balance is returned");
            helper.assertTrue(output.stream().anyMatch(line -> line.contains("40")), "and shown: " + output);

            int lines = auditSize(server);
            helper.assertValueEqual(commandAs(helper, f.player, output, "bank admin money give @s[type=player] 100"), 100, "the amount given");
            helper.assertValueEqual(f.balance(), 140, "given");
            helper.assertValueEqual(auditSize(server), lines + 1, "one audit line");
            JsonObject give = newestAudit(server);
            helper.assertValueEqual(give.get("action").getAsString(), "money give", "action");
            helper.assertValueEqual(give.get("target").getAsString(), target, "target");
            helper.assertValueEqual(give.get("result").getAsString(), "ok", "result");
            helper.assertValueEqual(give.get("before").getAsLong(), 40L, "before");
            helper.assertValueEqual(give.get("after").getAsLong(), 140L, "after");

            helper.assertValueEqual(commandAs(helper, f.player, output, "bank admin money take @s[type=player] 90"), 90, "the amount taken");
            helper.assertValueEqual(f.balance(), 50, "taken");
            JsonObject take = newestAudit(server);
            helper.assertValueEqual(take.get("action").getAsString(), "money take", "action");
            helper.assertTrue(take.get("before").getAsLong() == 140 && take.get("after").getAsLong() == 50, "before and after: " + take);
            helper.assertValueEqual(auditSize(server), lines + 2, "one more audit line");

            output.clear();
            helper.assertValueEqual(commandAs(helper, f.player, output, "bank admin money take @s[type=player] 51"), 0, "can't take more than there is");
            helper.assertValueEqual(f.balance(), 50, "nothing taken");
            helper.assertValueEqual(auditSize(server), lines + 2, "nothing logged");
            helper.assertTrue(output.stream().anyMatch(line -> line.contains("only")), "says why: " + output);

            boolean refused = false;
            try {
                commandAs(helper, f.player, output, "bank admin money give @s[type=player] 0");
            } catch (CommandSyntaxException e) {
                refused = true;
            }
            helper.assertTrue(refused, "an amount below 1 isn't accepted");
            helper.assertValueEqual(f.balance(), 50, "nothing given");

            helper.assertValueEqual(commandAs(helper, f.player, output, "bank admin money take @s[type=player] 50"), 50, "down to zero");
            helper.assertValueEqual(commandAs(helper, f.player, output, "bank admin money balance @s[type=player]"), 0, "empty");
        } catch (CommandSyntaxException e) {
            helper.fail("a command was rejected: " + e.getMessage());
        } finally {
            f.account().deduct(f.balance());
        }
        helper.succeed();
    }

    /** Below permission 2 the money commands don't exist. */
    @GameTest(template = EMPTY)
    public static void adminMoneyCommandsNeedPermission(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        CommandSourceStack source = server.createCommandSourceStack().withPermission(0).withEntity(f.player);
        boolean refused = false;
        try {
            server.getCommands().getDispatcher().execute("bank admin money give @s[type=player] 100", source);
        } catch (CommandSyntaxException e) {
            refused = true;
        }
        helper.assertTrue(refused, "refused below permission 2");
        helper.assertValueEqual(f.balance(), 0, "nothing given");
        helper.succeed();
    }
}
