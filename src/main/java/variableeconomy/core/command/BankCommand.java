package variableeconomy.core.command;

import variableeconomy.core.CoreSettings;
import variableeconomy.core.network.BankSync;
import variableeconomy.core.menu.BankMenu;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /bank} opens the bank screen, when the access mode allows commands. Its branches: {@code /bank account}
 * ({@link AccountCommands}), {@code /bank history} ({@link HistoryCommands}), {@code /bank top} ({@link TopCommand})
 * and {@code /bank admin}, which work
 * whatever the access mode.
 */
public final class BankCommand {
    private BankCommand() {
    }

    /** Registers {@code /bank}, with {@code /bank account} and the given {@code /bank admin} branch. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, LiteralArgumentBuilder<CommandSourceStack> admin) {
        dispatcher.register(Commands.literal("bank").executes(BankCommand::open).then(admin).then(AccountCommands.node()).then(HistoryCommands.node()).then(TopCommand.node()));
    }

    /** Opens the bank screen; in block-only mode only operators may, and everyone else is told where to go. */
    private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!CoreSettings.commandsAllowed(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.translatable("message.variable_economy.block_only.bank"));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        BankMenu.open(player);
        BankSync.sendFullState(player);
        return 1;
    }
}
