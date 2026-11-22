package variableeconomy.core.network;

import variableeconomy.core.CoreModule;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import variableeconomy.core.service.BankService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountRole;

/** Every packet the bank sends. Amounts and balances are in bits. */
public final class BankPayloads {
    private BankPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CoreModule.MOD_ID, path));
    }

    /**
     * One account the player may use, as the bank screen lists it.
     *
     * @param name    a named or party account's name; a personal account's is its player's name
     * @param balance its balance, in bits
     */
    public record AccountLine(UUID id, String name, AccountKind kind, AccountRole role, long balance, UUID owner) {
        static final StreamCodec<ByteBuf, AccountLine> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, AccountLine::id,
                ByteBufCodecs.stringUtf8(64), AccountLine::name,
                ByteBufCodecs.idMapper(i -> AccountKind.values()[i], AccountKind::ordinal), AccountLine::kind,
                ByteBufCodecs.idMapper(i -> AccountRole.values()[i], AccountRole::ordinal), AccountLine::role,
                ByteBufCodecs.VAR_LONG, AccountLine::balance,
                UUIDUtil.STREAM_CODEC, AccountLine::owner,
                AccountLine::new);
    }

    /**
     * One member of the shown account, longest-standing first; for its owner, then each player invited who hasn't
     * answered yet ({@code invited}, with the role offered).
     */
    public record MemberLine(UUID player, String name, AccountRole role, boolean invited) {
        static final StreamCodec<ByteBuf, MemberLine> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, MemberLine::player,
                ByteBufCodecs.stringUtf8(64), MemberLine::name,
                ByteBufCodecs.idMapper(i -> AccountRole.values()[i], AccountRole::ordinal), MemberLine::role,
                ByteBufCodecs.BOOL, MemberLine::invited,
                MemberLine::new);
    }

    /** The most member lines one bank state carries: members, then (for the owner) open invitations. */
    public static final int MAX_MEMBER_LINES = 256;

    /** An invitation the player holds: an account and the role it offers. */
    public record InviteLine(UUID account, String name, AccountRole role) {
        static final StreamCodec<ByteBuf, InviteLine> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, InviteLine::account,
                ByteBufCodecs.stringUtf8(64), InviteLine::name,
                ByteBufCodecs.idMapper(i -> AccountRole.values()[i], AccountRole::ordinal), InviteLine::role,
                InviteLine::new);
    }

    /**
     * Server to client: the accounts the player may use (personal first), which one the screen shows (its balance and
     * members) and which is active, their invitations, how many plain copies of each coin they carry and how much room
     * their main inventory has for coins (empty slots, plus the space left in stacks of each coin), and the server's
     * switches. The client can't work the coins out itself: vanilla doesn't sync the inventory while a slotless menu is
     * open.
     */
    public record BankState(List<AccountLine> accounts, UUID shown, UUID active, List<MemberLine> members,
                            List<InviteLine> invites, Map<ResourceLocation, Integer> coins, int emptySlots,
                            Map<ResourceLocation, Integer> stackRoom, boolean leaderboardEnabled, boolean extraEnabled,
                            boolean sharedEnabled) implements CustomPacketPayload {
        public static final Type<BankState> TYPE = payloadType("bank_state");
        private static final StreamCodec<ByteBuf, List<AccountLine>> ACCOUNTS = AccountLine.CODEC.apply(ByteBufCodecs.list(256));
        private static final StreamCodec<ByteBuf, List<MemberLine>> MEMBERS = MemberLine.CODEC.apply(ByteBufCodecs.list(MAX_MEMBER_LINES));
        private static final StreamCodec<ByteBuf, List<InviteLine>> INVITES = InviteLine.CODEC.apply(ByteBufCodecs.list(256));
        // Written out by hand: StreamCodec.composite takes at most six fields.
        public static final StreamCodec<RegistryFriendlyByteBuf, BankState> CODEC = StreamCodec.of(
                (buf, state) -> {
                    ACCOUNTS.encode(buf, state.accounts());
                    UUIDUtil.STREAM_CODEC.encode(buf, state.shown());
                    UUIDUtil.STREAM_CODEC.encode(buf, state.active());
                    MEMBERS.encode(buf, state.members());
                    INVITES.encode(buf, state.invites());
                    COUNTS_CODEC.encode(buf, state.coins());
                    ByteBufCodecs.VAR_INT.encode(buf, state.emptySlots());
                    COUNTS_CODEC.encode(buf, state.stackRoom());
                    buf.writeBoolean(state.leaderboardEnabled());
                    buf.writeBoolean(state.extraEnabled());
                    buf.writeBoolean(state.sharedEnabled());
                },
                buf -> new BankState(ACCOUNTS.decode(buf), UUIDUtil.STREAM_CODEC.decode(buf), UUIDUtil.STREAM_CODEC.decode(buf),
                        MEMBERS.decode(buf), INVITES.decode(buf), COUNTS_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        COUNTS_CODEC.decode(buf), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));

        /** The shown account's line, if the player may still use it. */
        public Optional<AccountLine> shownLine() {
            return accounts.stream().filter(line -> line.id().equals(shown)).findFirst();
        }

        @Override
        public Type<BankState> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<ByteBuf, Map<ResourceLocation, Integer>> COUNTS_CODEC =
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT);

    /**
     * Client to server: deposit every coin in the main inventory, or withdraw
     * {@code amount} as the fewest coins, or, with {@code coin}, {@code amount} of exactly that coin.
     */
    public record BankRequest(boolean deposit, long amount, Optional<Item> coin) implements CustomPacketPayload {
        public static final Type<BankRequest> TYPE = payloadType("bank_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, BankRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, BankRequest::deposit,
                ByteBufCodecs.VAR_LONG, BankRequest::amount,
                ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.ITEM)), BankRequest::coin,
                BankRequest::new);

        /** Withdraws {@code count} of exactly this coin, instead of an amount as the fewest coins. */
        public static BankRequest withdrawCoins(Item coin, int count) {
            return new BankRequest(false, count, Optional.of(coin));
        }

        @Override
        public Type<BankRequest> type() {
            return TYPE;
        }
    }

    /** Client to server: show this account in the bank screen (its balance, its members, where coins go). */
    public record ShowAccountRequest(UUID account) implements CustomPacketPayload {
        public static final Type<ShowAccountRequest> TYPE = payloadType("show_account_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ShowAccountRequest> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ShowAccountRequest::account,
                ShowAccountRequest::new);

        @Override
        public Type<ShowAccountRequest> type() {
            return TYPE;
        }
    }

    /** What the bank and Accounts screens ask for. */
    public enum AccountAction { USE, CREATE, DELETE, INVITE, ACCEPT, DECLINE, LEAVE, REMOVE, SET_ROLE }

    /**
     * Client to server: change an account. {@code account} is the account acted on ({@link AccountAction#CREATE}
     * ignores it); {@code text} is the new account's name ({@code CREATE}) or the player to invite ({@code INVITE});
     * {@code player} the member acted on ({@code REMOVE}, {@code SET_ROLE}); {@code role} the role offered or given.
     */
    public record AccountRequest(AccountAction action, UUID account, String text, UUID player, AccountRole role)
            implements CustomPacketPayload {
        public static final Type<AccountRequest> TYPE = payloadType("account_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, AccountRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.idMapper(i -> AccountAction.values()[i], AccountAction::ordinal), AccountRequest::action,
                UUIDUtil.STREAM_CODEC, AccountRequest::account,
                ByteBufCodecs.stringUtf8(32), AccountRequest::text,
                UUIDUtil.STREAM_CODEC, AccountRequest::player,
                ByteBufCodecs.idMapper(i -> AccountRole.values()[i], AccountRole::ordinal), AccountRequest::role,
                AccountRequest::new);

        /** A request about one account, with no text, player or role. */
        public static AccountRequest of(AccountAction action, UUID account) {
            return new AccountRequest(action, account, "", account, AccountRole.MEMBER);
        }

        @Override
        public Type<AccountRequest> type() {
            return TYPE;
        }
    }

    /** Server to client: how an account request went, as a translation key and the name it's about. */
    public record AccountOutcome(boolean ok, String key, String name) implements CustomPacketPayload {
        public static final Type<AccountOutcome> TYPE = payloadType("account_outcome");
        public static final StreamCodec<RegistryFriendlyByteBuf, AccountOutcome> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, AccountOutcome::ok,
                ByteBufCodecs.stringUtf8(128), AccountOutcome::key,
                ByteBufCodecs.stringUtf8(64), AccountOutcome::name,
                AccountOutcome::new);

        @Override
        public Type<AccountOutcome> type() {
            return TYPE;
        }
    }

    /** Server to client: how a bank request went, and the amount moved. */
    public record BankOutcome(boolean deposit, BankService.Result result, long amount) implements CustomPacketPayload {
        public static final Type<BankOutcome> TYPE = payloadType("bank_outcome");
        public static final StreamCodec<RegistryFriendlyByteBuf, BankOutcome> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, BankOutcome::deposit,
                ByteBufCodecs.idMapper(i -> BankService.Result.values()[i], BankService.Result::ordinal), BankOutcome::result,
                ByteBufCodecs.VAR_LONG, BankOutcome::amount,
                BankOutcome::new);

        @Override
        public Type<BankOutcome> type() {
            return TYPE;
        }
    }

    /** Client to server: send me this account's history (the screen asks when it shows the history list). */
    public record HistoryRequest(UUID account) implements CustomPacketPayload {
        public static final Type<HistoryRequest> TYPE = payloadType("history_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, HistoryRequest> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, HistoryRequest::account,
                HistoryRequest::new);

        @Override
        public Type<HistoryRequest> type() {
            return TYPE;
        }
    }

    /**
     * One history line: when (milliseconds since the epoch), the signed change, the reason's ID, the name of the player
     * who caused it ("" if none) and the balance after.
     */
    public record HistoryLine(long time, long amount, String reason, String actor, long balance) {
        static final StreamCodec<ByteBuf, HistoryLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, HistoryLine::time,
                ByteBufCodecs.VAR_LONG, HistoryLine::amount,
                ByteBufCodecs.stringUtf8(256), HistoryLine::reason,
                ByteBufCodecs.stringUtf8(64), HistoryLine::actor,
                ByteBufCodecs.VAR_LONG, HistoryLine::balance,
                HistoryLine::new);
    }

    /** Whether the player may read an account's history. */
    public enum HistoryAccess { OK, NOT_ALLOWED, OFF }

    /** Server to client: an account's history, newest first, or why it can't be read. */
    public record History(UUID account, HistoryAccess access, List<HistoryLine> lines) implements CustomPacketPayload {
        public static final Type<History> TYPE = payloadType("history");
        public static final StreamCodec<RegistryFriendlyByteBuf, History> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, History::account,
                ByteBufCodecs.idMapper(i -> HistoryAccess.values()[i], HistoryAccess::ordinal), History::access,
                HistoryLine.CODEC.apply(ByteBufCodecs.list(1000)), History::lines,
                History::new);

        @Override
        public Type<History> type() {
            return TYPE;
        }
    }

    /** Client to server: send me the leaderboard (the screen asks when it opens). */
    public record LeaderboardRequest() implements CustomPacketPayload {
        public static final Type<LeaderboardRequest> TYPE = payloadType("leaderboard_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, LeaderboardRequest> CODEC = StreamCodec.unit(new LeaderboardRequest());

        @Override
        public Type<LeaderboardRequest> type() {
            return TYPE;
        }
    }

    /** One leaderboard line: the player's name, their balance, and whether it's the player receiving it. */
    public record LeaderboardLine(String name, long balance, boolean you) {
        static final StreamCodec<ByteBuf, LeaderboardLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), LeaderboardLine::name,
                ByteBufCodecs.VAR_LONG, LeaderboardLine::balance,
                ByteBufCodecs.BOOL, LeaderboardLine::you,
                LeaderboardLine::new);
    }

    /**
     * Server to client: the richest players, richest first, and the receiving player's own rank (0 when they aren't
     * ranked) out of {@code ranked} players with money.
     */
    public record Leaderboard(List<LeaderboardLine> lines, int ownRank, int ranked) implements CustomPacketPayload {
        public static final Type<Leaderboard> TYPE = payloadType("leaderboard");
        public static final StreamCodec<RegistryFriendlyByteBuf, Leaderboard> CODEC = StreamCodec.composite(
                LeaderboardLine.CODEC.apply(ByteBufCodecs.list(100)), Leaderboard::lines,
                ByteBufCodecs.VAR_INT, Leaderboard::ownRank,
                ByteBufCodecs.VAR_INT, Leaderboard::ranked,
                Leaderboard::new);

        @Override
        public Type<Leaderboard> type() {
            return TYPE;
        }
    }

}
