package variableeconomy.core.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import variableeconomy.core.CoreModule;
import variableeconomy.core.CoreSettings;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountInfo;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.PaymentResult;
import variableeconomy.core.api.Reason;
import variableeconomy.core.data.AccountRegistry;
import variableeconomy.core.service.CoreServices;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /pay <player> <amount>}: pays another player, online or not, from the sender's active account into the
 * recipient's (their chosen active account, or their personal one). With {@code pay.fee_percent} the sender pays a fee
 * on top, which is destroyed; the payment and its fee are one all-or-nothing step. Works on block-only servers too;
 * {@code pay.enabled} turns it off.
 */
public final class PayCommand {
    /** A payment from one player to another. */
    public static final ResourceLocation PAYMENT = ResourceLocation.fromNamespaceAndPath(CoreModule.MOD_ID, "player_payment");
    /** The fee on a payment, destroyed. */
    public static final ResourceLocation FEE = ResourceLocation.fromNamespaceAndPath(CoreModule.MOD_ID, "pay_fee");
    private static final String KEY = "command.variable_economy.pay.";

    private PayCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("amount", LongArgumentType.longArg(1)).executes(PayCommand::pay))));
    }

    /** The fee on a payment of {@code amount} at {@code percent}: rounded up to a whole bit, 0 when there's no fee. */
    public static long fee(long amount, double percent) {
        if (percent <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.CEILING).min(BigDecimal.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    private static int pay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        if (!CoreSettings.payEnabled()) {
            return fail(ctx, "off");
        }
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            return fail(ctx, "one_player");
        }
        return pay(ctx, sender, profiles.iterator().next(), LongArgumentType.getLong(ctx, "amount"));
    }

    /**
     * How a payment went: {@code key} names the message ({@code paid}, {@code paid_fee} or why not), {@code fee} the fee
     * taken, and {@code to} the account paid.
     */
    public record Attempt(boolean ok, String key, long fee, AccountId to, String detail) {
        Attempt(boolean ok, String key, long fee, AccountId to) {
            this(ok, key, fee, to, "");
        }
    }

    /** Pays {@code recipient}'s active account from {@code sender}'s, after every check: the command without its arguments. */
    public static Attempt attempt(ServerPlayer sender, UUID recipient, long amount) {
        MinecraftServer server = sender.server;
        AccountId none = CoreApi.accounts().personal(recipient);
        if (!CoreSettings.payEnabled()) {
            return new Attempt(false, "off", 0, none);
        }
        if (recipient.equals(sender.getUUID())) {
            return new Attempt(false, "self", 0, none);
        }
        if (!AccountRegistry.get(server).isPersonal(recipient)) {
            return new Attempt(false, "unknown", 0, none);
        }
        if (amount < CoreSettings.payMinAmount()) {
            return new Attempt(false, "too_small", 0, none);
        }
        long fee = fee(amount, CoreSettings.payFeePercent());
        AccountId from = CoreApi.accounts().active(sender);
        AccountId to = CoreServices.ACCOUNTS.activeOf(server, recipient);
        PaymentResult result = CoreServices.MONEY.transferWithFee(server, from, to, amount, fee,
                Reason.by(PAYMENT, sender.getUUID()), Reason.by(FEE, sender.getUUID()));
        if (result.ok()) {
            return new Attempt(true, fee > 0 ? "paid_fee" : "paid", fee, to);
        }
        return new Attempt(false, switch (result.result()) {
            case NO_FUNDS -> "no_funds";
            case NOT_ALLOWED -> "same_account";
            default -> "failed";
        }, fee, to, result.result().name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Pays {@code recipient} and says how it went. */
    private static int pay(CommandContext<CommandSourceStack> ctx, ServerPlayer sender, GameProfile recipient, long amount) {
        MinecraftServer server = sender.server;
        Attempt attempt = attempt(sender, recipient.getId(), amount);
        Component money = CoreApi.formatMoney(amount);
        Component fee = CoreApi.formatMoney(attempt.fee());
        if (!attempt.ok()) {
            return switch (attempt.key()) {
                case "unknown" -> fail(ctx, "unknown", recipient.getName());
                case "too_small" -> fail(ctx, "too_small", CoreApi.formatMoney(CoreSettings.payMinAmount()));
                case "no_funds" -> fail(ctx, "no_funds", money, fee,
                        CoreApi.formatMoney(CoreApi.money().balance(server, CoreApi.accounts().active(sender)).orElse(0L)));
                case "failed" -> fail(ctx, "failed", Component.translatable(
                        "command.variable_economy.account.result." + attempt.detail()));
                default -> fail(ctx, attempt.key());
            };
        }
        // Paid into a named or party account, which others may share: both messages name it.
        Optional<String> into = attempt.to().equals(CoreApi.accounts().personal(recipient.getId())) ? Optional.empty()
                : CoreApi.accounts().get(server, attempt.to()).map(AccountInfo::name);
        Component paid = into.isEmpty() ? Component.translatable(KEY + attempt.key(), recipient.getName(), money, fee)
                : attempt.fee() > 0 ? Component.translatable(KEY + "paid_fee_account", recipient.getName(), money, fee, into.get())
                : Component.translatable(KEY + "paid_account", recipient.getName(), money, into.get());
        ctx.getSource().sendSuccess(() -> paid, false);
        ServerPlayer online = server.getPlayerList().getPlayer(recipient.getId());
        if (online != null) {
            String from = sender.getGameProfile().getName();
            online.sendSystemMessage(into.isEmpty() ? Component.translatable(KEY + "received", from, money)
                    : Component.translatable(KEY + "received_account", from, money, into.get()));
        }
        return (int) Math.min(amount, Integer.MAX_VALUE);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        ctx.getSource().sendFailure(Component.translatable(KEY + key, args));
        return 0;
    }
}
