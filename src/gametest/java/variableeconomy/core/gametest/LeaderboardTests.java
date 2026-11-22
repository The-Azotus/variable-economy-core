package variableeconomy.core.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.core.CoreConfig;
import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.Reason;
import variableeconomy.core.data.AccountRegistry;
import variableeconomy.core.data.OwedPayments;
import variableeconomy.core.service.AccountsService;
import variableeconomy.core.service.CoreServices;
import variableeconomy.core.service.Leaderboard;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * The leaderboard's options: ranking by owner (every account a player owns, together) or by account, with owed and
 * held money counted, and operators left out when the server says so; and {@code /bank top}.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LeaderboardTests {
    private LeaderboardTests() {
    }

    @GameTest(template = EMPTY)
    public static void leaderboardRanksByOwnerOrByAccount(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        UUID me = f.player.getUUID();
        // Only this player is known by name, so test leftovers in the persistent world stay off the board.
        Function<UUID, Optional<GameProfile>> profiles = id -> id.equals(me) ? Optional.of(f.player.getGameProfile()) : Optional.empty();
        Reason reason = Reason.of(rl("board"));
        String name = accountName("b");
        CoreConfig.RankBy rankBy = CoreConfig.LEADERBOARD_RANK_BY.get();
        withAccounts(() -> {
            AccountId savings = CoreApi.accounts().create(server, me, name).account().orElseThrow();
            AccountId personal = CoreApi.accounts().personal(me);
            ResourceLocation holder = rl("board_holds");
            OwedPayments.Payment owed = new OwedPayments.Payment(personal, 20, reason);
            try {
                CoreApi.money().pay(server, personal, 100, reason);
                CoreApi.money().pay(server, savings, 300, reason);
                OwedPayments.get(server).owe(owed);
                CoreApi.netWorth().register(holder, s -> Map.of(savings, 50L));

                CoreConfig.LEADERBOARD_RANK_BY.set(CoreConfig.RankBy.OWNER);
                Leaderboard.View byOwner = Leaderboard.viewFor(f.player, profiles);
                helper.assertValueEqual(byOwner.top(), List.of(new Leaderboard.Entry(me, "market-test", 470L)),
                        "one line for the player: both accounts, the owed 20 and the held 50");
                helper.assertValueEqual(byOwner.ownRank(), 1, "ranked first");

                CoreConfig.LEADERBOARD_RANK_BY.set(CoreConfig.RankBy.ACCOUNT);
                Leaderboard.View byAccount = Leaderboard.viewFor(f.player, profiles);
                helper.assertValueEqual(byAccount.top(), List.of(new Leaderboard.Entry(savings.value(), name, 350L),
                        new Leaderboard.Entry(me, "market-test", 120L)), "a line per account, under its own name");
                helper.assertValueEqual(byAccount.you(), me, "the player's line is their active account");
                helper.assertValueEqual(byAccount.ownRank(), 2, "which is second");

                // Operators, and the accounts they own, are left off when the server says so.
                boolean exclude = CoreConfig.LEADERBOARD_EXCLUDE_OPERATORS.get();
                server.getPlayerList().op(f.player.getGameProfile());
                try {
                    CoreConfig.LEADERBOARD_EXCLUDE_OPERATORS.set(true);
                    helper.assertTrue(Leaderboard.viewFor(f.player, profiles).top().isEmpty(), "by account, none of theirs");
                    CoreConfig.LEADERBOARD_RANK_BY.set(CoreConfig.RankBy.OWNER);
                    helper.assertTrue(Leaderboard.viewFor(f.player, profiles).top().isEmpty(), "by owner, not them");
                } finally {
                    CoreConfig.LEADERBOARD_EXCLUDE_OPERATORS.set(exclude);
                    server.getPlayerList().deop(f.player.getGameProfile());
                }
            } finally {
                CoreConfig.LEADERBOARD_RANK_BY.set(rankBy);
                CoreApi.netWorth().unregister(holder);
                OwedPayments.get(server).paid(owed);
                CoreServices.ACCOUNTS.closeAccount(server, savings, me);
                f.account().deduct(f.account().balance());
            }
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void playersAreNamedWithoutTheProfileCache(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        UUID me = f.player.getUUID();
        helper.assertValueEqual(server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(me), Optional.empty(),
                "test players aren't in the profile cache");
        helper.assertValueEqual(AccountRegistry.get(server).personalName(me), Optional.of("market-test"), "core remembered the name");
        helper.assertValueEqual(Leaderboard.profile(server, me).map(GameProfile::getName), Optional.of("market-test"), "so they are named");
        helper.assertValueEqual(AccountsService.name(server, me), "market-test", "in member lists and history too");

        // A name several players joined with names none of them; a unique one names its player.
        UUID unique = UUID.randomUUID();
        String name = accountName("n");
        AccountRegistry.get(server).registerPersonal(unique);
        AccountRegistry.get(server).setPersonalName(unique, name);
        helper.assertValueEqual(AccountRegistry.get(server).personalByName(name.toUpperCase(java.util.Locale.ROOT)), Optional.of(unique),
                "found by name, ignoring case");
        helper.assertValueEqual(AccountRegistry.get(server).personalByName("market-test"), Optional.empty(),
                "every test player is called market-test, so that name is ambiguous");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankTopPrintsTheBoard(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        String key = "command.variable_economy.top.";
        f.deposit(10);
        try {
            List<String> board = commandKeysAs(helper, f.player, "bank top");
            // Test players aren't in the server's profile cache: they're named by the name core remembered when it saw them.
            helper.assertValueEqual(board.get(0), key + "header", "a header: " + board);
            helper.assertValueEqual(board.get(board.size() - 1), key + "you", "and the player's own place: they are ranked");
            boolean enabled = CoreConfig.LEADERBOARD_ENABLED.get();
            CoreConfig.LEADERBOARD_ENABLED.set(false);
            try {
                helper.assertValueEqual(commandKeysAs(helper, f.player, "bank top"), List.of(key + "off"), "turned off");
            } finally {
                CoreConfig.LEADERBOARD_ENABLED.set(enabled);
            }
        } finally {
            f.account().deduct(f.account().balance());
        }
        helper.succeed();
    }
}
