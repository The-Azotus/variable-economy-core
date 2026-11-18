package variableeconomy.core.command;

import variableeconomy.core.CoreModule;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import variableeconomy.core.data.AuditLog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * {@code /bank admin audit}: the admin audit log, newest first, ten actions a page.
 */
public final class AdminAuditCommands {
    private static final int BACKUPS_SHOWN = 10;
    private static final int AUDIT_PER_PAGE = 10;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private AdminAuditCommands() {
    }

    public static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin) {
        admin.then(Commands.literal("audit").executes(ctx -> audit(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> audit(ctx, IntegerArgumentType.getInteger(ctx, "page")))));
    }

    /** The most recent admin actions, newest first, ten per page. */
    private static int audit(CommandContext<CommandSourceStack> ctx, int page) {
        List<JsonObject> entries = AuditLog.recent(AuditLog.file(ctx.getSource().getServer()), page * AUDIT_PER_PAGE + 1);
        List<JsonObject> shown = entries.stream().skip((long) (page - 1) * AUDIT_PER_PAGE).limit(AUDIT_PER_PAGE).toList();
        if (shown.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(page == 1 ? "No admin actions logged yet"
                    : "No more entries"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Admin actions, newest first (page %d; the full log is <world>/%s/audit.log):"
                .formatted(page, CoreModule.MOD_ID)), false);
        for (JsonObject entry : shown) {
            String line = "  %s %s: %s %s → %s".formatted(when(text(entry, "time")), text(entry, "actor"), text(entry, "action"),
                    text(entry, "target"), text(entry, "result"));
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        if (entries.size() > page * AUDIT_PER_PAGE) {
            ctx.getSource().sendSuccess(() -> Component.literal("/bank admin audit " + (page + 1) + " for older ones"), false);
        }
        return shown.size();
    }

    private static String text(JsonObject entry, String key) {
        return entry.has(key) ? entry.get(key).getAsString() : "?";
    }

    /** An ISO instant as local date and time, or as it is if it can't be read. */
    private static String when(String instant) {
        try {
            return WHEN.format(Instant.parse(instant));
        } catch (RuntimeException e) {
            return instant;
        }
    }
}
