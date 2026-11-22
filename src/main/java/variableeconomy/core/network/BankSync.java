package variableeconomy.core.network;

import variableeconomy.core.CoreSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountInfo;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountMember;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.bank.Denomination;
import variableeconomy.core.bank.WorldBank;
import variableeconomy.core.menu.BankMenu;
import variableeconomy.core.service.AccountsService;
import variableeconomy.core.service.BankService;
import variableeconomy.core.service.CoreServices;
import variableeconomy.core.service.Leaderboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server side of the bank screen: keeps every open bank screen up to date, and handles its requests. Each viewer is
 * shown one of their accounts (their active account until they pick another); deposits and withdrawals act on it.
 * Requests from a player without the bank open are stale or forged, and ignored. Server thread only.
 */
public final class BankSync {
    /** How often to re-check viewers' balance and coins for changes made elsewhere (pickups, trades). */
    private static final int STATE_INTERVAL_TICKS = 20;
    /** Fewest ticks between one player's leaderboard requests, since each one reads every bank account. */
    private static final int LEADERBOARD_COOLDOWN_TICKS = 20;
    /**
     * Fewest ticks between one player's history replies. The screen asks again whenever the shown balance changes, which
     * on a busy shared account can be every tick; a request inside the cooldown waits and is answered when it ends.
     */
    public static final int HISTORY_COOLDOWN_TICKS = 20;
    /** Account names in the bank screen's packets are cut to this many characters; FTB Teams sets no limit. */
    private static final int NAME_LENGTH = 64;

    private static final Map<UUID, BankPayloads.BankState> lastSentState = new HashMap<>();
    private static final Map<UUID, Integer> lastLeaderboardRequest = new HashMap<>();
    /** When each viewer was last sent a history, and the request waiting for its cooldown to end, if any. */
    private static final Map<UUID, Integer> lastHistorySent = new HashMap<>();
    private static final Map<UUID, AccountId> pendingHistory = new HashMap<>();
    /** The account each viewer picked to see, when it isn't their active one. */
    private static final Map<UUID, AccountId> shown = new HashMap<>();

    private BankSync() {
    }

    public static List<ServerPlayer> viewers(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream()
                .filter(p -> p.containerMenu instanceof BankMenu)
                .toList();
    }

    static boolean viewing(ServerPlayer player) {
        return player.containerMenu instanceof BankMenu menu && menu.stillValid(player);
    }

    /** Call right after the bank screen opens. The screen asks for the leaderboard itself. */
    public static void sendFullState(ServerPlayer player) {
        // A newly opened screen starts empty, so resend even if nothing changed since the last send. It asks for the
        // leaderboard once, so that request mustn't fall within the cooldown of one from a screen just closed.
        lastSentState.remove(player.getUUID());
        lastLeaderboardRequest.remove(player.getUUID());
        shown.remove(player.getUUID());
        lastHistorySent.remove(player.getUUID());
        pendingHistory.remove(player.getUUID());
        sendState(player);
    }

    /** Sends a viewer their accounts and coins, but only if they changed since the last send. */
    public static void sendState(ServerPlayer player) {
        BankPayloads.BankState state = stateFor(player);
        if (!state.equals(lastSentState.get(player.getUUID()))) {
            lastSentState.put(player.getUUID(), state);
            PacketDistributor.sendToPlayer(player, state);
        }
    }

    /**
     * The account the player's bank screen shows: the one they picked while they may still use it in any role,
     * otherwise their active account.
     */
    public static AccountId shownAccount(ServerPlayer player) {
        AccountId picked = shown.get(player.getUUID());
        if (picked != null && CoreApi.accounts().role(player.server, picked, player.getUUID()).isPresent()) {
            return picked;
        }
        shown.remove(player.getUUID());
        return CoreApi.accounts().active(player);
    }

