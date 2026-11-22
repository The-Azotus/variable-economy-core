package variableeconomy.core.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountInfo;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountMember;
import variableeconomy.core.api.AccountResult;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.service.AccountsService;
import variableeconomy.core.service.CoreServices;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * {@code /bank account}: a player's accounts. Listing them, picking the active one, and, when the server allows extra
 * and shared accounts, opening, sharing, leaving and deleting them. Accounts are named by their name, or
 * {@code personal} for the player's own. Members join by accepting an invitation. These work on block-only servers
 * too: only opening the bank screen follows {@code access_mode}.
 */
public final class AccountCommands {
    private static final String KEY = "command.variable_economy.account.";

    private AccountCommands() {
    }

    /** The {@code account} branch, for {@code /bank}. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("account").executes(AccountCommands::list)
                .then(Commands.literal("list").executes(AccountCommands::list))
                .then(Commands.literal("info").then(name(mine(info -> true)).executes(AccountCommands::info)))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(AccountCommands::create)))
                .then(Commands.literal("delete").then(name(owned()).executes(AccountCommands::delete)))
                .then(Commands.literal("use").then(name(mine(info -> true)).executes(AccountCommands::use)))
                .then(Commands.literal("invite").then(name(owned())
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> invite(ctx, AccountRole.MEMBER))
                                .then(Commands.argument("role", StringArgumentType.word()).suggests(roles(false))
                                        .executes(ctx -> invite(ctx, null))))))
                .then(Commands.literal("join").then(name(invitations()).executes(ctx -> answer(ctx, true))))
                .then(Commands.literal("decline").then(name(invitations()).executes(ctx -> answer(ctx, false))))
                .then(Commands.literal("leave").then(name(mine(info -> info.kind() == AccountKind.NAMED)).executes(AccountCommands::leave)))
                .then(Commands.literal("remove").then(name(owned())
                        .then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(AccountCommands::remove))))
                .then(Commands.literal("role").then(name(owned())
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("role", StringArgumentType.word()).suggests(roles(true))
                                        .executes(AccountCommands::role)))));
    }

    // ---- Reading ---------------------------------------------------------------------------------------------

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = player.server;
        AccountId active = CoreApi.accounts().active(player);
        List<AccountInfo> accounts = CoreApi.accounts().accessibleBy(server, player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "list.header"), false);
        for (AccountInfo info : accounts) {
            MutableComponent line = Component.translatable(KEY + "list.line", label(info), kind(info.kind()),
                    role(info.roleOf(player.getUUID()).orElse(AccountRole.MEMBER)), CoreApi.formatMoney(info.balance()));
            if (info.id().equals(active)) {
                line.append(Component.translatable(KEY + "list.active"));
            }
            ctx.getSource().sendSuccess(() -> line, false);
        }
        Map<AccountId, AccountRole> offers = CoreServices.ACCOUNTS.invitations(server, player.getUUID());
        offers.forEach((id, role) -> CoreApi.accounts().get(server, id).ifPresent(info -> ctx.getSource().sendSuccess(
                () -> Component.translatable(KEY + "list.invitation", info.name(), role(role)), false)));
        return accounts.size();
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<AccountInfo> found = mineNamed(ctx, player, info -> true);
        if (found.isEmpty()) {
            return 0;
        }
        AccountInfo info = found.get();
        MutableComponent members = Component.empty();
        for (int i = 0; i < info.members().size(); i++) {
            AccountMember member = info.members().get(i);
            if (i > 0) {
                members.append(", ");
            }
            members.append(Component.translatable(KEY + "info.member", AccountsService.name(player.server, member.player()), role(member.role())));
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "info", label(info), kind(info.kind()),
                AccountsService.name(player.server, info.owner()), members, CoreApi.formatMoney(info.balance())), false);
        // The owner also sees whom they have invited, so they can withdraw an invitation with "remove".
        Map<UUID, AccountRole> invited = info.owner().equals(player.getUUID()) ? CoreServices.ACCOUNTS.invitedTo(info.id()) : Map.of();
        if (!invited.isEmpty()) {
            MutableComponent list = Component.empty();
            invited.forEach((who, offered) -> {
                if (!list.getSiblings().isEmpty()) {
                    list.append(", ");
                }
                list.append(Component.translatable(KEY + "info.member", AccountsService.name(player.server, who), role(offered)));
            });
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "info.invited", list), false);
        }
        return 1;
    }

    // ---- Changing --------------------------------------------------------------------------------------------

    private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        AccountResult created = CoreApi.accounts().create(player.server, player.getUUID(), name);
        if (!created.ok()) {
            return result(ctx, created.result());
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "created", name), false);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<AccountInfo> found = ownedNamed(ctx, player);
        if (found.isEmpty()) {
            return 0;
        }
        BankResult result = CoreApi.accounts().delete(player.server, found.get().id());
        if (result != BankResult.OK) {
            return result(ctx, result);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "deleted", found.get().name()), false);
        return 1;
    }

    private static int use(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<AccountInfo> found = mineNamed(ctx, player, info -> true);
        if (found.isEmpty()) {
            return 0;
        }
        BankResult result = CoreApi.accounts().setActive(player, found.get().id());
        if (result != BankResult.OK) {
            return result(ctx, result);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "using", label(found.get())), false);
        return 1;
    }

    /** {@code role} null: the role argument, which must be member or deposit_only. */
    private static int invite(CommandContext<CommandSourceStack> ctx, AccountRole role) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        AccountRole offered = role;
        if (offered == null) {
            Optional<AccountRole> parsed = parseRole(StringArgumentType.getString(ctx, "role"));
            if (parsed.isEmpty() || parsed.get() == AccountRole.OWNER) {
                return fail(ctx, "bad_role");
            }
            offered = parsed.get();
        }
        Optional<AccountInfo> found = ownedNamed(ctx, player);
        if (found.isEmpty()) {
            return 0;
        }
        AccountInfo info = found.get();
        if (info.roleOf(target.getUUID()).isPresent()) {
            return fail(ctx, "already_member", target.getGameProfile().getName());
        }
        BankResult result = CoreServices.ACCOUNTS.invite(player.server, info.id(), target.getUUID(), offered);
        if (result != BankResult.OK) {
            return result(ctx, result);
        }
        target.sendSystemMessage(Component.translatable(KEY + "invited_you", player.getGameProfile().getName(), info.name(), role(offered)));
        AccountRole shown = offered;
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "invited", target.getGameProfile().getName(), info.name(),
                role(shown)), false);
        return 1;
    }

    /** Accepts ({@code join}) or declines an invitation. */
    private static int answer(CommandContext<CommandSourceStack> ctx, boolean join) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        Optional<AccountInfo> invited = CoreServices.ACCOUNTS.invitations(player.server, player.getUUID()).keySet().stream()
                .map(id -> CoreApi.accounts().get(player.server, id)).flatMap(Optional::stream)
                .filter(info -> info.name().equalsIgnoreCase(name)).findFirst();
        if (invited.isEmpty()) {
            return fail(ctx, "not_invited", name);
        }
        if (!join) {
            CoreServices.ACCOUNTS.decline(player.getUUID(), invited.get().id());
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "declined", invited.get().name()), false);
            return 1;
        }
        BankResult result = CoreServices.ACCOUNTS.accept(player.server, player.getUUID(), invited.get().id());
        if (result != BankResult.OK) {
            return result(ctx, result);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "joined", invited.get().name()), false);
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<AccountInfo> found = mineNamed(ctx, player, info -> info.kind() == AccountKind.NAMED);
        if (found.isEmpty()) {
            return 0;
        }
        AccountInfo info = found.get();
        BankResult result = CoreApi.accounts().removeMember(player.server, info.id(), player.getUUID());
        if (result == BankResult.NOT_ALLOWED) {
            return fail(ctx, "last_member", info.name());
        }
        if (result != BankResult.OK) {
            return result(ctx, result);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "left", info.name()), false);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<GameProfile> target = onePlayer(ctx);
        Optional<AccountInfo> found = target.isEmpty() ? Optional.empty() : ownedNamed(ctx, player);
        if (found.isEmpty()) {
            return 0;
        }
        if (target.get().getId().equals(player.getUUID())) {
            return fail(ctx, "remove_self");
        }
        if (found.get().roleOf(target.get().getId()).isEmpty()
                && CoreServices.ACCOUNTS.cancelInvite(found.get().id(), target.get().getId())) {
            // Not a member, but invited: the owner withdraws the invitation.
            ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "invite_cancelled", target.get().getName(),
                    found.get().name()), false);
            return 1;
        }
        BankResult result = CoreApi.accounts().removeMember(player.server, found.get().id(), target.get().getId());
        if (result != BankResult.OK) {
            return result(ctx, result);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "removed", target.get().getName(), found.get().name()), false);
        return 1;
    }

    private static int role(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<AccountRole> role = parseRole(StringArgumentType.getString(ctx, "role"));
        if (role.isEmpty()) {
            return fail(ctx, "bad_role");
        }
        Optional<GameProfile> target = onePlayer(ctx);
        Optional<AccountInfo> found = target.isEmpty() ? Optional.empty() : ownedNamed(ctx, player);
        if (found.isEmpty()) {
            return 0;
        }
        UUID id = target.get().getId();
        if (found.get().roleOf(id).isEmpty()) {
            return result(ctx, BankResult.NOT_A_MEMBER);
        }
        if (id.equals(player.getUUID())) {
            return fail(ctx, "own_role");
        }
        BankResult result = CoreApi.accounts().setMember(player.server, found.get().id(), id, role.get());
        if (result != BankResult.OK) {
            return result(ctx, result);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(KEY + "role_set", target.get().getName(), found.get().name(),
                role(role.get())), false);
        return 1;
    }

    // ---- Finding accounts ------------------------------------------------------------------------------------

    /**
     * The account called {@code name} among those {@code player} may use ({@code personal} for their own), or empty
     * after saying there is none.
     */
    private static Optional<AccountInfo> mineNamed(CommandContext<CommandSourceStack> ctx, ServerPlayer player,
                                                   Predicate<AccountInfo> which) {
        String name = StringArgumentType.getString(ctx, "name");
        Optional<AccountInfo> found = find(player.server, player.getUUID(), name).filter(which);
        if (found.isEmpty()) {
            fail(ctx, "not_yours", name);
        }
        return found;
    }

    /** As {@link #mineNamed}, for a named account the player owns; empty after saying why not. */
    private static Optional<AccountInfo> ownedNamed(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        Optional<AccountInfo> found = mineNamed(ctx, player, info -> true);
        if (found.isEmpty()) {
            return found;
        }
        if (found.get().kind() != AccountKind.NAMED) {
            fail(ctx, found.get().kind() == AccountKind.PARTY ? "party" : "personal");
            return Optional.empty();
        }
        if (!found.get().owner().equals(player.getUUID())) {
            fail(ctx, "owner_only", found.get().name());
            return Optional.empty();
        }
        return found;
    }

    /**
     * The account called {@code name} among those {@code player} may use: {@code personal} is their own, {@code party}
     * their FTB Teams party's; a named account by its name first, then a party account by its party's.
     */
    public static Optional<AccountInfo> find(MinecraftServer server, UUID player, String name) {
        List<AccountInfo> mine = CoreApi.accounts().accessibleBy(server, player);
        if (name.equalsIgnoreCase(AccountsService.PERSONAL_WORD)) {
            return mine.stream().filter(info -> info.kind() == AccountKind.PERSONAL).findFirst();
        }
        if (name.equalsIgnoreCase(AccountsService.PARTY_WORD)) {
            return mine.stream().filter(info -> info.kind() == AccountKind.PARTY).findFirst();
        }
        Optional<AccountInfo> named = mine.stream().filter(info -> info.kind() == AccountKind.NAMED && info.name().equalsIgnoreCase(name)).findFirst();
        return named.isPresent() ? named
                : mine.stream().filter(info -> info.kind() == AccountKind.PARTY && info.name().equalsIgnoreCase(name)).findFirst();
    }

    /** How commands name an account in suggestions: {@code personal}, {@code party}, or a named account's name. */
    static String word(AccountInfo info) {
        return switch (info.kind()) {
            case PERSONAL -> AccountsService.PERSONAL_WORD;
            case PARTY -> AccountsService.PARTY_WORD;
            case NAMED -> info.name();
        };
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> name(
            SuggestionProvider<CommandSourceStack> suggestions) {
        return Commands.argument("name", StringArgumentType.word()).suggests(suggestions);
    }

    /** Suggests the names of the player's accounts that pass {@code which}, {@code personal} for their own. */
    private static SuggestionProvider<CommandSourceStack> mine(Predicate<AccountInfo> which) {
        return (ctx, builder) -> {
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(CoreApi.accounts().accessibleBy(player.server, player.getUUID()).stream()
                    .filter(which).map(AccountCommands::word), builder);
        };
    }

    private static SuggestionProvider<CommandSourceStack> owned() {
        return (ctx, builder) -> {
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(CoreApi.accounts().accessibleBy(player.server, player.getUUID()).stream()
                    .filter(info -> info.kind() == AccountKind.NAMED && info.owner().equals(player.getUUID()))
                    .map(AccountInfo::name), builder);
        };
    }

    private static SuggestionProvider<CommandSourceStack> invitations() {
        return (ctx, builder) -> {
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) {
                return builder.buildFuture();
            }
            return SharedSuggestionProvider.suggest(CoreServices.ACCOUNTS.invitations(player.server, player.getUUID()).keySet().stream()
                    .map(id -> CoreApi.accounts().get(player.server, id)).flatMap(Optional::stream).map(AccountInfo::name), builder);
        };
    }

    private static SuggestionProvider<CommandSourceStack> roles(boolean withOwner) {
        return (ctx, builder) -> SharedSuggestionProvider.suggest(withOwner
                ? List.of("owner", "member", "deposit_only") : List.of("member", "deposit_only"), builder);
    }

    static Optional<AccountRole> parseRole(String text) {
        try {
            return Optional.of(AccountRole.valueOf(text.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<GameProfile> onePlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            fail(ctx, "one_player");
            return Optional.empty();
        }
        return Optional.of(profiles.iterator().next());
    }

    /** An account's name as players see it: a personal account's is "personal". */
    static Component label(AccountInfo info) {
        return info.kind() == AccountKind.PERSONAL ? Component.translatable(KEY + "personal_label") : Component.literal(info.name());
    }

    static Component kind(AccountKind kind) {
        return Component.translatable(KEY + "kind." + kind.name().toLowerCase(Locale.ROOT));
    }

    static Component role(AccountRole role) {
        return Component.translatable(KEY + "role." + role.name().toLowerCase(Locale.ROOT));
    }

    /** Says why a call was refused. */
    static int result(CommandContext<CommandSourceStack> ctx, BankResult result) {
        ctx.getSource().sendFailure(Component.translatable(KEY + "result." + result.name().toLowerCase(Locale.ROOT)));
        return 0;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        ctx.getSource().sendFailure(Component.translatable(KEY + key, args));
        return 0;
    }
}
