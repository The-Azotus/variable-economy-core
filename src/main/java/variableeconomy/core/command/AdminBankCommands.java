package variableeconomy.core.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import variableeconomy.core.api.AccountInfo;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.bank.OwnBank;
import variableeconomy.core.data.AccountRegistry;
import variableeconomy.core.data.OwedPayments;
import variableeconomy.core.service.AccountsService;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /bank admin info}: how much money the mod's bank holds, by kind of account. {@code /bank admin accounts
 * <player>}: the accounts a player may use. Both change nothing.
 */
public final class AdminBankCommands {
    private AdminBankCommands() {
    }

    public static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin) {
        admin.then(Commands.literal("info").executes(AdminBankCommands::info));
        admin.then(Commands.literal("accounts")
                .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(AdminBankCommands::accounts)));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        Map<AccountKind, long[]> totals = totals(server);
        for (AccountKind kind : AccountKind.values()) {
            long[] t = totals.get(kind);
            String line = "%d %s account(s) hold %s bits".formatted(t[0], kind.name().toLowerCase(Locale.ROOT), amount(t[1]));
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        List<OwedPayments.Payment> owed = OwedPayments.get(server).list();
        if (!owed.isEmpty()) {
            String line = "%d payment(s) of %s bits wait for the bank to be reachable".formatted(owed.size(),
                    amount(owed.stream().mapToLong(OwedPayments.Payment::amount).sum()));
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /**
     * For each kind of account: how many live accounts of it hold money, and how much. A balance held for an account
     * core doesn't know counts as personal.
     */
    public static Map<AccountKind, long[]> totals(MinecraftServer server) {
        AccountRegistry registry = AccountRegistry.get(server);
        Map<AccountKind, long[]> totals = new EnumMap<>(AccountKind.class);
        for (AccountKind kind : AccountKind.values()) {
            totals.put(kind, new long[2]);
        }
        for (Map.Entry<UUID, Long> balance : OwnBank.get(server).balances().entrySet()) {
            AccountKind kind = registry.entry(balance.getKey()).map(AccountRegistry.Entry::kind).orElse(AccountKind.PERSONAL);
            long[] t = totals.get(kind);
            t[0]++;
            t[1] += balance.getValue();
        }
        return totals;
    }

    private static int accounts(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            ctx.getSource().sendFailure(Component.literal("Name one player at a time"));
            return 0;
        }
        GameProfile profile = profiles.iterator().next();
        MinecraftServer server = ctx.getSource().getServer();
        List<AccountInfo> accounts = CoreApi.accounts().accessibleBy(server, profile.getId());
        if (accounts.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(profile.getName() + " has no accounts: the server hasn't seen them"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(profile.getName() + "'s accounts:"), false);
        for (AccountInfo info : accounts) {
            String role = info.roleOf(profile.getId()).map(r -> r.name().toLowerCase(Locale.ROOT)).orElse("?");
            String line = "  %s (%s, %s, owner %s, %d member(s)): %s bits".formatted(info.name(),
                    info.kind().name().toLowerCase(Locale.ROOT), role, AccountsService.nameOrId(server, info.owner()),
                    info.members().size(), amount(info.balance()));
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return accounts.size();
    }

    private static String amount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
