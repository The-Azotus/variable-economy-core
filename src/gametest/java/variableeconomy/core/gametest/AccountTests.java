package variableeconomy.core.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.core.CoreConfig;
import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountMember;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.Reason;
import variableeconomy.core.api.event.AccountEvent;
import variableeconomy.core.bank.OwnBank;
import variableeconomy.core.data.AccountRegistry;
import variableeconomy.core.data.OwedPayments;
import variableeconomy.core.service.AccountsService;
import variableeconomy.core.service.CoreServices;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * Extra, shared and party accounts: the server's rules for them, roles and ownership, invitations, the active
 * account, {@code /bank account}, and what is saved.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AccountTests {
    private static final String KEY = "command.variable_economy.account.";

    private AccountTests() {
    }

    private static AccountId personal(CoreFixture f) {
        return CoreApi.accounts().personal(f.player.getUUID());
    }

    @GameTest(template = EMPTY)
    public static void extraAccountsFollowTheServersRules(GameTestHelper helper) {
        listenFor(AccountEvent.Created.class, AccountEvent.Deleted.class);
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        UUID owner = f.player.getUUID();
        helper.assertValueEqual(CoreApi.accounts().create(server, owner, accountName("off")).result(), BankResult.DISABLED,
                "extra accounts are off by default");
        withAccounts(() -> {
            helper.assertValueEqual(CoreApi.accounts().create(server, owner, "two words").result(), BankResult.INVALID_NAME, "one word");
            helper.assertValueEqual(CoreApi.accounts().create(server, owner, "").result(), BankResult.INVALID_NAME, "not blank");
            helper.assertValueEqual(CoreApi.accounts().create(server, owner, "a".repeat(25)).result(), BankResult.INVALID_NAME, "at most 24");
            helper.assertValueEqual(CoreApi.accounts().create(server, owner, "Personal").result(), BankResult.NAME_TAKEN,
                    "'personal' names a player's own account in commands");
            helper.assertValueEqual(CoreApi.accounts().create(server, owner, "party").result(), BankResult.NAME_TAKEN, "and 'party' their party's");

            String name = accountName("x");
            List<Event> events = recordEvents(() -> helper.assertTrue(CoreApi.accounts().create(server, owner, name).ok(), "created"));
            List<AccountEvent.Created> created = only(events, AccountEvent.Created.class);
            helper.assertValueEqual(created.size(), 1, "one Created event");
            AccountId first = created.get(0).getAccount();
            helper.assertValueEqual(created.get(0).getKind(), AccountKind.NAMED, "a named account");
            helper.assertValueEqual(CoreApi.accounts().get(server, first).orElseThrow().members(),
                    List.of(new AccountMember(owner, AccountRole.OWNER)), "its owner is its only member");
            helper.assertValueEqual(CoreApi.accounts().create(server, UUID.randomUUID(), name.toUpperCase(java.util.Locale.ROOT)).result(),
                    BankResult.NAME_TAKEN, "names are unique, ignoring case");

            // The default cap is three accounts, the personal one included.
            AccountId second = CoreApi.accounts().create(server, owner, accountName("y")).account().orElseThrow();
            helper.assertValueEqual(CoreApi.accounts().create(server, owner, accountName("z")).result(), BankResult.LIMIT_REACHED,
                    "a third named account is one too many");
            helper.assertValueEqual(CoreApi.accounts().accessibleBy(server, owner).size(), 3, "personal and two named");
            helper.assertValueEqual(CoreApi.accounts().accessibleBy(server, owner).get(0).kind(), AccountKind.PERSONAL, "personal first");

            // Deleting needs an empty account: no balance, nothing owed, nothing held by an add-on.
            Reason reason = Reason.of(rl("accounts"));
            CoreApi.money().pay(server, first, 10, reason);
            helper.assertValueEqual(CoreApi.accounts().delete(server, first), BankResult.NOT_EMPTY, "it holds money");
            CoreApi.money().charge(server, first, 10, reason);
            OwedPayments.Payment owed = new OwedPayments.Payment(first, 5, reason);
            OwedPayments.get(server).owe(owed);
            helper.assertValueEqual(CoreApi.accounts().delete(server, first), BankResult.NOT_EMPTY, "money is owed to it");
            OwedPayments.get(server).paid(owed);
            ResourceLocation holder = rl("holds_for_account");
            CoreApi.netWorth().register(holder, s -> Map.of(first, 7L));
            try {
                helper.assertValueEqual(CoreApi.accounts().delete(server, first), BankResult.NOT_EMPTY, "an add-on holds money for it");
            } finally {
                CoreApi.netWorth().unregister(holder);
            }
            helper.assertValueEqual(CoreApi.accounts().delete(server, personal(f)), BankResult.PERSONAL_ACCOUNT, "never the personal one");
            List<Event> deletion = recordEvents(() -> helper.assertValueEqual(CoreApi.accounts().delete(server, first), BankResult.OK, "deleted"));
            helper.assertValueEqual(only(deletion, AccountEvent.Deleted.class).size(), 1, "one Deleted event");
            helper.assertValueEqual(CoreApi.accounts().get(server, first), Optional.empty(), "gone");
            helper.assertValueEqual(CoreApi.accounts().delete(server, first), BankResult.NO_ACCOUNT, "and stays gone");
            helper.assertValueEqual(CoreApi.money().pay(server, first, 1, reason).result(), BankResult.NO_ACCOUNT, "taking no money");
            helper.assertTrue(CoreApi.accounts().create(server, owner, name).ok(), "its name is free again");
            CoreApi.accounts().delete(server, second);
            CoreApi.accounts().accessibleBy(server, owner).stream().filter(a -> a.kind() == AccountKind.NAMED)
                    .forEach(a -> CoreApi.accounts().delete(server, a.id()));
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void rolesDecideWhoSpendsAndOwnershipPassesOn(GameTestHelper helper) {
        listenFor(AccountEvent.MemberAdded.class, AccountEvent.MemberRemoved.class, AccountEvent.RoleChanged.class);
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture a = new CoreFixture(helper);
        CoreFixture b = new CoreFixture(helper);
        CoreFixture c = new CoreFixture(helper);
        UUID ua = a.player.getUUID();
        UUID ub = b.player.getUUID();
        UUID uc = c.player.getUUID();
        withAccounts(() -> {
            AccountId id = CoreApi.accounts().create(server, ua, accountName("r")).account().orElseThrow();
            CoreConfig.SHARED_ENABLED.set(false);
            helper.assertValueEqual(CoreApi.accounts().setMember(server, id, ub, AccountRole.MEMBER), BankResult.DISABLED,
                    "no members while shared accounts are off");
            CoreConfig.SHARED_ENABLED.set(true);

            List<Event> joined = recordEvents(() -> {
                helper.assertValueEqual(CoreApi.accounts().setMember(server, id, ub, AccountRole.MEMBER), BankResult.OK, "B joins");
                helper.assertValueEqual(CoreApi.accounts().setMember(server, id, uc, AccountRole.DEPOSIT_ONLY), BankResult.OK, "C joins");
            });
            helper.assertValueEqual(only(joined, AccountEvent.MemberAdded.class).size(), 2, "two MemberAdded events");
            helper.assertValueEqual(CoreApi.accounts().get(server, id).orElseThrow().members(), List.of(
                    new AccountMember(ua, AccountRole.OWNER), new AccountMember(ub, AccountRole.MEMBER),
                    new AccountMember(uc, AccountRole.DEPOSIT_ONLY)), "in the order they joined");
            helper.assertValueEqual(CoreApi.accounts().setMember(server, id, ub, AccountRole.MEMBER), BankResult.OK, "the same role is no change");

            helper.assertValueEqual(CoreApi.accounts().setActive(c.player, id), BankResult.NOT_ALLOWED, "deposit-only can't spend from it");
            helper.assertValueEqual(CoreApi.accounts().setActive(b.player, id), BankResult.OK, "a member can");
            helper.assertValueEqual(CoreApi.accounts().setMember(server, id, ub, AccountRole.DEPOSIT_ONLY), BankResult.OK, "B is demoted");
            helper.assertValueEqual(AccountRegistry.get(server).chosenActive(ub), Optional.empty(), "at once, not only when next read");
            helper.assertValueEqual(CoreApi.accounts().active(b.player), personal(b), "so B is back on their own account");
            CoreApi.accounts().setMember(server, id, ub, AccountRole.MEMBER);

            helper.assertValueEqual(CoreApi.accounts().setMember(server, id, ua, AccountRole.MEMBER), BankResult.NOT_ALLOWED,
                    "the owner can't step down without naming a new owner");
            int max = CoreConfig.MAX_MEMBERS.get();
            CoreConfig.MAX_MEMBERS.set(3);
            try {
                helper.assertValueEqual(CoreApi.accounts().setMember(server, id, UUID.randomUUID(), AccountRole.MEMBER),
                        BankResult.LIMIT_REACHED, "three members is the cap here, owner included");
            } finally {
                CoreConfig.MAX_MEMBERS.set(max);
            }

            // Handing over: B owns it, A is a member.
            List<Event> handed = recordEvents(() ->
                    helper.assertValueEqual(CoreApi.accounts().setMember(server, id, ub, AccountRole.OWNER), BankResult.OK, "handed to B"));
            helper.assertValueEqual(only(handed, AccountEvent.RoleChanged.class).size(), 2, "B up, A down");
            helper.assertValueEqual(CoreApi.accounts().get(server, id).orElseThrow().owner(), ub, "B owns it");
            helper.assertValueEqual(CoreApi.accounts().role(server, id, ua), Optional.of(AccountRole.MEMBER), "A is a member");

            // B leaves: the longest-standing member left, A, owns it again.
            helper.assertValueEqual(CoreApi.accounts().setActive(b.player, id), BankResult.OK, "B was using it");
            List<Event> left = recordEvents(() ->
                    helper.assertValueEqual(CoreApi.accounts().removeMember(server, id, ub), BankResult.OK, "B leaves"));
            helper.assertValueEqual(only(left, AccountEvent.MemberRemoved.class).size(), 1, "one MemberRemoved event");
            helper.assertValueEqual(CoreApi.accounts().get(server, id).orElseThrow().owner(), ua, "A, who joined first, owns it");
            helper.assertValueEqual(CoreApi.accounts().active(b.player), personal(b), "B is back on their own account");
            helper.assertValueEqual(CoreApi.accounts().removeMember(server, id, ub), BankResult.NOT_A_MEMBER, "not twice");

            helper.assertValueEqual(CoreApi.accounts().removeMember(server, id, uc), BankResult.OK, "C leaves");
            helper.assertValueEqual(CoreApi.accounts().removeMember(server, id, ua), BankResult.NOT_ALLOWED,
                    "the last member must delete it instead");
            helper.assertValueEqual(CoreApi.accounts().delete(server, id), BankResult.OK, "deleted");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankAccountCommandsManageAccounts(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture a = new CoreFixture(helper);
        CoreFixture b = new CoreFixture(helper);
        String name = accountName("cmd");
        helper.assertValueEqual(commandKeysAs(helper, a.player, "bank account create " + name), List.of(KEY + "result.disabled"),
                "off by default");
        withAccounts(() -> {
            helper.assertValueEqual(commandKeysAs(helper, a.player, "bank account create " + name), List.of(KEY + "created"), "created");
            helper.assertValueEqual(commandKeysAs(helper, a.player, "bank account create personal"), List.of(KEY + "result.name_taken"),
                    "'personal' is reserved");
            List<String> listed = commandKeysAs(helper, a.player, "bank account list");
            helper.assertValueEqual(listed.get(0), KEY + "list.header", "a header");
            helper.assertValueEqual(listed.size(), 3, "then personal and the new account: " + listed);
            helper.assertValueEqual(commandKeysAs(helper, a.player, "bank account info " + name), List.of(KEY + "info"), "info");
            helper.assertValueEqual(commandKeysAs(helper, a.player, "bank account info nothing"), List.of(KEY + "not_yours"), "no such account");

            AccountId id = new AccountId(AccountRegistry.get(server).namedByName(name).orElseThrow());
            helper.assertValueEqual(commandKeysAs(helper, b.player, "bank account join " + name), List.of(KEY + "not_invited"), "no invitation");
            // The invite command takes an online player; test players aren't, so invite through the service.
            helper.assertValueEqual(CoreServices.ACCOUNTS.invite(server, id, b.player.getUUID(), AccountRole.OWNER), BankResult.NOT_ALLOWED,
                    "nobody is invited as owner");
            CoreServices.ACCOUNTS.invite(server, id, b.player.getUUID(), AccountRole.MEMBER);
            helper.assertValueEqual(commandKeysAs(helper, b.player, "bank account decline " + name), List.of(KEY + "declined"), "declined");
            helper.assertValueEqual(commandKeysAs(helper, b.player, "bank account join " + name), List.of(KEY + "not_invited"), "and gone");
            CoreServices.ACCOUNTS.invite(server, id, b.player.getUUID(), AccountRole.MEMBER);
            helper.assertValueEqual(commandKeysAs(helper, b.player, "bank account join " + name), List.of(KEY + "joined"), "joined");
            helper.assertValueEqual(CoreApi.accounts().role(server, id, b.player.getUUID()), Optional.of(AccountRole.MEMBER), "as a member");

            helper.assertValueEqual(commandKeysAs(helper, b.player, "bank account delete " + name), List.of(KEY + "owner_only"), "owner only");
            helper.assertValueEqual(commandKeysAs(helper, b.player, "bank account use " + name), List.of(KEY + "using"), "B uses it");
            helper.assertValueEqual(CoreApi.accounts().active(b.player), id, "it's active");
            helper.assertValueEqual(commandKeysAs(helper, b.player, "bank account use personal"), List.of(KEY + "using"), "and back");
            helper.assertValueEqual(CoreApi.accounts().active(b.player), personal(b), "on their own");
            helper.assertValueEqual(commandKeysAs(helper, b.player, "bank account leave " + name), List.of(KEY + "left"), "B leaves");
            helper.assertValueEqual(commandKeysAs(helper, a.player, "bank account leave " + name), List.of(KEY + "last_member"),
                    "the last member can't leave");
            helper.assertValueEqual(commandKeysAs(helper, a.player, "bank account leave personal"), List.of(KEY + "not_yours"),
                    "nobody leaves a personal account");
            helper.assertValueEqual(commandKeysAs(helper, a.player, "bank account delete " + name), List.of(KEY + "deleted"), "deleted");
            helper.assertValueEqual(commandKeysAs(helper, a.player, "bank account use " + name), List.of(KEY + "not_yours"), "gone");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownersWithdrawInvitationsAndNewOwnersStartAfresh(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture a = new CoreFixture(helper);
        CoreFixture b = new CoreFixture(helper);
        CoreFixture c = new CoreFixture(helper);
        CoreFixture d = new CoreFixture(helper);
        UUID ub = b.player.getUUID();
        UUID uc = c.player.getUUID();
        UUID ud = d.player.getUUID();
        withAccounts(() -> {
            String name = accountName("inv");
            AccountId id = CoreApi.accounts().create(server, a.player.getUUID(), name).account().orElseThrow();
            try {
                // Withdrawn with "remove": the invitation is gone, and joining finds none.
                CoreServices.ACCOUNTS.invite(server, id, ub, AccountRole.MEMBER);
                helper.assertValueEqual(CoreServices.ACCOUNTS.invitedTo(id), Map.of(ub, AccountRole.MEMBER), "B is invited");
                // The command and the screen withdraw it the same way (BankScreenTests); test players aren't online,
                // so the command can't name them.
                helper.assertTrue(CoreServices.ACCOUNTS.cancelInvite(id, ub), "the owner withdraws it");
                helper.assertValueEqual(CoreServices.ACCOUNTS.invitedTo(id), Map.of(), "no invitation left");
                helper.assertValueEqual(CoreServices.ACCOUNTS.accept(server, ub, id), BankResult.NOT_ALLOWED, "B can't join");
                helper.assertFalse(CoreServices.ACCOUNTS.cancelInvite(id, ub), "there's nothing left to withdraw");

                // A new owner didn't offer the invitations still open: handing over ends them.
                CoreApi.accounts().setMember(server, id, uc, AccountRole.MEMBER);
                CoreServices.ACCOUNTS.invite(server, id, ub, AccountRole.MEMBER);
                helper.assertValueEqual(CoreApi.accounts().setMember(server, id, uc, AccountRole.OWNER), BankResult.OK, "C becomes owner");
                helper.assertValueEqual(CoreServices.ACCOUNTS.invitedTo(id), Map.of(), "B's invitation ended with the handover");

                // So does the owner leaving.
                CoreServices.ACCOUNTS.invite(server, id, ud, AccountRole.DEPOSIT_ONLY);
                helper.assertValueEqual(CoreApi.accounts().removeMember(server, id, uc), BankResult.OK, "C leaves; A inherits");
                helper.assertValueEqual(CoreServices.ACCOUNTS.invitedTo(id), Map.of(), "D's invitation ended too");
                helper.assertValueEqual(CoreServices.ACCOUNTS.accept(server, ud, id), BankResult.NOT_ALLOWED, "D can't join");
            } finally {
                CoreServices.ACCOUNTS.cancelInvite(id, ub);
                CoreServices.ACCOUNTS.cancelInvite(id, ud);
                CoreApi.accounts().accessibleBy(server, a.player.getUUID()).stream().filter(x -> x.id().equals(id)).findFirst()
                        .ifPresent(info -> info.members().stream().filter(m -> m.role() != AccountRole.OWNER)
                                .forEach(m -> CoreApi.accounts().removeMember(server, id, m.player())));
                CoreApi.accounts().delete(server, id);
            }
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void accountsAndActiveChoicesSurviveASave(GameTestHelper helper) {
        AccountRegistry registry = new AccountRegistry();
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID named = UUID.randomUUID();
        UUID party = UUID.randomUUID();
        UUID partyAccount = UUID.randomUUID();
        UUID ended = UUID.randomUUID();
        registry.registerPersonal(owner);
        registry.createNamed(named, owner, "Savings");
        registry.setMembers(named, owner, List.of(new AccountMember(owner, AccountRole.OWNER), new AccountMember(member, AccountRole.DEPOSIT_ONLY)));
        registry.createParty(partyAccount, party, owner, "Crew");
        registry.createNamed(ended, owner, "Old");
        registry.end(ended, member);
        registry.setActive(owner, named);

        AccountRegistry loaded = AccountRegistry.fromTag(registry.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        for (UUID id : List.of(owner, named, partyAccount, ended)) {
            helper.assertValueEqual(loaded.entry(id), registry.entry(id), "account " + id + " survives a save");
        }
        helper.assertValueEqual(loaded.partyAccount(party), Optional.of(partyAccount), "a party finds its account");
        helper.assertValueEqual(loaded.namedByName("savings"), Optional.of(named), "named accounts by name");
        helper.assertValueEqual(loaded.namedByName("Old"), Optional.empty(), "but not ended ones");
        helper.assertValueEqual(loaded.byName("crew"), Optional.of(partyAccount), "admins find party accounts by name too");
        helper.assertValueEqual(loaded.byName("SAVINGS"), Optional.of(named), "named ones first");
        helper.assertValueEqual(loaded.chosenActive(owner), Optional.of(named), "the active choice survives too");
        helper.assertValueEqual(loaded.entry(ended).orElseThrow().owner(), member, "an ended account keeps its last owner");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void partyAccountsEndWhenTurnedOff(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        AccountRegistry registry = AccountRegistry.get(server);
        // A party account whose party this test makes up: FTB Teams parties can't be made from a test.
        UUID party = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        registry.createParty(account, party, f.player.getUUID(), accountName("p"));
        OwnBank.get(server).accountOf(account).deposit(500);
        boolean on = CoreConfig.FTB_PARTY_ACCOUNT.get();
        try {
            CoreConfig.FTB_PARTY_ACCOUNT.set(true);
            CoreServices.ACCOUNTS.endPartyAccountsIfOff(server);
            helper.assertValueEqual(registry.entry(account).orElseThrow().ended(), !AccountsService.partyAccountsOn(),
                    "while parties have accounts, theirs stay (without FTB Teams, none do)");

            CoreConfig.FTB_PARTY_ACCOUNT.set(false);
            CoreServices.ACCOUNTS.endPartyAccountsIfOff(server);
            helper.assertTrue(registry.entry(account).orElseThrow().ended(), "turned off, the account ends");
            helper.assertValueEqual(f.balance(), 500, "and its money goes to the party's owner");
            helper.assertValueEqual(registry.partyAccount(party), Optional.empty(), "the party has no account now");
        } finally {
            CoreConfig.FTB_PARTY_ACCOUNT.set(on);
            CoreServices.ACCOUNTS.closeAccount(server, new AccountId(account), f.player.getUUID());
            f.account().deduct(f.balance());
        }
        helper.succeed();
    }
}
