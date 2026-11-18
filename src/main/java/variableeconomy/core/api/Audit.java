package variableeconomy.core.api;

import com.google.gson.JsonElement;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * The admin audit log: one line per admin action, in {@code <world>/variable_economy/audit.log}, where admins read it
 * with {@code /bank admin audit}. Get it from {@link CoreApi#audit()}.
 * <p>
 * For actions an operator or the console takes with an add-on's admin commands, such as changing what the add-on
 * holds. Money calls write their own lines (see {@link Money}), so don't record them again here. Writing never fails
 * the caller: a problem with the file is only logged.
 */
public interface Audit {
    /**
     * Records one admin action. Server thread only.
     *
     * @param source who ran the command; the line names them
     * @param action what was done, a few words such as {@code exchange cancel}
     * @param target what it was done to, such as an item ID or {@code #12}
     * @param result how it ended: {@code ok}, or {@code refused: } and why
     * @param before what the target was before, when that is worth keeping, or null
     * @param after  what the target is after, or null
     */
    void record(CommandSourceStack source, String action, String target, String result, @Nullable JsonElement before,
                @Nullable JsonElement after);

    /**
     * How many lines the audit log has gained since {@code since}, counting at most the last thousand, such as to tell
     * an admin how much has happened since a backup was taken. Server thread only.
     *
     * @param server the server
     * @param since  the moment to count from
     * @return the number of lines logged after it, at most 1,000
     */
    long countSince(MinecraftServer server, Instant since);
}
