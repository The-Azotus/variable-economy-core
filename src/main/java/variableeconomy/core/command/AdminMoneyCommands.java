package variableeconomy.core.command;

import variableeconomy.core.CoreModule;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.PaymentResult;
import variableeconomy.core.api.Reason;
import variableeconomy.core.service.CoreServices;
import net.minecraft.resources.ResourceLocation;
import java.util.OptionalLong;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import variableeconomy.core.data.AccountRegistry;
import java.util.UUID;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import variableeconomy.core.data.AuditLog;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /bank admin money}: a player's balance, and giving or taking money. Giving creates money and taking
 * destroys it, outside the market and its prices, so these are an admin power and every change is audited. They act
 * on the player's personal account, online or not, or with {@code account <name>} on a named account. If the bank can't be reached they fail; nothing is
 * owed.
 */
public final class AdminMoneyCommands {
    /** What an admin's gift or charge is, in core's money service. The command writes its own audit line. */
    static final ResourceLocation ADMIN = ResourceLocation.fromNamespaceAndPath(CoreModule.MOD_ID, "admin");

    private AdminMoneyCommands() {
    }

    public static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin) {
        // "account <name>" is matched first: a literal wins over the player argument.
        admin.then(Commands.literal("money")
                .then(Commands.literal("balance")
                        .then(Commands.literal("account").then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> balance(ctx, named(ctx)))))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(ctx -> balance(ctx, player(ctx)))))
                .then(Commands.literal("give")
                        .then(Commands.literal("account").then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> move(ctx, true, named(ctx))))))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> move(ctx, true, player(ctx))))))
                .then(Commands.literal("take")
                        .then(Commands.literal("account").then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> move(ctx, false, named(ctx))))))
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> move(ctx, false, player(ctx)))))));
    }

    /** What a money command acts on: an account, and how to name it (and its ID, for the audit line). */
    private record Target(AccountId account, String name, String audit) {
    }

    /** The named player's personal account, or empty after saying a selector named several. */
    private static Optional<Target> player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Optional<GameProfile> profile = onePlayer(ctx);
        return profile.map(p -> new Target(account(ctx, p), p.getName(), p.getName() + " (" + p.getId() + ")"));
    }

    /** The live named (or else party) account called {@code name}, or empty after saying there is none. */
    private static Optional<Target> named(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Optional<UUID> id = AccountRegistry.get(ctx.getSource().getServer()).byName(name);
        if (id.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("There's no named or party account called " + name));
            return Optional.empty();
        }
        return Optional.of(new Target(new AccountId(id.get()), "account " + name, "account " + name + " (" + id.get() + ")"));
    }

    /** Returns the balance, capped at {@code Integer.MAX_VALUE}, so {@code execute store} can use it. */
    private static int balance(CommandContext<CommandSourceStack> ctx, Optional<Target> target) {
        if (target.isEmpty()) {
            return 0;
        }
        OptionalLong found = CoreApi.money().balance(ctx.getSource().getServer(), target.get().account());
        if (found.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("The bank can't be reached"));
            return 0;
        }
        long balance = found.getAsLong();
        ctx.getSource().sendSuccess(() -> Component.literal("%s has %s".formatted(target.get().name(),
                money(balance))), false);
        return (int) Math.min(balance, Integer.MAX_VALUE);
    }

    /** Gives ({@code give}) or takes money; returns the amount moved (capped at {@code Integer.MAX_VALUE}). Taking is all or nothing. */
    private static int move(CommandContext<CommandSourceStack> ctx, boolean give, Optional<Target> target) {
        if (target.isEmpty()) {
            return 0;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        MinecraftServer server = ctx.getSource().getServer();
        String name = target.get().name();
        AccountId account = target.get().account();
        OptionalLong found = CoreApi.money().balance(server, account);
        if (found.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("The bank can't be reached, so nothing was changed"));
            return 0;
        }
        long before = found.getAsLong();
        Reason reason = new Reason(ADMIN, Optional.ofNullable(ctx.getSource().getEntity()).map(e -> e.getUUID()));
        PaymentResult result = give ? CoreApi.money().pay(server, account, amount, reason)
                : CoreApi.money().charge(server, account, amount, reason);
        if (result.owed()) {
            // Paid later, when the bank can be reached: running the command again would pay twice.
            ctx.getSource().sendSuccess(() -> Component.literal("The bank can't be reached: %s is owed %s and will be paid when it can"
                    .formatted(name, money(amount))), true);
            return 1;
        }
        if (!result.ok()) {
            // A refused charge or payment changed nothing.
            ctx.getSource().sendFailure(Component.literal(result.result() == BankResult.NO_FUNDS
                    ? "%s has only %s".formatted(name, money(before))
                    : "Nothing was changed: " + result.result()));
            return 0;
        }
        long after = CoreApi.money().balance(server, account).orElse(before);
        AuditLog.record(ctx.getSource(), give ? "money give" : "money take", target.get().audit(), "ok",
                new JsonPrimitive(before), new JsonPrimitive(after));

        String moved = amount(amount) + " bits";
        String done = give ? "Gave %s %s".formatted(name, moved) : "Took %s from %s".formatted(moved, name);
        ctx.getSource().sendSuccess(() -> Component.literal(done + "; they now have " + money(after)), true);
        return (int) Math.min(amount, Integer.MAX_VALUE);
    }

    /** The one player named, or empty after saying a selector named several. */
    private static Optional<GameProfile> onePlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            ctx.getSource().sendFailure(Component.literal("Name one player at a time"));
            return Optional.empty();
        }
        return Optional.of(profiles.iterator().next());
    }

    /** The player's own account. The server knows the profile, so it has seen the player: their account exists. */
    private static AccountId account(CommandContext<CommandSourceStack> ctx, GameProfile profile) {
        CoreServices.ACCOUNTS.seenPlayer(ctx.getSource().getServer(), profile.getId());
        return CoreApi.accounts().personal(profile.getId());
    }

    /** E.g. "1,024 bits". */
    private static String money(long balance) {
        return amount(balance) + " bits";
    }

    private static String amount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
