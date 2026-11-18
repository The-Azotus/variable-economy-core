package variableeconomy.core.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.Reason;
import variableeconomy.core.data.MoneySupplyStats;

import java.util.List;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * Money-supply stats: what each source created and destroyed, transfers counting as neither, saved with the world,
 * reset, and the admin command's report.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StatsTests {
    private StatsTests() {
    }

    private static MoneySupplyStats.Totals totals(MinecraftServer server, ResourceLocation reason) {
        return MoneySupplyStats.get(server).sources().getOrDefault(reason, new MoneySupplyStats.Totals(0, 0, 0));
    }

    @GameTest(template = EMPTY)
    public static void statsCountMoneyCreatedAndDestroyedPerSource(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        CoreFixture other = new CoreFixture(helper);
        AccountId mine = CoreApi.accounts().personal(f.player.getUUID());
        AccountId theirs = CoreApi.accounts().personal(other.player.getUUID());
        ResourceLocation source = rl("stats_" + accountName(""));
        ResourceLocation moved = rl("stats_moved_" + accountName(""));
        try {
            CoreApi.money().pay(server, mine, 100, Reason.of(source));
            CoreApi.money().charge(server, mine, 30, Reason.of(source));
            CoreApi.money().transfer(server, mine, theirs, 50, Reason.of(moved));
            MoneySupplyStats.Totals counted = totals(server, source);
            helper.assertValueEqual(counted, new MoneySupplyStats.Totals(100, 30, 2), "created 100, destroyed 30, twice");
            helper.assertValueEqual(counted.net(), 70L, "net 70");
            helper.assertValueEqual(totals(server, moved), new MoneySupplyStats.Totals(0, 0, 0), "a transfer counts as neither");
            CoreApi.money().charge(server, mine, 1000, Reason.of(source));
            helper.assertValueEqual(totals(server, source).count(), 2L, "a refused charge counts nothing");

            MoneySupplyStats loaded = MoneySupplyStats.fromTag(MoneySupplyStats.get(server).save(new CompoundTag(),
                    helper.getLevel().registryAccess()), helper.getLevel().registryAccess());
            helper.assertValueEqual(loaded.sources().get(source), counted, "the counts survive a save");
            helper.assertValueEqual(loaded.since(), MoneySupplyStats.get(server).since(), "with when counting began");

            List<String> report = runCommand(helper, "bank admin stats");
            helper.assertTrue(report.get(0).startsWith("Money supply"), "the report: " + report);
            helper.assertTrue(report.stream().anyMatch(line -> line.contains(source.getPath()) && line.contains("net +70")),
                    "lists the source: " + report);

            MoneySupplyStats copy = new MoneySupplyStats();
            copy.created(source, 5);
            copy.reset();
            helper.assertTrue(copy.sources().isEmpty(), "a reset forgets every count");
        } finally {
            f.account().deduct(f.account().balance());
            other.account().deduct(other.account().balance());
        }
        helper.succeed();
    }
}
