package variableeconomy.core.gametest;

import net.minecraft.resources.ResourceLocation;
import variableeconomy.core.data.AccountRegistry;
import variableeconomy.core.data.OwedPayments;
import variableeconomy.core.bank.OwnBank;
import variableeconomy.core.menu.BankMenu;
import variableeconomy.core.block.CoreBlocks;
import variableeconomy.core.CoreSettings;
import variableeconomy.core.CoreConfig;
import variableeconomy.core.CoreModule;
import variableeconomy.core.service.Leaderboard;
import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * The Bank block, the access mode and the leaderboard.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AccessTests {
    private AccessTests() {
    }


    // ---- Leaderboard -------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void leaderboardRanksRichestFirstAndSkipsWhoItShould(GameTestHelper helper) {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        UUID broke = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        Map<UUID, GameProfile> known = Map.of(alice, new GameProfile(alice, "alice"), bob, new GameProfile(bob, "Bob"),
                carol, new GameProfile(carol, "carol"), broke, new GameProfile(broke, "broke"),
                admin, new GameProfile(admin, "admin"));
        Map<UUID, Long> balances = Map.of(alice, 500L, bob, 900L, carol, 500L, broke, 0L, stranger, 10_000L, admin, 99_999L);

        List<Leaderboard.Entry> ranked = Leaderboard.rank(balances, id -> Optional.ofNullable(known.get(id)),
                profile -> profile.getName().equals("admin"));

        helper.assertValueEqual(ranked.stream().map(Leaderboard.Entry::name).toList(), List.of("Bob", "alice", "carol"),
                "richest first, ties by name; no empty, unknown or excluded accounts");
        helper.assertValueEqual(ranked.get(0).balance(), 900L, "exact balance");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void leaderboardReadsPersonalAccountsAtTheirBankBalance(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        f.deposit(1234);
        try {
            Map<UUID, Long> balances = f.bank().balances();
            helper.assertValueEqual(balances.get(f.player.getUUID()), (long) f.balance(), "listed balance is the bank balance");

            // With only this player known by name, every other account (test leftovers) stays off.
            Leaderboard.View view = Leaderboard.viewFor(f.player, id -> id.equals(f.player.getUUID())
                    ? Optional.of(f.player.getGameProfile()) : Optional.empty());
            helper.assertValueEqual(view.ranked(), 1, "players ranked");
            helper.assertValueEqual(view.ownRank(), 1, "own rank");
            helper.assertValueEqual(view.top().get(0).id(), f.player.getUUID(), "the player's line");
            helper.assertValueEqual(view.top().get(0).balance(), 1234L, "ranked at the bank balance");
        } finally {
            // The GameTest world persists: leave the account empty, which keeps it off every later board.
            f.account().deduct(f.balance());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void leaderboardAddsUpEverySource(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        helper.assertValueEqual(Leaderboard.withHeld(Map.of(id, 10L), Map.of(id, 5L), Map.of(id, 40L)), Map.of(id, 55L),
                "the bank, owed money and what add-ons hold add up");
        helper.succeed();
    }

    // ---- The Bank: its block, access mode, ids and commands ------------------------------------------------------

    private static EconomyBlock bank() {
        return new EconomyBlock("bank", CoreBlocks.BANK.get(), CoreBlocks.BANK_ITEM.get(), BankMenu::forBlock, BankMenu::forCommand,
                net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE);
    }

    @GameTest(template = EMPTY)
    public static void blockOnlyModeKeepsCommandsForOperators(GameTestHelper helper) {
        helper.assertTrue(CoreSettings.commandsAllowed(false, false), "everyone, by default");
        helper.assertTrue(!CoreSettings.commandsAllowed(true, false), "players need the block");
        helper.assertTrue(CoreSettings.commandsAllowed(true, true), "operators keep commands");
        helper.assertTrue(CoreSettings.blockOnly() == (CoreConfig.accessMode() == CoreConfig.AccessMode.BLOCK_ONLY),
                "core reads its own access_mode");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankScreenClosesWhenYouWalkAwayOrItsBlockBreaks(GameTestHelper helper) {
        assertScreenFollowsTheBlock(helper, bank());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankHasARecipeAndFacing(GameTestHelper helper) {
        assertRecipeAndFacing(helper, bank());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankIsSolidAndDropsItself(GameTestHelper helper) {
        assertSolidAndDropsItself(helper, bank());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void commandsSitUnderTheirOwnRoot(GameTestHelper helper) {
        for (String command : List.of("bank", "bank account list", "bank admin info", "bank admin audit", "bank admin money")) {
            helper.assertTrue(commandExists(helper, command), "/" + command + " exists");
        }
        for (String command : List.of("market admin bank", "market admin money", "market admin audit", "bank team")) {
            helper.assertTrue(!commandExists(helper, command), "/" + command + " is gone");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everythingUsesTheModId(GameTestHelper helper) {
        // The mod id is spelled out in several places that must agree; a mismatch shows only as missing names or data.
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        String oldId = "dynamic_economy"; // the mod's id before it was renamed Variable Economy
        for (String folder : List.of("market", "market_classes", "recipe", "loot_table")) {
            List<ResourceLocation> old = server.getResourceManager().listResources(folder, id -> true).keySet().stream()
                    .filter(id -> id.getNamespace().equals(oldId)).toList();
            helper.assertTrue(old.isEmpty(), folder + " files still under the old id: " + old);
        }
        assertOwnNamespace(helper, CoreModule.MOD_ID, List.of("money.variable_economy.bits"));
        for (String id : List.of(OwnBank.ID, OwedPayments.ID, AccountRegistry.ID)) {
            helper.assertTrue(id.startsWith(CoreModule.MOD_ID + "_"), "saved data " + id + " is named after the mod");
        }
        helper.succeed();
    }
}
