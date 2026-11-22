package variableeconomy.core.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.coin.Coin;
import variableeconomy.core.coin.Coins;
import variableeconomy.core.menu.BankMenu;
import variableeconomy.core.network.AccountRequests;
import variableeconomy.core.network.BankPayloads;
import variableeconomy.core.network.BankPayloads.AccountAction;
import variableeconomy.core.network.BankSync;
import variableeconomy.core.service.BankService;
import variableeconomy.core.service.CoreServices;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * The bank screen's server side: which account it shows, coins going in and out of it by role, and the Accounts
 * screen's requests, checked as {@code /bank account} checks them.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BankScreenTests {
    private static final String KEY = "command.variable_economy.account.";

    private BankScreenTests() {
    }

    private static BankPayloads.AccountOutcome ask(CoreFixture f, AccountAction action, AccountId account, String text, UUID player,
                                                   AccountRole role) {
        return AccountRequests.handle(new BankPayloads.AccountRequest(action, account.value(), text, player, role), f.player);
    }

    @GameTest(template = EMPTY)
    public static void screenRequestsNeedTheBankOpen(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        String name = accountName("closed");
        withAccounts(() -> {
            BankSync.handleAccountRequest(new BankPayloads.AccountRequest(AccountAction.CREATE, new UUID(0, 0), name, new UUID(0, 0),
                    AccountRole.OWNER), f.player);
            helper.assertTrue(variableeconomy.core.data.AccountRegistry.get(server).namedByName(name).isEmpty(),
                    "no account is opened from a screen that isn't open");
            AccountId mine = CoreApi.accounts().create(server, f.player.getUUID(), name).account().orElseThrow();
            BankSync.handleShowAccount(new BankPayloads.ShowAccountRequest(mine.value()), f.player);
            helper.assertValueEqual(BankSync.pickedForTesting(f.player), Optional.empty(), "nor is an account shown");
            CoreApi.accounts().delete(server, mine);
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theScreenShowsOnlyAccountsThePlayerMayUse(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        CoreFixture other = new CoreFixture(helper);
        withAccounts(() -> {
            AccountId theirs = CoreApi.accounts().create(server, other.player.getUUID(), accountName("theirs")).account().orElseThrow();
            AccountId mine = CoreApi.accounts().create(server, f.player.getUUID(), accountName("mine")).account().orElseThrow();
            f.player.containerMenu = BankMenu.forCommand();
            try {
                helper.assertFalse(BankSync.show(f.player, theirs), "someone else's account can't be shown");
                BankPayloads.BankState state = BankSync.stateFor(f.player);
                helper.assertValueEqual(state.shown(), f.player.getUUID(), "the active (personal) account is shown");
                helper.assertValueEqual(state.accounts().size(), 2, "personal and the player's own named account");
                helper.assertTrue(state.accounts().stream().noneMatch(line -> line.id().equals(theirs.value())), "not theirs");

                helper.assertTrue(BankSync.show(f.player, mine), "their own named account can");
                state = BankSync.stateFor(f.player);
                helper.assertValueEqual(state.shown(), mine.value(), "and is shown");
                helper.assertValueEqual(state.active(), f.player.getUUID(), "without changing the active account");
                helper.assertValueEqual(state.members().size(), 1, "its members are listed: the owner");

                CoreApi.accounts().delete(server, mine);
                helper.assertValueEqual(BankSync.stateFor(f.player).shown(), f.player.getUUID(), "once it's gone, the active one again");
            } finally {
                f.player.containerMenu = f.player.inventoryMenu;
                CoreApi.accounts().delete(server, theirs);
            }
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theOwnerSeesAndWithdrawsInvitations(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture owner = new CoreFixture(helper);
        CoreFixture member = new CoreFixture(helper);
        CoreFixture invitee = new CoreFixture(helper);
        withAccounts(() -> {
            AccountId account = CoreApi.accounts().create(server, owner.player.getUUID(), accountName("scr")).account().orElseThrow();
            CoreApi.accounts().setMember(server, account, member.player.getUUID(), AccountRole.MEMBER);
            CoreServices.ACCOUNTS.invite(server, account, invitee.player.getUUID(), AccountRole.DEPOSIT_ONLY);
            try {
                BankSync.show(owner.player, account);
                List<BankPayloads.MemberLine> lines = BankSync.stateFor(owner.player).members();
                helper.assertValueEqual(lines.size(), 3, "the owner sees both members and the invitation: " + lines);
                BankPayloads.MemberLine invited = lines.get(2);
                helper.assertTrue(invited.invited() && invited.player().equals(invitee.player.getUUID())
                        && invited.role() == AccountRole.DEPOSIT_ONLY, "the invitation comes last, with the role offered: " + invited);
                helper.assertTrue(lines.subList(0, 2).stream().noneMatch(BankPayloads.MemberLine::invited), "members aren't invitations");

                BankSync.show(member.player, account);
                helper.assertValueEqual(BankSync.stateFor(member.player).members().size(), 2, "a member doesn't see invitations");

                BankPayloads.AccountOutcome notOwner = AccountRequests.handle(new BankPayloads.AccountRequest(
                        BankPayloads.AccountAction.REMOVE, account.value(), "", invitee.player.getUUID(), AccountRole.MEMBER), member.player);
                helper.assertFalse(notOwner.ok(), "only the owner withdraws an invitation");
                BankPayloads.AccountOutcome withdrawn = AccountRequests.handle(new BankPayloads.AccountRequest(
                        BankPayloads.AccountAction.REMOVE, account.value(), "", invitee.player.getUUID(), AccountRole.MEMBER), owner.player);
                helper.assertTrue(withdrawn.ok() && withdrawn.key().endsWith("done.invite_cancelled"), "the owner withdraws it: " + withdrawn);
                helper.assertValueEqual(BankSync.stateFor(owner.player).members().size(), 2, "and it's gone from the list");
                BankPayloads.AccountOutcome again = AccountRequests.handle(new BankPayloads.AccountRequest(
                        BankPayloads.AccountAction.REMOVE, account.value(), "", invitee.player.getUUID(), AccountRole.MEMBER), owner.player);
                helper.assertTrue(!again.ok() && again.key().endsWith("result.not_a_member"), "with none left, as before: " + again);
            } finally {
                CoreServices.ACCOUNTS.cancelInvite(account, invitee.player.getUUID());
                CoreApi.accounts().removeMember(server, account, member.player.getUUID());
                CoreApi.accounts().delete(server, account);
            }
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void depositOnlyMembersPayInButDontTakeOut(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture owner = new CoreFixture(helper);
        CoreFixture payer = new CoreFixture(helper);
        withAccounts(() -> {
            AccountId account = CoreApi.accounts().create(server, owner.player.getUUID(), accountName("tip")).account().orElseThrow();
            CoreApi.accounts().setMember(server, account, payer.player.getUUID(), AccountRole.DEPOSIT_ONLY);
            try {
                int bit = Coins.denominations().stream().filter(d -> d.item() == Coins.item(Coin.BIT)).findFirst().orElseThrow().value();
                payer.give(new ItemStack(Coins.item(Coin.BIT), 5));
                BankService.Outcome in = BankService.depositAll(payer.player, account);
                helper.assertTrue(in.ok() && in.amount() == 5L * bit, "a deposit-only member pays coins in: " + in);
                helper.assertValueEqual(CoreApi.money().balance(server, account).orElseThrow(), 5L * bit, "into the account");
                payer.assertRejectedUnchanged(BankService.Result.NOT_ALLOWED, () -> BankService.withdraw(payer.player, 1, account));
                payer.assertRejectedUnchanged(BankService.Result.NOT_ALLOWED,
                        () -> BankService.withdrawCoins(payer.player, Coins.item(Coin.BIT), 1, account));
                helper.assertValueEqual(CoreApi.money().balance(server, account).orElseThrow(), 5L * bit, "nothing came out");

                CoreFixture stranger = new CoreFixture(helper);
                stranger.give(new ItemStack(Coins.item(Coin.BIT), 1));
                stranger.assertRejectedUnchanged(BankService.Result.NOT_ALLOWED, () -> BankService.depositAll(stranger.player, account));
                helper.assertValueEqual(stranger.count(Coins.item(Coin.BIT)), 1L, "a stranger keeps their coin");

                BankService.Outcome out = BankService.withdraw(owner.player, 5L * bit, account);
                helper.assertTrue(out.ok(), "the owner takes it out: " + out);
            } finally {
                CoreApi.accounts().removeMember(server, account, payer.player.getUUID());
                CoreApi.accounts().delete(server, account);
            }
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void accountsScreenRequestsFollowTheRules(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture owner = new CoreFixture(helper);
        CoreFixture member = new CoreFixture(helper);
        String name = accountName("scr");
        UUID none = new UUID(0, 0);
        withAccounts(() -> {
            BankPayloads.AccountOutcome created = ask(owner, AccountAction.CREATE, new AccountId(none), name, none, AccountRole.OWNER);
            helper.assertTrue(created.ok(), "opened: " + created);
            AccountId account = new AccountId(variableeconomy.core.data.AccountRegistry.get(server).namedByName(name).orElseThrow());
            helper.assertValueEqual(ask(owner, AccountAction.INVITE, account, "nobody-" + UUID.randomUUID().toString().substring(0, 6),
                    none, AccountRole.MEMBER).key(), KEY + "unknown_player", "only players the server has seen");
            helper.assertValueEqual(ask(member, AccountAction.ACCEPT, account, "", account.value(), AccountRole.MEMBER).key(),
                    KEY + "not_invited", "no invitation yet");
            helper.assertValueEqual(ask(member, AccountAction.DELETE, account, "", account.value(), AccountRole.MEMBER).key(),
                    KEY + "not_yours", "a stranger can't touch it");

            variableeconomy.core.service.CoreServices.ACCOUNTS.invite(server, account, member.player.getUUID(), AccountRole.MEMBER);
            helper.assertTrue(ask(member, AccountAction.ACCEPT, account, "", account.value(), AccountRole.MEMBER).ok(), "accepted");
            helper.assertValueEqual(ask(member, AccountAction.DELETE, account, "", account.value(), AccountRole.MEMBER).key(),
                    KEY + "owner_only", "a member can't delete it");
            helper.assertValueEqual(ask(member, AccountAction.SET_ROLE, account, "", owner.player.getUUID(), AccountRole.DEPOSIT_ONLY).key(),
                    KEY + "owner_only", "nor change roles");
            helper.assertTrue(ask(member, AccountAction.USE, account, "", account.value(), AccountRole.MEMBER).ok(), "a member may use it");
            helper.assertValueEqual(CoreApi.accounts().active(member.player), account, "it's their active account");

            helper.assertValueEqual(ask(owner, AccountAction.SET_ROLE, account, "", owner.player.getUUID(), AccountRole.MEMBER).key(),
                    KEY + "own_role", "the owner can't change their own role");
            helper.assertTrue(ask(owner, AccountAction.SET_ROLE, account, "", member.player.getUUID(), AccountRole.DEPOSIT_ONLY).ok(),
                    "the owner can change the member's");
            helper.assertValueEqual(CoreApi.accounts().active(member.player), CoreApi.accounts().personal(member.player.getUUID()),
                    "deposit-only, they're back on their own account");
            helper.assertTrue(ask(owner, AccountAction.REMOVE, account, "", member.player.getUUID(), AccountRole.MEMBER).ok(), "removed");
            helper.assertValueEqual(CoreApi.accounts().role(server, account, member.player.getUUID()), Optional.empty(), "gone");
            helper.assertValueEqual(ask(owner, AccountAction.LEAVE, account, "", account.value(), AccountRole.MEMBER).key(),
                    KEY + "last_member", "the last member deletes rather than leaves");
            helper.assertTrue(ask(owner, AccountAction.DELETE, account, "", account.value(), AccountRole.MEMBER).ok(), "deleted");
            helper.assertValueEqual(CoreApi.accounts().get(server, account), Optional.empty(), "and gone");
            helper.assertValueEqual(CoreApi.accounts().delete(server, account), BankResult.NO_ACCOUNT, "for good");
        });
        helper.succeed();
    }
}
