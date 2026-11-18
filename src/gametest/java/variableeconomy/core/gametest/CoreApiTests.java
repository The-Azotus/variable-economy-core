package variableeconomy.core.gametest;

import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountInfo;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountMember;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.Reason;
import variableeconomy.core.api.event.AccountEvent;
import variableeconomy.core.api.event.BalanceChangedEvent;
import variableeconomy.core.api.event.ItemGateChangedEvent;
import variableeconomy.core.service.CoreServices;
import variableeconomy.core.service.Leaderboard;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * Core's API: accounts, balance events, item gate events, net worth and player items.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CoreApiTests {
    private CoreApiTests() {
    }


    // ---- Event recorder: always on, read by account or player, since tests run side by side -------------------

    private static boolean listening;
    private static final List<Event> recorded = new ArrayList<>();

    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        for (Class<? extends Event> type : List.of(BalanceChangedEvent.class, ItemGateChangedEvent.class, AccountEvent.Created.class,
                AccountEvent.Deleted.class, AccountEvent.MemberAdded.class, AccountEvent.MemberRemoved.class, AccountEvent.RoleChanged.class)) {
            record(type);
        }
    }

    private static <T extends Event> void record(Class<T> type) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, type, event -> {
            synchronized (recorded) {
                recorded.add(event);
                if (recorded.size() > 10_000) {
                    recorded.subList(0, 5_000).clear();
                }
            }
        });
    }

    private static <T extends Event> List<T> recorded(Class<T> type) {
        synchronized (recorded) {
            return recorded.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    private static List<BalanceChangedEvent> balanceEvents(AccountId account) {
        return recorded(BalanceChangedEvent.class).stream().filter(e -> e.getAccount().equals(account)).toList();
    }

    private static Reason reason(String path) {
        return Reason.of(rl(path));
    }

    // ---- Balance events ----------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void balanceEventsArePostedOncePerAccountAtTheEndOfTheTick(GameTestHelper helper) {
        listen();
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        AccountId account = CoreApi.accounts().personal(f.player.getUUID());
        Reason in = reason("balance_in");
        Reason out = reason("balance_out");
        CoreApi.money().pay(server, account, 100, in);
        CoreApi.money().charge(server, account, 30, out);
        helper.assertValueEqual(balanceEvents(account), List.of(), "nothing yet: posted at the end of the tick");
        helper.runAfterDelay(1, () -> {
            List<BalanceChangedEvent> events = balanceEvents(account);
            helper.assertValueEqual(events.size(), 1, "one event for both changes: " + events);
            BalanceChangedEvent event = events.get(0);
            helper.assertTrue(event.getServer() == server, "for this server");
            helper.assertValueEqual(event.getBefore(), 0L, "before the first change");
            helper.assertValueEqual(event.getAfter(), 70L, "after the last");
            helper.assertValueEqual(event.getChanges(), List.of(new BalanceChangedEvent.Change(100, in), new BalanceChangedEvent.Change(-30, out)),
                    "each change in order");
            f.account().deduct(f.balance());
            helper.succeed();
        });
    }

    // ---- Accounts ----------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void personalAccountsExistOnceTheServerSeesThePlayer(GameTestHelper helper) {
        listen();
        MinecraftServer server = helper.getLevel().getServer();
        UUID id = UUID.randomUUID();
        AccountId account = CoreApi.accounts().personal(id);
        helper.assertValueEqual(account.value(), id, "a personal account's ID holds the player's UUID");
        helper.assertValueEqual(CoreApi.accounts().get(server, account), Optional.empty(), "unknown before the server sees them");
        helper.assertValueEqual(CoreApi.money().pay(server, account, 5, reason("unseen")).result(), BankResult.NO_ACCOUNT, "and takes no money");

        ServerPlayer player = new ServerPlayer(server, helper.getLevel(), new GameProfile(id, "seen-test"), ClientInformation.createDefault());
        helper.assertValueEqual(CoreApi.accounts().active(player), account, "the personal account is active");
        AccountInfo info = CoreApi.accounts().get(server, account).orElseThrow();
        helper.assertValueEqual(info.kind(), AccountKind.PERSONAL, "kind");
        helper.assertValueEqual(info.owner(), id, "owner");
        helper.assertValueEqual(info.members(), List.of(new AccountMember(id, AccountRole.OWNER)), "its only member, the owner");
        helper.assertValueEqual(info.balance(), 0L, "empty");
        helper.assertValueEqual(CoreApi.accounts().accessibleBy(server, id).get(0), info, "personal first");
        CoreApi.accounts().active(player);
        List<AccountEvent.Created> created = recorded(AccountEvent.Created.class).stream().filter(e -> e.getAccount().equals(account)).toList();
        helper.assertValueEqual(created.size(), 1, "created once, however often it's seen");
        helper.assertValueEqual(created.get(0).getKind(), AccountKind.PERSONAL, "as a personal account");

        UUID other = UUID.randomUUID();
        helper.assertValueEqual(CoreApi.accounts().create(server, id, "Savings").result(), BankResult.DISABLED, "extra accounts are off by default");
        helper.assertValueEqual(CoreApi.accounts().delete(server, account), BankResult.PERSONAL_ACCOUNT, "a personal account can't be deleted");
        helper.assertValueEqual(CoreApi.accounts().setMember(server, account, other, AccountRole.MEMBER), BankResult.PERSONAL_ACCOUNT, "nor shared");
        helper.assertValueEqual(CoreApi.accounts().removeMember(server, account, id), BankResult.PERSONAL_ACCOUNT, "nor left");
        helper.assertValueEqual(CoreApi.accounts().setActive(player, new AccountId(UUID.randomUUID())), BankResult.NO_ACCOUNT, "no such account");
        helper.succeed();
    }

    /** A shared account: its members, the active choice, and its end, when its money goes to a player and the account is gone. */
    @GameTest(template = EMPTY)
    public static void anAccountEndsAndItsMoneyGoesToAPlayer(GameTestHelper helper) {
        listen();
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture owner = new CoreFixture(helper);
        CoreFixture member = new CoreFixture(helper);
        CoreFixture stranger = new CoreFixture(helper);
        withAccounts(() -> {
            AccountId account = CoreApi.accounts().create(server, owner.player.getUUID(), accountName("t")).account().orElseThrow();
            helper.assertValueEqual(CoreApi.accounts().setMember(server, account, member.player.getUUID(), AccountRole.MEMBER),
                    BankResult.OK, "a member joins");
            try {
                AccountInfo info = CoreApi.accounts().get(server, account).orElseThrow();
                helper.assertValueEqual(info.kind(), AccountKind.NAMED, "a named account");
                helper.assertValueEqual(info.members(), List.of(new AccountMember(owner.player.getUUID(), AccountRole.OWNER),
                        new AccountMember(member.player.getUUID(), AccountRole.MEMBER)), "the owner, then the member");
                helper.assertValueEqual(CoreApi.accounts().setActive(member.player, account), BankResult.OK, "a member may make it active");
                helper.assertValueEqual(CoreApi.accounts().active(member.player), account, "and it is");
                helper.assertValueEqual(CoreApi.accounts().setActive(stranger.player, account), BankResult.NOT_ALLOWED, "not someone else");
                helper.assertValueEqual(CoreApi.money().pay(server, account, 300, reason("team")).result(), BankResult.OK, "paid into it");

                long paid = CoreServices.ACCOUNTS.closeAccount(server, account, owner.player.getUUID());
                helper.assertValueEqual(paid, 300L, "its whole balance");
                helper.assertValueEqual(owner.balance(), 300, "went to the owner");
                helper.assertValueEqual(CoreApi.accounts().get(server, account), Optional.empty(), "the account is gone");
                helper.assertValueEqual(CoreApi.accounts().active(member.player), CoreApi.accounts().personal(member.player.getUUID()),
                        "the member is back on their own account");
                helper.assertValueEqual(CoreApi.money().pay(server, account, 5, reason("team")).result(), BankResult.NO_ACCOUNT, "for good");
                List<AccountEvent.Deleted> deleted = recorded(AccountEvent.Deleted.class).stream().filter(e -> e.getAccount().equals(account)).toList();
                helper.assertValueEqual(deleted.size(), 1, "one Deleted event");
                helper.assertValueEqual(deleted.get(0).getBalanceMoved(), 300L, "saying how much moved");
                helper.assertValueEqual(deleted.get(0).getBalanceTo(), Optional.of(CoreApi.accounts().personal(owner.player.getUUID())), "and where");
            } finally {
                CoreServices.ACCOUNTS.closeAccount(server, account, owner.player.getUUID());
                owner.account().deduct(owner.balance());
            }
        });
        helper.succeed();
    }

    // ---- Item gate events --------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void lockChangesPostAtMostOneEventPerPlayerPerTick(GameTestHelper helper) {
        listen();
        CoreFixture f = new CoreFixture(helper);
        int before = recorded(ItemGateChangedEvent.class).size();
        CoreApi.itemGate().refresh(f.player);
        CoreApi.itemGate().refresh(f.player);
        CoreApi.itemGate().refresh(f.player);
        helper.runAfterDelay(1, () -> {
            List<ItemGateChangedEvent> events = recorded(ItemGateChangedEvent.class);
            List<ItemGateChangedEvent> since = events.subList(before, events.size());
            long forPlayer = since.stream().filter(e -> e.getPlayer().filter(p -> p == f.player).isPresent()).count();
            boolean forAnyone = since.stream().anyMatch(e -> e.getPlayer().isEmpty());
            helper.assertTrue(forPlayer == 1 || forPlayer == 0 && forAnyone,
                    "one event for the player (or one for everyone): " + since);
            helper.assertTrue(forPlayer <= 1, "never more than one");
            helper.succeed();
        });
    }

    // ---- Net worth ---------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void netWorthCountsContributorsAndIgnoresOnesThatThrow(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        AccountId account = CoreApi.accounts().personal(f.player.getUUID());
        ResourceLocation holds = rl("holds");
        ResourceLocation broken = rl("broken_worth");
        CoreApi.netWorth().register(holds, s -> Map.of(account, 500L, new AccountId(UUID.randomUUID()), 99L));
        CoreApi.netWorth().register(broken, s -> {
            throw new IllegalStateException("A broken contributor (GameTest)");
        });
        try {
            helper.assertValueEqual(Leaderboard.heldForPlayers(server).get(f.player.getUUID()), 500L,
                    "the player's 500; the broken one counts as nothing");
            Leaderboard.View view = Leaderboard.viewFor(f.player, id -> id.equals(f.player.getUUID())
                    ? Optional.of(f.player.getGameProfile()) : Optional.empty());
            helper.assertValueEqual(view.top().get(0).balance(), 500L, "and it ranks them");
        } finally {
            helper.assertTrue(CoreApi.netWorth().unregister(holds), "removed");
            CoreApi.netWorth().unregister(broken);
        }
        helper.assertFalse(CoreApi.netWorth().unregister(holds), "once");
        helper.assertFalse(Leaderboard.heldForPlayers(server).containsKey(f.player.getUUID()), "nothing held now");
        helper.succeed();
    }

    /** {@link variableeconomy.core.api.PlayerItems}' checks: a change it can't make changes nothing. */
    @GameTest(template = EMPTY)
    public static void playerItemsRefuseWhatTheyCantDo(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        f.give(new ItemStack(Items.IRON_INGOT, 5));
        boolean threw = false;
        try {
            variableeconomy.core.api.PlayerItems.remove(f.player, stack -> stack.is(Items.IRON_INGOT), 6);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        helper.assertTrue(threw, "removing more than there is throws");
        helper.assertValueEqual(f.count(Items.IRON_INGOT), 5L, "and removes nothing");
        for (int slot = 0; slot < f.player.getInventory().items.size(); slot++) {
            if (f.player.getInventory().items.get(slot).isEmpty()) {
                f.player.getInventory().items.set(slot, new ItemStack(Items.DIRT, 64));
            }
        }
        long room = variableeconomy.core.api.PlayerItems.room(f.player, new ItemStack(Items.DIAMOND));
        threw = false;
        try {
            variableeconomy.core.api.PlayerItems.insert(f.player, new ItemStack(Items.DIAMOND), (int) room + 1);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        helper.assertTrue(threw, "inserting more than fits throws");
        helper.assertValueEqual(f.count(Items.DIAMOND), 0L, "and gives nothing");
        helper.succeed();
    }
}
