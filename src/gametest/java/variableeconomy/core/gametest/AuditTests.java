package variableeconomy.core.gametest;

import variableeconomy.core.CoreModule;
import variableeconomy.core.data.AuditLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static variableeconomy.core.gametest.CoreTestSupport.*;

/**
 * The audit log.
 *
 * Shared setup is in {@link CoreTestSupport}.
 */
@GameTestHolder(CoreModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AuditTests {
    private AuditTests() {
    }


    // ---- Audit history and recovery ----------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void auditLogRotatesAndReadsBackNewestFirst(GameTestHelper helper) {
        try {
            Path dir = Files.createTempDirectory("market-audit");
            Path file = dir.resolve("audit.log");
            try {
                for (int i = 0; i < 80; i++) {
                    AuditLog.append(file, "{\"n\":" + i + "}", 100, AuditLog.KEEP);
                }
                helper.assertTrue(Files.size(file) <= 100, "the current file stays under the cap");
                for (int i = 1; i < AuditLog.KEEP; i++) {
                    helper.assertTrue(Files.exists(file.resolveSibling("audit.log." + i)), "rotated file " + i + " kept");
                }
                helper.assertTrue(!Files.exists(file.resolveSibling("audit.log." + AuditLog.KEEP)), "nothing beyond the limit is kept");
                List<Integer> newest = AuditLog.recent(file, 12).stream().map(entry -> entry.get("n").getAsInt()).toList();
                helper.assertValueEqual(newest, java.util.stream.IntStream.iterate(79, n -> n - 1).limit(12).boxed().toList(),
                        "newest first, reading back into rotated files");
            } finally {
                deleteTree(dir);
            }
        } catch (java.io.IOException e) {
            throw new GameTestAssertException("audit log test failed: " + e);
        }
        helper.succeed();
    }
}