    /** What the player's bank screen shows: their accounts, the shown one's members, their coins and room for more. */
    public static BankPayloads.BankState stateFor(ServerPlayer player) {
        MinecraftServer server = player.server;
        UUID me = player.getUUID();
        AccountId active = CoreApi.accounts().active(player);
        AccountId showing = shownAccount(player);
        List<BankPayloads.AccountLine> accounts = new ArrayList<>();
        List<BankPayloads.MemberLine> members = new ArrayList<>();
        for (AccountInfo info : CoreApi.accounts().accessibleBy(server, me)) {
            AccountRole role = info.roleOf(me).orElse(AccountRole.DEPOSIT_ONLY);
            accounts.add(new BankPayloads.AccountLine(info.id().value(), cut(info.name()), info.kind(), role, info.balance(), info.owner()));
            if (info.id().equals(showing) && info.kind() != AccountKind.PERSONAL) {
                for (AccountMember member : info.members()) {
                    members.add(new BankPayloads.MemberLine(member.player(), cut(AccountsService.name(server, member.player())), member.role(), false));
                }
                if (info.owner().equals(me) && info.kind() == AccountKind.NAMED) {
                    // The owner also sees the invitations still open, to withdraw them.
                    // The packet holds at most MAX_MEMBER_LINES lines.
                    CoreServices.ACCOUNTS.invitedTo(info.id()).forEach((invitee, offered) -> {
                        if (members.size() < BankPayloads.MAX_MEMBER_LINES) {
                            members.add(new BankPayloads.MemberLine(invitee, cut(AccountsService.name(server, invitee)), offered, true));
                        }
                    });
                }
            }
        }
        List<BankPayloads.InviteLine> invites = new ArrayList<>();
        CoreServices.ACCOUNTS.invitations(server, me).forEach((id, role) -> CoreApi.accounts().get(server, id)
                .ifPresent(info -> invites.add(new BankPayloads.InviteLine(id.value(), cut(info.name()), role))));

        List<Denomination> coins = WorldBank.of(server).coins();
        Map<ResourceLocation, Integer> held = new HashMap<>();
        Map<ResourceLocation, Integer> stackRoom = new HashMap<>();
        int emptySlots = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            for (Denomination coin : coins) {
                if (BankService.isPlain(stack, coin)) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(coin.item());
                    held.merge(id, stack.getCount(), Integer::sum);
                    stackRoom.merge(id, Math.max(0, stack.getMaxStackSize() - stack.getCount()), Integer::sum);
                }
            }
        }
        return new BankPayloads.BankState(accounts, showing.value(), active.value(), members, invites, held, emptySlots, stackRoom,
                CoreSettings.leaderboardEnabled(), CoreSettings.extraAccountsEnabled(), CoreSettings.sharedAccountsEnabled());
    }

    private static String cut(String name) {
        return name.length() > NAME_LENGTH ? name.substring(0, NAME_LENGTH) : name;
    }

    /** Every tick: forgets players who closed the screen, and refreshes the rest once a second. */
    public static void tick(MinecraftServer server) {
        sendDueHistories(server);
        if (server.getTickCount() % STATE_INTERVAL_TICKS != 0) {
            return;
        }
        List<ServerPlayer> viewers = viewers(server);
        Set<UUID> open = new HashSet<>();
        viewers.forEach(v -> open.add(v.getUUID()));
        lastSentState.keySet().retainAll(open);
        lastLeaderboardRequest.keySet().retainAll(open);
        shown.keySet().retainAll(open);
        lastHistorySent.keySet().retainAll(open);
        pendingHistory.keySet().retainAll(open);
        viewers.forEach(BankSync::sendState);
    }

    /** Answers the history requests whose cooldown has ended, for players who still have the bank open. */
    private static void sendDueHistories(MinecraftServer server) {
        if (pendingHistory.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        for (UUID id : List.copyOf(pendingHistory.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || !viewing(player)) {
                pendingHistory.remove(id);
            } else if (historyDue(id, now)) {
                AccountId account = pendingHistory.remove(id);
                lastHistorySent.put(id, now);
                PacketDistributor.sendToPlayer(player, historyFor(player, account));
            }
        }
    }

    /** Whether {@code player} may be sent a history at tick {@code now}: none was sent within the cooldown. */
    public static boolean historyDue(UUID player, int now) {
        Integer last = lastHistorySent.get(player);
        return last == null || now < last || now - last >= HISTORY_COOLDOWN_TICKS;
    }

    /** After an account's balance changed: viewers who can see that account see it at once. */
    public static void balanceChanged(MinecraftServer server, AccountId account) {
        for (ServerPlayer viewer : viewers(server)) {
            if (CoreApi.accounts().role(server, account, viewer.getUUID()).isPresent()) {
                sendState(viewer);
            }
        }
    }

    /** After accounts or members changed: every viewer is brought up to date. */
    public static void accountsChanged(MinecraftServer server) {
        viewers(server).forEach(BankSync::sendState);
    }

    // ---- Requests --------------------------------------------------------------------------------------------

    /** Deposit or withdraw from the open bank screen, whatever opened it, on the account it shows. */
    public static void handleBankRequest(BankPayloads.BankRequest request, ServerPlayer player) {
        if (!viewing(player)) {
            return;
        }
        AccountId account = shownAccount(player);
        BankService.Outcome outcome = request.deposit() ? BankService.depositAll(player, account)
                : request.coin().isPresent()
                ? BankService.withdrawCoins(player, request.coin().get(), (int) Math.min(request.amount(), Integer.MAX_VALUE), account)
                : BankService.withdraw(player, request.amount(), account);
        PacketDistributor.sendToPlayer(player, new BankPayloads.BankOutcome(request.deposit(), outcome.result(), outcome.amount()));
        sendState(player);
    }

    /** Shows another of the player's accounts. Ignored for one they can't use. */
    public static void handleShowAccount(BankPayloads.ShowAccountRequest request, ServerPlayer player) {
        if (!viewing(player)) {
            return;
        }
        show(player, new AccountId(request.account()));
        sendState(player);
    }

    /** Shows {@code id} in the player's bank screen, if they may use it in any role. Returns whether it is shown now. */
    public static boolean show(ServerPlayer player, AccountId id) {
        if (CoreApi.accounts().role(player.server, id, player.getUUID()).isEmpty()) {
            return false;
        }
        shown.put(player.getUUID(), id);
        return true;
    }

    /** An account change from the bank or Accounts screen. */
    public static void handleAccountRequest(BankPayloads.AccountRequest request, ServerPlayer player) {
        if (!viewing(player)) {
            return;
        }
        BankPayloads.AccountOutcome outcome = AccountRequests.handle(request, player);
        PacketDistributor.sendToPlayer(player, outcome);
        if (outcome.ok() && request.action() == BankPayloads.AccountAction.CREATE) {
            // Show the new account straight away.
            CoreApi.accounts().accessibleBy(player.server, player.getUUID()).stream()
                    .filter(info -> info.kind() == AccountKind.NAMED && info.name().equalsIgnoreCase(request.text()))
                    .findFirst().ifPresent(info -> shown.put(player.getUUID(), info.id()));
        }
        accountsChanged(player.server);
    }

    /** An account's history, if the player may read it: an owner or member, not a deposit-only member. */
    public static void handleHistoryRequest(BankPayloads.HistoryRequest request, ServerPlayer player) {
        if (!viewing(player)) {
            return;
        }
        AccountId account = new AccountId(request.account());
        int now = player.server.getTickCount();
        if (!historyDue(player.getUUID(), now)) {
            // Answered when the cooldown ends; a newer request replaces an older one.
            pendingHistory.put(player.getUUID(), account);
            return;
        }
        pendingHistory.remove(player.getUUID());
        lastHistorySent.put(player.getUUID(), now);
        PacketDistributor.sendToPlayer(player, historyFor(player, account));
    }

    /** Records a history reply sent at {@code tick}, as a test would see the cooldown start. */
    public static void historySentForTesting(UUID player, int tick) {
        lastHistorySent.put(player, tick);
    }

    /** What the player's bank screen shows of an account's history. */
    public static BankPayloads.History historyFor(ServerPlayer player, AccountId account) {
        if (CoreSettings.historySize() == 0) {
            return new BankPayloads.History(account.value(), BankPayloads.HistoryAccess.OFF, List.of());
        }
        boolean readable = CoreApi.accounts().role(player.server, account, player.getUUID())
                .map(variableeconomy.core.command.HistoryCommands::canRead).orElse(false);
        if (!readable) {
            return new BankPayloads.History(account.value(), BankPayloads.HistoryAccess.NOT_ALLOWED, List.of());
        }
        List<BankPayloads.HistoryLine> lines = new ArrayList<>();
        Map<UUID, String> names = new HashMap<>();
        for (variableeconomy.core.data.TransactionHistory.Line line
                : variableeconomy.core.data.TransactionHistory.get(player.server).of(account.value())) {
            String actor = line.actor().map(id -> names.computeIfAbsent(id, who -> cut(AccountsService.name(player.server, who)))).orElse("");
            lines.add(new BankPayloads.HistoryLine(line.time(), line.amount(), line.reason().toString(), actor, line.balance()));
        }
        return new BankPayloads.History(account.value(), BankPayloads.HistoryAccess.OK, lines);
    }

    /** The leaderboard as this player sees it, ranked now. Read only on request: when the screen opens. */
    public static void handleLeaderboardRequest(ServerPlayer player) {
        if (!viewing(player) || !CoreSettings.leaderboardEnabled()) {
            return;
        }
        int now = player.server.getTickCount();
        Integer last = lastLeaderboardRequest.get(player.getUUID());
        if (last != null && now >= last && now - last < LEADERBOARD_COOLDOWN_TICKS) {
            return;
        }
        lastLeaderboardRequest.put(player.getUUID(), now);
        Leaderboard.View view = Leaderboard.viewFor(player);
        List<BankPayloads.LeaderboardLine> lines = view.top().stream()
                .map(entry -> new BankPayloads.LeaderboardLine(entry.name(), entry.balance(), entry.id().equals(view.you())))
                .toList();
        PacketDistributor.sendToPlayer(player, new BankPayloads.Leaderboard(lines, view.ownRank(), view.ranked()));
    }

    /** Forgets every viewer, when the server stops. */
    public static void clear() {
        lastSentState.clear();
        lastLeaderboardRequest.clear();
        shown.clear();
        lastHistorySent.clear();
        pendingHistory.clear();
    }

    /** {@code player}'s shown account, as a test sees it: empty when they picked none. */
    public static Optional<AccountId> pickedForTesting(ServerPlayer player) {
        return Optional.ofNullable(shown.get(player.getUUID()));
    }
}
