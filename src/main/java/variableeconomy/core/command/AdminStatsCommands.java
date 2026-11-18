package variableeconomy.core.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.data.AuditLog;
import variableeconomy.core.data.MoneySupplyStats;
import variableeconomy.core.data.OwedPayments;
import variableeconomy.core.service.BankService;
import variableeconomy.core.service.CoreServices;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code /bank admin stats}: the money supply. What accounts hold now, money owed and money add-ons hold, the coins
 * paid in and taken out, then the money each source created and destroyed since counting started, grouped by mod.
 * {@code /bank admin stats reset} starts counting again (audited). The market's own {@code /market admin stats}
 * counts its trades alone.
 */
public final class AdminStatsCommands {
    private AdminStatsCommands() {
    }

    public static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin) {
        admin.then(Commands.literal("stats").executes(AdminStatsCommands::stats)
                .then(Commands.literal("reset").executes(AdminStatsCommands::reset)));
    }

    /** The report, one line each. */
    public static List<String> report(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        MoneySupplyStats stats = MoneySupplyStats.get(server);
        Map<AccountKind, long[]> totals = AdminBankCommands.totals(server);
        long inAccounts = totals.values().stream().mapToLong(t -> t[1]).sum();
        lines.add("Money supply, counted since " + Instant.ofEpochMilli(stats.since()) + ":");
        lines.add("  In accounts: %s bits (personal %s, named %s, party %s)".formatted(amount(inAccounts),
                amount(totals.get(AccountKind.PERSONAL)[1]), amount(totals.get(AccountKind.NAMED)[1]), amount(totals.get(AccountKind.PARTY)[1])));
        long owed = OwedPayments.get(server).list().stream().mapToLong(OwedPayments.Payment::amount).sum();
        long held = CoreServices.NET_WORTH.held(server).values().stream().mapToLong(Long::longValue).sum();
        lines.add("  Owed, waiting for the bank: %s bits; held by add-ons: %s bits".formatted(amount(owed), amount(held)));

        Map<ResourceLocation, MoneySupplyStats.Totals> sources = stats.sources();
        MoneySupplyStats.Totals in = sources.getOrDefault(BankService.COINS_IN, new MoneySupplyStats.Totals(0, 0, 0));
        MoneySupplyStats.Totals out = sources.getOrDefault(BankService.COINS_OUT, new MoneySupplyStats.Totals(0, 0, 0));
        lines.add("  Coins: %s bits deposited, %s withdrawn; about %s bits more in players' hands than when counting began"
                .formatted(amount(in.created()), amount(out.destroyed()), amount(out.destroyed() - in.created())));

        Map<String, List<Map.Entry<ResourceLocation, MoneySupplyStats.Totals>>> byMod = new TreeMap<>();
        sources.entrySet().stream()
                .filter(e -> !e.getKey().equals(BankService.COINS_IN) && !e.getKey().equals(BankService.COINS_OUT))
                .forEach(e -> byMod.computeIfAbsent(e.getKey().getNamespace(), ns -> new ArrayList<>()).add(e));
        if (byMod.isEmpty()) {
            lines.add("  No money created or destroyed yet");
        }
        byMod.forEach((mod, entries) -> {
            long created = entries.stream().mapToLong(e -> e.getValue().created()).sum();
            long destroyed = entries.stream().mapToLong(e -> e.getValue().destroyed()).sum();
            lines.add("  %s: created %s, destroyed %s, net %s".formatted(mod, amount(created), amount(destroyed), signed(created - destroyed)));
            for (Map.Entry<ResourceLocation, MoneySupplyStats.Totals> e : entries) {
                MoneySupplyStats.Totals t = e.getValue();
                lines.add("    %s: created %s, destroyed %s, net %s (%d times)".formatted(e.getKey().getPath(), amount(t.created()),
                        amount(t.destroyed()), signed(t.net()), t.count()));
            }
        });
        return lines;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        report(ctx.getSource().getServer()).forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        MoneySupplyStats.get(ctx.getSource().getServer()).reset();
        AuditLog.record(ctx.getSource(), "stats reset", "money supply", "ok", null, null);
        ctx.getSource().sendSuccess(() -> Component.literal("Money-supply counts reset; counting starts again now"), true);
        return 1;
    }

    private static String amount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String signed(long value) {
        return (value > 0 ? "+" : "") + amount(value);
    }
}
