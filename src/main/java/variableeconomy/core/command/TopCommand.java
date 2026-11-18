package variableeconomy.core.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import variableeconomy.core.CoreSettings;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.data.TransactionHistory;
import variableeconomy.core.service.Leaderboard;

import java.util.List;

/**
 * {@code /bank top [page]}: the leaderboard in chat, as the bank screen shows it, and the player's own place on it.
 * Works on block-only servers too.
 */
public final class TopCommand {
    private static final String KEY = "command.variable_economy.top.";
    private static final int PER_PAGE = 10;

    private TopCommand() {
    }

    /** The {@code top} branch, for {@code /bank}. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("top").executes(ctx -> show(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, "page"))));
    }

    private static int show(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!CoreSettings.leaderboardEnabled()) {
            ctx.getSource().sendFailure(Component.translatable(KEY + "off"));
            return 0;
        }
        Leaderboard.View view = Leaderboard.viewFor(player);
        if (view.top().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "empty"), false);
            return 0;
        }
        int pages = (view.top().size() + PER_PAGE - 1) / PER_PAGE;
        int shown = Math.min(page, pages);
        Component by = Component.translatable(KEY + (CoreSettings.leaderboardByAccount() ? "by_account" : "by_owner"));
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "header", by, shown, pages), false);
        List<Leaderboard.Entry> lines = TransactionHistory.page(view.top(), shown, PER_PAGE);
        for (int i = 0; i < lines.size(); i++) {
            Leaderboard.Entry entry = lines.get(i);
            int rank = (shown - 1) * PER_PAGE + i + 1;
            Component line = Component.translatable(KEY + "line", rank, entry.name(), CoreApi.formatMoney(entry.balance()));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        Component own = view.ownRank() > 0 ? Component.translatable(KEY + "you", view.ownRank(), view.ranked())
                : Component.translatable(KEY + "unranked");
        ctx.getSource().sendSuccess(() -> own, false);
        return view.ownRank();
    }
}
