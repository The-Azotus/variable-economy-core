package variableeconomy.core.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.core.CoreModule;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.compat.KubeJsBridge;
import variableeconomy.core.data.TransactionHistory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * The KubeJS bindings, called as scripts call them, and the plugin file that hands them to KubeJS. The bindings are
 * plain Java, so they're tested with or without KubeJS; this test never touches KubeJS's own classes.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KubeJsTests {
    private KubeJsTests() {
    }

    @GameTest(template = EMPTY)
    public static void scriptBindingsUseTheActiveAccount(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        KubeJsBridge.Bindings script = new KubeJsBridge.Bindings();
        try {
            helper.assertTrue(script.pay(f.player, 100), "a script pays");
            helper.assertValueEqual(script.balance(f.player), 100L, "into the active account");
            TransactionHistory.Line line = TransactionHistory.get(server).of(f.player.getUUID()).get(0);
            helper.assertValueEqual(line.reason(), KubeJsBridge.SCRIPT, "as kubejs:script");
            helper.assertValueEqual(line.actor(), Optional.of(f.player.getUUID()), "for the player");
            helper.assertTrue(script.pay(f.player, 50, "mypack:quest_reward"), "with its own reason");
            helper.assertValueEqual(TransactionHistory.get(server).of(f.player.getUUID()).get(0).reason().toString(), "mypack:quest_reward",
                    "named in the history");
            helper.assertFalse(script.charge(f.player, 1000), "a charge it can't cover");
            helper.assertValueEqual(script.balance(f.player), 150L, "changes nothing");
            helper.assertTrue(script.charge(f.player, 30, "mypack:unlock"), "one it can, goes through");
            helper.assertValueEqual(script.personalBalance(f.player), 120L, "the personal balance");
            helper.assertFalse(script.pay(f.player, 0), "nothing to pay is refused");
            helper.assertValueEqual(script.format(1024), CoreApi.formatMoney(1024), "amounts read as the screens show them");
        } finally {
            f.account().deduct(f.account().balance());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void kubeJsFindsThePlugin(GameTestHelper helper) throws IOException, ClassNotFoundException {
        boolean installed = ModList.get().isLoaded(KubeJsBridge.MOD_ID);
        helper.assertValueEqual(KubeJsBridge.isLoaded(), installed, "the bridge sees KubeJS when it's installed");
        Path file = ModList.get().getModFileById(CoreModule.MOD_ID).getFile().findResource("kubejs.plugins.txt");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String plugin = KubeJsBridge.class.getName() + "$Present";
        helper.assertValueEqual(lines, List.of(plugin), "the plugin file names the bridge's plugin");
        if (installed) {
            helper.assertValueEqual(Class.forName(plugin, false, KubeJsBridge.class.getClassLoader()).getName(), plugin,
                    "which loads alongside KubeJS");
        }
        helper.succeed();
    }
}
