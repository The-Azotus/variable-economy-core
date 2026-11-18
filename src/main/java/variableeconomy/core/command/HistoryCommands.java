package variableeconomy.core.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import variableeconomy.core.CoreSettings;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountInfo;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.data.AccountRegistry;
import variableeconomy.core.data.TransactionHistory;
import variableeconomy.core.service.AccountsService;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /bank history [account] [page]}: an account's money movements, newest first, for its owner and members
 * (deposit-only members can't read it). {@code /bank admin history <player>|account <name> [page]}: any account's.
 * These work on block-only servers too.
 */
public final class HistoryCommands {
    private static final String KEY = "command.variable_economy.history.";
    public static final int PER_PAGE = 10;

    private HistoryCommands() {
    }

    /** The {@code history} branch, for {@code /bank}. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("history").executes(ctx -> own(ctx, null, 1))
                .then(Commands.argument("account", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            ServerPlayer player = ctx.getSource().getPlayer();
                            return player == null ? builder.buildFuture() : SharedSuggestionProvider.suggest(
                                    CoreApi.accounts().accessibleBy(player.server, player.getUUID()).stream()
                                            .map(AccountCommands::word), builder);
                        })
                        .executes(ctx -> own(ctx, StringArgumentType.getString(ctx, "account"), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> own(ctx, StringArgumentType.getString(ctx, "account"), IntegerArgumentType.getInteger(ctx, "page")))));
    }

    /** The {@code history} branch, for {@code /bank admin}. */
    public static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin) {
        admin.then(Commands.literal("history")
                .then(Commands.literal("account").then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> named(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> named(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ctx -> player(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> player(ctx, IntegerArgumentType.getInteger(ctx, "page"))))));
    }

    /** The player's own account's history: {@code name}, or their active account when null. */
    private static int own(CommandContext<CommandSourceStack> ctx, String name, int page) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<AccountInfo> account = name == null
                ? CoreApi.accounts().get(player.server, CoreApi.accounts().active(player))
                : AccountCommands.find(player.server, player.getUUID(), name);
        if (account.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("command.variable_economy.account.not_yours", name));
            return 0;
        }
        if (!account.get().roleOf(player.getUUID()).map(HistoryCommands::canRead).orElse(false)) {
            ctx.getSource().sendFailure(Component.translatable(KEY + "not_allowed", AccountCommands.label(account.get())));
            return 0;
        }
        return show(ctx, account.get().id(), AccountCommands.label(account.get()), page);
    }

    private static int named(CommandContext<CommandSourceStack> ctx, int page) {
        String name = StringArgumentType.getString(ctx, "name");
        Optional<UUID> id = AccountRegistry.get(ctx.getSource().getServer()).byName(name);
        if (id.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("There's no named or party account called " + name));
            return 0;
        }
        return show(ctx, new AccountId(id.get()), Component.literal(name), page);
    }

    private static int player(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            ctx.getSource().sendFailure(Component.literal("Name one player at a time"));
            return 0;
        }
        GameProfile profile = profiles.iterator().next();
        return show(ctx, CoreApi.accounts().personal(profile.getId()), Component.literal(profile.getName()), page);
    }

    /** Who may read an account's history: everyone who may take money out of it. */
    public static boolean canRead(AccountRole role) {
        return role.canSpend();
    }

    private static int show(CommandContext<CommandSourceStack> ctx, AccountId account, Component name, int page) {
        MinecraftServer server = ctx.getSource().getServer();
        if (CoreSettings.historySize() == 0) {
            ctx.getSource().sendFailure(Component.translatable(KEY + "off"));
            return 0;
        }
        List<TransactionHistory.Line> lines = TransactionHistory.get(server).of(account.value());
        if (lines.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "empty", name), false);
            return 0;
        }
        int pages = (lines.size() + PER_PAGE - 1) / PER_PAGE;
        int shown = Math.min(page, pages);
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "header", name, shown, pages), false);
        long now = System.currentTimeMillis();
        for (TransactionHistory.Line line : TransactionHistory.page(lines, shown, PER_PAGE)) {
            Component by = line.actor().<Component>map(actor -> Component.translatable(KEY + "by", AccountsService.name(server, actor)))
                    .orElse(Component.empty());
            Component text = Component.translatable(KEY + "line", ago(now, line.time()), signed(line.amount()), reason(line.reason()), by,
                    CoreApi.formatMoney(line.balance()));
            ctx.getSource().sendSuccess(() -> text, false);
        }
        return lines.size();
    }

    /** "+1,024 bits" or "-1,024 bits". */
    public static MutableComponent signed(long amount) {
        return Component.literal(amount < 0 ? "-" : "+").append(CoreApi.formatMoney(amount == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(amount)));
    }

    /** A reason as players read it: its module's name for it, or its ID if it has none. */
    public static Component reason(ResourceLocation reason) {
        return Component.translatableWithFallback("reason." + reason.getNamespace() + "." + reason.getPath(), reason.toString());
    }

    /** "5 min ago", roughly. */
    public static Component ago(long now, long then) {
        long seconds = Math.max(0, (now - then) / 1000);
        if (seconds < 60) {
            return Component.translatable("time.variable_economy.ago.seconds", seconds);
        }
        if (seconds < 3600) {
            return Component.translatable("time.variable_economy.ago.minutes", seconds / 60);
        }
        if (seconds < 86_400) {
            return Component.translatable("time.variable_economy.ago.hours", seconds / 3600);
        }
        return Component.translatable("time.variable_economy.ago.days", seconds / 86_400);
    }
}
