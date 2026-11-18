package variableeconomy.core.network;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountInfo;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountResult;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.service.AccountsService;
import variableeconomy.core.service.CoreServices;
import variableeconomy.core.service.Leaderboard;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The bank and Accounts screens' account changes, checked the way {@code /bank account} checks them: only an
 * account's owner manages it, nobody manages a personal or party account, and invitations are accepted by the
 * invited player. Each answers with the translation key players see. Server thread only.
 */
public final class AccountRequests {
    private static final String KEY = "command.variable_economy.account.";
    /** Successes, each with one argument: the account or player it's about. */
    private static final String DONE = "screen.variable_economy.accounts.done.";

    private AccountRequests() {
    }

    /** Carries out one request from a player with the bank open, and says how it went. */
    public static BankPayloads.AccountOutcome handle(BankPayloads.AccountRequest request, ServerPlayer player) {
        MinecraftServer server = player.server;
        UUID me = player.getUUID();
        AccountId id = new AccountId(request.account());
        return switch (request.action()) {
            case CREATE -> create(player, request.text());
            case ACCEPT -> answer(player, id, true);
            case DECLINE -> answer(player, id, false);
            default -> {
                Optional<AccountInfo> found = CoreApi.accounts().get(server, id).filter(info -> info.roleOf(me).isPresent());
                if (found.isEmpty()) {
                    yield fail("not_yours", "");
                }
                AccountInfo info = found.get();
                yield switch (request.action()) {
                    case USE -> result(CoreApi.accounts().setActive(player, id), "using", label(info));
                    case LEAVE -> leave(player, info);
                    default -> manage(player, info, request);
                };
            }
        };
    }

    private static BankPayloads.AccountOutcome create(ServerPlayer player, String name) {
        AccountResult created = CoreApi.accounts().create(player.server, player.getUUID(), name);
        return result(created.result(), "created", name);
    }

    private static BankPayloads.AccountOutcome answer(ServerPlayer player, AccountId id, boolean join) {
        Optional<AccountRole> offered = Optional.ofNullable(CoreServices.ACCOUNTS.invitations(player.server, player.getUUID()).get(id));
        Optional<AccountInfo> info = CoreApi.accounts().get(player.server, id);
        if (offered.isEmpty() || info.isEmpty()) {
            return fail("not_invited", "");
        }
        if (!join) {
            CoreServices.ACCOUNTS.decline(player.getUUID(), id);
            return ok("declined", info.get().name());
        }
        return result(CoreServices.ACCOUNTS.accept(player.server, player.getUUID(), id), "joined", info.get().name());
    }

    private static BankPayloads.AccountOutcome leave(ServerPlayer player, AccountInfo info) {
        if (info.kind() != AccountKind.NAMED) {
            return fail(info.kind() == AccountKind.PARTY ? "party" : "personal", "");
        }
        BankResult result = CoreApi.accounts().removeMember(player.server, info.id(), player.getUUID());
        if (result == BankResult.NOT_ALLOWED) {
            return fail("last_member", info.name());
        }
        return result(result, "left", info.name());
    }

    /** Owner-only changes: delete, invite, remove a member, change a role. */
    private static BankPayloads.AccountOutcome manage(ServerPlayer player, AccountInfo info, BankPayloads.AccountRequest request) {
        MinecraftServer server = player.server;
        if (info.kind() != AccountKind.NAMED) {
            return fail(info.kind() == AccountKind.PARTY ? "party" : "personal", "");
        }
        if (!info.owner().equals(player.getUUID())) {
            return fail("owner_only", info.name());
        }
        return switch (request.action()) {
            case DELETE -> result(CoreApi.accounts().delete(server, info.id()), "deleted", info.name());
            case INVITE -> invite(player, info, request.text(), request.role());
            case REMOVE -> {
                if (request.player().equals(player.getUUID())) {
                    yield fail("remove_self", "");
                }
                yield result(CoreApi.accounts().removeMember(server, info.id(), request.player()), "removed",
                        AccountsService.name(server, request.player()));
            }
            case SET_ROLE -> {
                if (request.player().equals(player.getUUID())) {
                    yield fail("own_role", "");
                }
                if (info.roleOf(request.player()).isEmpty()) {
                    yield result(BankResult.NOT_A_MEMBER, "", "");
                }
                yield result(CoreApi.accounts().setMember(server, info.id(), request.player(), request.role()), "role_set",
                        AccountsService.name(server, request.player()));
            }
            default -> fail("not_yours", "");
        };
    }

    private static BankPayloads.AccountOutcome invite(ServerPlayer player, AccountInfo info, String name, AccountRole role) {
        MinecraftServer server = player.server;
        if (role == AccountRole.OWNER) {
            return fail("bad_role", "");
        }
        Optional<GameProfile> target = profile(server, name);
        if (target.isEmpty()) {
            return fail("unknown_player", name);
        }
        UUID id = target.get().getId();
        if (info.roleOf(id).isPresent()) {
            return fail("already_member", target.get().getName());
        }
        CoreServices.ACCOUNTS.seenPlayer(server, id);
        BankResult result = CoreServices.ACCOUNTS.invite(server, info.id(), id, role);
        if (result == BankResult.OK) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                online.sendSystemMessage(Component.translatable(KEY + "invited_you", player.getGameProfile().getName(), info.name(),
                        Component.translatable(KEY + "role." + role.name().toLowerCase(Locale.ROOT))));
            }
        }
        return result(result, "invited", target.get().getName());
    }

    /** A player by name: online, or one the server has seen. */
    private static Optional<GameProfile> profile(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        return server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(name)
                .filter(profile -> Leaderboard.profile(server, profile.getId()).isPresent());
    }

    private static String label(AccountInfo info) {
        return info.kind() == AccountKind.PERSONAL ? AccountsService.PERSONAL_WORD : info.name();
    }

    private static BankPayloads.AccountOutcome result(BankResult result, String okKey, String name) {
        return result == BankResult.OK ? ok(okKey, name)
                : new BankPayloads.AccountOutcome(false, KEY + "result." + result.name().toLowerCase(Locale.ROOT), name);
    }

    private static BankPayloads.AccountOutcome ok(String key, String name) {
        return new BankPayloads.AccountOutcome(true, DONE + key, name);
    }

    private static BankPayloads.AccountOutcome fail(String key, String name) {
        return new BankPayloads.AccountOutcome(false, KEY + key, name);
    }
}
