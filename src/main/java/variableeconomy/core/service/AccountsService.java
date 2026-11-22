package variableeconomy.core.service;

import variableeconomy.core.CoreModule;
import variableeconomy.core.CoreSettings;
import com.mojang.authlib.GameProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import variableeconomy.core.bank.OwnBank;
import variableeconomy.core.bank.Party;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.AccountInfo;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountMember;
import variableeconomy.core.api.AccountResult;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.api.Accounts;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.Reason;
import variableeconomy.core.api.event.AccountEvent;
import variableeconomy.core.compat.FtbTeamsBridge;
import variableeconomy.core.data.AccountRegistry;
import variableeconomy.core.data.OwedPayments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Core's accounts: every player's personal account, the named accounts players open and share (when the server allows
 * them), and FTB Teams parties' accounts. Each player's active account is the one they picked, while they may still
 * spend from it, otherwise their personal account. The records live in {@link AccountRegistry}, the balances in the
 * bank.
 * <p>
 * The {@link Accounts} calls act with the server's authority; the commands and the bank screen check who asks.
 * Invitations, which players accept before joining an account, are kept here until the server stops; the account's
 * owner can withdraw them, and they end when the account ends or changes owner. Server thread only.
 */
public final class AccountsService implements Accounts {
    /** The reason for an ended account's balance passing to a player. */
    public static final ResourceLocation ACCOUNT_ENDED = ResourceLocation.fromNamespaceAndPath(CoreModule.MOD_ID, "account_ended");
    /** Letters, digits, '_' and '-', 1 to 24 of them: one word, so commands can take it unquoted. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");
    /** The word commands use for a player's personal account. */
    public static final String PERSONAL_WORD = "personal";
    /** The word commands use for a player's FTB Teams party's account. */
    public static final String PARTY_WORD = "party";

    /** Pending invitations: invited player, then account, then the role offered. Not saved. */
    private final Map<UUID, Map<AccountId, AccountRole>> invites = new HashMap<>();

    @Override
    public AccountId personal(UUID player) {
        return new AccountId(Objects.requireNonNull(player, "player"));
    }

    @Override
    public Optional<AccountInfo> get(MinecraftServer server, AccountId id) {
        CoreServices.requireServerThread(server);
        Objects.requireNonNull(id, "id");
        Optional<AccountRegistry.Entry> entry = live(server, id);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        return switch (entry.get().kind()) {
            case PERSONAL -> Optional.of(personalInfo(server, id.value()));
            case NAMED -> Optional.of(namedInfo(server, id, entry.get()));
            case PARTY -> party(entry.get()).map(party -> partyInfo(server, id, party));
        };
    }

    @Override
    public List<AccountInfo> accessibleBy(MinecraftServer server, UUID player) {
        CoreServices.requireServerThread(server);
        Objects.requireNonNull(player, "player");
        List<AccountInfo> others = new ArrayList<>();
        AccountRegistry registry = AccountRegistry.get(server);
        registry.live().forEach((id, entry) -> {
            if (entry.kind() == AccountKind.NAMED && entry.roleOf(player).isPresent()) {
                others.add(namedInfo(server, new AccountId(id), entry));
            }
        });
        partyAccountOf(server, player).flatMap(id -> get(server, id)).ifPresent(others::add);
        others.sort(Comparator.comparing(AccountInfo::name, String.CASE_INSENSITIVE_ORDER));
        List<AccountInfo> accounts = new ArrayList<>();
        if (registry.isPersonal(player)) {
            accounts.add(personalInfo(server, player));
        }
        accounts.addAll(others);
        return accounts;
    }

    @Override
    public AccountId active(ServerPlayer player) {
        CoreServices.requireServerThread(player.server);
        seen(player);
        return activeOf(player.server, player.getUUID());
    }

    /**
     * {@code player}'s active account, online or not: the one they picked while they may still spend from it, otherwise
     * their personal account (and the stale choice is forgotten).
     */
    public AccountId activeOf(MinecraftServer server, UUID player) {
        AccountRegistry registry = AccountRegistry.get(server);
        Optional<UUID> chosen = registry.chosenActive(player);
        if (chosen.isPresent()) {
            AccountId id = new AccountId(chosen.get());
            if (role(server, id, player).filter(AccountRole::canSpend).isPresent()) {
                return id;
            }
            registry.setActive(player, player);
        }
        return personal(player);
    }

    @Override
    public BankResult setActive(ServerPlayer player, AccountId id) {
        CoreServices.requireServerThread(player.server);
        Objects.requireNonNull(id, "id");
        seen(player);
        AccountRegistry registry = AccountRegistry.get(player.server);
        if (id.value().equals(player.getUUID())) {
            registry.setActive(player.getUUID(), player.getUUID());
            return BankResult.OK;
        }
        Optional<AccountInfo> info = get(player.server, id);
        if (info.isEmpty()) {
            return BankResult.NO_ACCOUNT;
        }
        if (info.get().roleOf(player.getUUID()).filter(AccountRole::canSpend).isEmpty()) {
            return BankResult.NOT_ALLOWED;
        }
        registry.setActive(player.getUUID(), id.value());
        return BankResult.OK;
    }

    @Override
    public Optional<AccountRole> role(MinecraftServer server, AccountId id, UUID player) {
        Objects.requireNonNull(player, "player");
        return get(server, id).flatMap(info -> info.roleOf(player));
    }

    @Override
    public AccountResult create(MinecraftServer server, UUID owner, String name) {
        CoreServices.requireServerThread(server);
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        if (!CoreSettings.extraAccountsEnabled()) {
            return new AccountResult(BankResult.DISABLED, Optional.empty());
        }
        if (!validName(name)) {
            return new AccountResult(BankResult.INVALID_NAME, Optional.empty());
        }
        if (name.equalsIgnoreCase(PERSONAL_WORD) || name.equalsIgnoreCase(PARTY_WORD)) {
            return new AccountResult(BankResult.NAME_TAKEN, Optional.empty());
        }
        AccountRegistry registry = AccountRegistry.get(server);
        if (registry.namedByName(name).isPresent()) {
            return new AccountResult(BankResult.NAME_TAKEN, Optional.empty());
        }
        if (owned(server, owner) >= CoreSettings.maxAccountsPerPlayer()) {
            return new AccountResult(BankResult.LIMIT_REACHED, Optional.empty());
        }
        seenPlayer(server, owner);
        UUID id = newId(registry);
        registry.createNamed(id, owner, name);
        AccountId account = new AccountId(id);
        CoreEvents.post(new AccountEvent.Created(server, account, AccountKind.NAMED, owner, name));
        return new AccountResult(BankResult.OK, Optional.of(account));
    }

    @Override
    public BankResult delete(MinecraftServer server, AccountId id) {
        CoreServices.requireServerThread(server);
        Objects.requireNonNull(id, "id");
        Optional<AccountRegistry.Entry> found = live(server, id);
        if (found.isEmpty()) {
            return BankResult.NO_ACCOUNT;
        }
        AccountRegistry.Entry entry = found.get();
        if (entry.kind() == AccountKind.PERSONAL) {
            return BankResult.PERSONAL_ACCOUNT;
        }
        if (entry.kind() == AccountKind.PARTY) {
            return BankResult.NOT_ALLOWED;
        }
        if (holdsMoney(server, id)) {
            return BankResult.NOT_EMPTY;
        }
        AccountRegistry.get(server).end(id.value(), entry.owner());
        forgetInvites(id);
        CoreEvents.post(new AccountEvent.Deleted(server, id, 0, Optional.empty()));
        return BankResult.OK;
    }

    @Override
    public BankResult setMember(MinecraftServer server, AccountId id, UUID player, AccountRole role) {
        CoreServices.requireServerThread(server);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(role, "role");
        Optional<AccountRegistry.Entry> found = live(server, id);
        BankResult kind = namedOnly(found);
        if (kind != BankResult.OK) {
            return kind;
        }
        AccountRegistry.Entry entry = found.get();
        Optional<AccountRole> current = entry.roleOf(player);
        if (current.isPresent() && current.get() == role) {
            return BankResult.OK;
        }
        if (current.isPresent() && current.get() == AccountRole.OWNER) {
            // The owner can't step down by themselves: someone else must be made owner.
            return BankResult.NOT_ALLOWED;
        }
        if (current.isEmpty()) {
            if (!CoreSettings.sharedAccountsEnabled()) {
                return BankResult.DISABLED;
            }
            if (entry.members().size() >= CoreSettings.maxMembers()) {
                return BankResult.LIMIT_REACHED;
            }
        }
        if (role == AccountRole.OWNER && owned(server, player) >= CoreSettings.maxAccountsPerPlayer()) {
            return BankResult.LIMIT_REACHED;
        }

        // Every check is done; from here nothing can fail.
        seenPlayer(server, player);
        List<AccountMember> members = new ArrayList<>(entry.members());
        UUID owner = entry.owner();
        List<AccountEvent> events = new ArrayList<>();
        if (current.isEmpty()) {
            members.add(new AccountMember(player, role));
            events.add(new AccountEvent.MemberAdded(server, id, player, role));
        } else {
            members.replaceAll(m -> m.player().equals(player) ? new AccountMember(player, role) : m);
            events.add(new AccountEvent.RoleChanged(server, id, player, current.get(), role));
        }
        if (role == AccountRole.OWNER) {
            UUID old = owner;
            members.replaceAll(m -> m.player().equals(old) ? new AccountMember(old, AccountRole.MEMBER) : m);
            owner = player;
            events.add(new AccountEvent.RoleChanged(server, id, old, AccountRole.OWNER, AccountRole.MEMBER));
        }
        AccountRegistry registry = AccountRegistry.get(server);
        registry.setMembers(id.value(), owner, members);
        if (!role.canSpend()) {
            registry.chosenActive(player).filter(id.value()::equals).ifPresent(was -> registry.setActive(player, player));
        }
        removeInvite(player, id);
        if (role == AccountRole.OWNER) {
            // The new owner didn't offer the invitations still open.
            forgetInvites(id);
        }
        events.forEach(CoreEvents::post);
        return BankResult.OK;
    }

    @Override
    public BankResult removeMember(MinecraftServer server, AccountId id, UUID player) {
        CoreServices.requireServerThread(server);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(player, "player");
        Optional<AccountRegistry.Entry> found = live(server, id);
        BankResult kind = namedOnly(found);
        if (kind != BankResult.OK) {
            return kind;
        }
        AccountRegistry.Entry entry = found.get();
        Optional<AccountRole> role = entry.roleOf(player);
        if (role.isEmpty()) {
            return BankResult.NOT_A_MEMBER;
        }
        List<AccountMember> members = new ArrayList<>(entry.members());
        members.removeIf(m -> m.player().equals(player));
        if (role.get() == AccountRole.OWNER && members.isEmpty()) {
            return BankResult.NOT_ALLOWED;
        }

        // Every check is done; from here nothing can fail.
        List<AccountEvent> events = new ArrayList<>();
        events.add(new AccountEvent.MemberRemoved(server, id, player, role.get()));
        UUID owner = entry.owner();
        if (role.get() == AccountRole.OWNER) {
            // The longest-standing remaining member, whatever their role, becomes owner.
            AccountMember heir = members.get(0);
            members.set(0, new AccountMember(heir.player(), AccountRole.OWNER));
            owner = heir.player();
            events.add(new AccountEvent.RoleChanged(server, id, heir.player(), heir.role(), AccountRole.OWNER));
            // The new owner didn't offer the invitations still open.
            forgetInvites(id);
        }
        AccountRegistry registry = AccountRegistry.get(server);
        registry.setMembers(id.value(), owner, members);
        registry.chosenActive(player).filter(id.value()::equals).ifPresent(was -> registry.setActive(player, player));
        events.forEach(CoreEvents::post);
        return BankResult.OK;
    }

    // ---- Invitations -----------------------------------------------------------------------------------------

    /**
     * Invites {@code player} to a named account with {@code role}, to accept with {@link #accept}. The caller has
     * checked that whoever asked owns the account.
     *
     * @return {@link BankResult#OK}; as {@link #setMember} would answer for adding them; {@link BankResult#NOT_ALLOWED}
     *         if they are already a member, or the role offered is owner
     */
    public BankResult invite(MinecraftServer server, AccountId id, UUID player, AccountRole role) {
        Optional<AccountRegistry.Entry> found = live(server, id);
        BankResult kind = namedOnly(found);
        if (kind != BankResult.OK) {
            return kind;
        }
        if (role == AccountRole.OWNER || found.get().roleOf(player).isPresent()) {
            return BankResult.NOT_ALLOWED;
        }
        if (!CoreSettings.sharedAccountsEnabled()) {
            return BankResult.DISABLED;
        }
        if (found.get().members().size() >= CoreSettings.maxMembers()) {
            return BankResult.LIMIT_REACHED;
        }
        invites.computeIfAbsent(player, p -> new LinkedHashMap<>()).put(id, role);
        return BankResult.OK;
    }

    /** The invitations {@code player} holds to accounts that still exist, and the role each offers. */
    public Map<AccountId, AccountRole> invitations(MinecraftServer server, UUID player) {
        Map<AccountId, AccountRole> offers = invites.getOrDefault(player, Map.of());
        Map<AccountId, AccountRole> live = new LinkedHashMap<>();
        offers.forEach((id, role) -> {
            if (live(server, id).isPresent()) {
                live.put(id, role);
            }
        });
        return live;
    }

    /**
     * Accepts an invitation: {@code player} joins with the role offered, if the account can still take them.
     *
     * @return as {@link #setMember}; {@link BankResult#NOT_ALLOWED} if there is no such invitation
     */
    public BankResult accept(MinecraftServer server, UUID player, AccountId id) {
        AccountRole role = invitations(server, player).get(id);
        if (role == null) {
            return BankResult.NOT_ALLOWED;
        }
        return setMember(server, id, player, role);
    }

    /** Turns an invitation down; returns whether there was one. */
    public boolean decline(UUID player, AccountId id) {
        return removeInvite(player, id);
    }

    /** The invitations still open to {@code id}: each invited player and the role offered, in the order invited. */
    public Map<UUID, AccountRole> invitedTo(AccountId id) {
        Map<UUID, AccountRole> invited = new LinkedHashMap<>();
        invites.forEach((player, offers) -> {
            AccountRole role = offers.get(id);
            if (role != null) {
                invited.put(player, role);
            }
        });
        return invited;
    }

    /**
     * Withdraws {@code player}'s invitation to {@code id}; returns whether there was one. The caller has checked that
     * whoever asked owns the account.
     */
    public boolean cancelInvite(AccountId id, UUID player) {
        return removeInvite(player, id);
    }

    /** Forgets every invitation to {@code id}: when it ends, or changes owner. */
    private void forgetInvites(AccountId id) {
        invites.values().forEach(offers -> offers.remove(id));
        invites.values().removeIf(Map::isEmpty);
    }

    private boolean removeInvite(UUID player, AccountId id) {
        Map<AccountId, AccountRole> offers = invites.get(player);
        boolean had = offers != null && offers.remove(id) != null;
        if (offers != null && offers.isEmpty()) {
            invites.remove(player);
        }
        return had;
    }

    /** Forgets every invitation, when the server stops. */
    public void clearInvitations() {
        invites.clear();
    }

    // ---- Party accounts --------------------------------------------------------------------------------------

    /** Whether FTB Teams parties have accounts: FTB Teams is installed and the server's toggle is on. */
    public static boolean partyAccountsOn() {
        return FtbTeamsBridge.isAvailable() && CoreSettings.ftbPartyAccounts();
    }

    /** The account of {@code player}'s FTB Teams party, made now if the party has none yet. */
    public Optional<AccountId> partyAccountOf(MinecraftServer server, UUID player) {
        if (!partyAccountsOn()) {
            return Optional.empty();
        }
        return FtbTeamsBridge.teamOf(player).flatMap(party -> partyAccount(server, party));
    }

    /** The party's account, made now (posting its creation) if it has none; empty if parties have no accounts. */
    public Optional<AccountId> partyAccount(MinecraftServer server, Party party) {
        if (!partyAccountsOn()) {
            return Optional.empty();
        }
        AccountRegistry registry = AccountRegistry.get(server);
        Optional<UUID> existing = registry.partyAccount(party.id());
        if (existing.isPresent()) {
            registry.updateParty(existing.get(), party.owner(), party.name());
            return Optional.of(new AccountId(existing.get()));
        }
        UUID id = newId(registry);
        registry.createParty(id, party.id(), party.owner(), party.name());
        AccountId account = new AccountId(id);
        CoreEvents.post(new AccountEvent.Created(server, account, AccountKind.PARTY, party.owner(), party.name()));
        return Optional.of(account);
    }

    /** A player joined a party: its account gains a member. */
    public void partyMemberJoined(MinecraftServer server, Party party, UUID player) {
        partyAccount(server, party).ifPresent(id -> CoreEvents.post(new AccountEvent.MemberAdded(server, id, player, AccountRole.MEMBER)));
    }

    /** A player left a party that goes on: its account loses a member. */
    public void partyMemberLeft(MinecraftServer server, Party party, UUID player) {
        partyAccount(server, party).ifPresent(id -> CoreEvents.post(new AccountEvent.MemberRemoved(server, id, player, AccountRole.MEMBER)));
    }

    /** A party's ownership passed from {@code from} (who may have left) to {@code to}. */
    public void partyOwnerChanged(MinecraftServer server, Party party, UUID from, boolean fromStays, UUID to) {
        partyAccount(server, party).ifPresent(id -> {
            if (fromStays) {
                CoreEvents.post(new AccountEvent.RoleChanged(server, id, from, AccountRole.OWNER, AccountRole.MEMBER));
            }
            CoreEvents.post(new AccountEvent.RoleChanged(server, id, to, AccountRole.MEMBER, AccountRole.OWNER));
        });
    }

    /** A party was deleted: its account ends, and its balance goes to the party's owner. Returns what was paid. */
    public long partyDeleted(MinecraftServer server, Party party) {
        Optional<UUID> account = AccountRegistry.get(server).partyAccount(party.id());
        return account.map(id -> closeAccount(server, new AccountId(id), party.owner())).orElse(0L);
    }

    /**
     * Ends every party account when parties no longer have them (the toggle is off, or FTB Teams is gone), paying
     * each balance to the party's last known owner. At server start.
     */
    public void endPartyAccountsIfOff(MinecraftServer server) {
        if (partyAccountsOn()) {
            return;
        }
        AccountRegistry.get(server).live().forEach((id, entry) -> {
            if (entry.kind() == AccountKind.PARTY) {
                long paid = closeAccount(server, new AccountId(id), entry.owner());
                CoreModule.LOGGER.info("FTB Teams party {} no longer has an account ({}); its {} bits went to its owner {}",
                        entry.name(), FtbTeamsBridge.isAvailable() ? "ftb_party_account is off" : "FTB Teams isn't installed",
                        paid, entry.owner());
            }
        });
    }

    // ---- For core --------------------------------------------------------------------------------------------

    /**
     * Ends an account: its whole balance goes to {@code heir}'s personal account, and it is remembered as ended with
     * {@code heir} as its last owner, so money still owed to it goes there too. Can't fail. Returns what was paid.
     */
    public long closeAccount(MinecraftServer server, AccountId id, UUID heir) {
        seenPlayer(server, heir);
        OwnBank bank = OwnBank.get(server);
        AccountId to = personal(heir);
        long before = bank.accountOf(id.value()).balance();
        long heirBefore = bank.accountOf(heir).balance();
        long paid = bank.close(id.value(), heir);
        if (paid > 0) {
            Reason reason = Reason.of(ACCOUNT_ENDED);
            BalanceEvents.changed(server, id, before, 0, -paid, reason);
            BalanceEvents.changed(server, to, heirBefore, bank.accountOf(heir).balance(), paid, reason);
        }
        if (AccountRegistry.get(server).end(id.value(), heir)) {
            forgetInvites(id);
            CoreEvents.post(new AccountEvent.Deleted(server, id, paid, paid > 0 ? Optional.of(to) : Optional.empty()));
        }
        return paid;
    }

    /** Registers, quietly, every account the bank holds money for that core doesn't know yet. At server start. */
    public void seed(MinecraftServer server) {
        AccountRegistry registry = AccountRegistry.get(server);
        OwnBank.get(server).balances().keySet().forEach(id -> {
            if (registry.entry(id).isEmpty()) {
                registry.registerPersonal(id);
            }
        });
        endPartyAccountsIfOff(server);
    }

    /**
     * The server has seen {@code player}: their personal account exists from now on, and their current name is
     * remembered for when the server's profile cache no longer knows them.
     */
    public void seen(ServerPlayer player) {
        seenPlayer(player.server, player.getUUID());
        AccountRegistry.get(player.server).setPersonalName(player.getUUID(), player.getGameProfile().getName());
    }

    /** Registers {@code player}'s personal account, posting its creation if it is new. */
    public void seenPlayer(MinecraftServer server, UUID player) {
        if (AccountRegistry.get(server).registerPersonal(player)) {
            CoreEvents.post(new AccountEvent.Created(server, personal(player), AccountKind.PERSONAL, player, name(server, player)));
        }
    }

    /** Whether {@code id} names a live account: money can be paid into it. */
    public boolean exists(MinecraftServer server, AccountId id) {
        Optional<AccountRegistry.Entry> entry = live(server, id);
        return entry.isPresent() && (entry.get().kind() != AccountKind.PARTY || party(entry.get()).isPresent());
    }

    /** Whether {@code id} is a personal account the server has seen. */
    public boolean isPersonal(MinecraftServer server, AccountId id) {
        return AccountRegistry.get(server).isPersonal(id.value());
    }

    /** Every personal account's balance, by player. */
    public Map<UUID, Long> personalBalances(MinecraftServer server) {
        AccountRegistry registry = AccountRegistry.get(server);
        Map<UUID, Long> balances = new HashMap<>();
        OwnBank.get(server).balances().forEach((id, balance) -> {
            if (registry.isPersonal(id)) {
                balances.put(id, balance);
            }
        });
        return balances;
    }

    /** How many accounts {@code player} owns: their personal account and the named accounts they own. */
    public int owned(MinecraftServer server, UUID player) {
        int owned = 1;
        for (AccountRegistry.Entry entry : AccountRegistry.get(server).live().values()) {
            if (entry.kind() == AccountKind.NAMED && entry.owner().equals(player)) {
                owned++;
            }
        }
        return owned;
    }

    /** Whether an account holds money, is owed any, or has money held for it by an add-on. */
    public boolean holdsMoney(MinecraftServer server, AccountId id) {
        if (OwnBank.get(server).accountOf(id.value()).balance() > 0) {
            return true;
        }
        if (OwedPayments.get(server).list().stream().anyMatch(payment -> payment.account().equals(id))) {
            return true;
        }
        return CoreServices.NET_WORTH.held(server).getOrDefault(id, 0L) > 0;
    }

    /** Whether {@code name} can name an account: one word of letters, digits, '_' and '-', at most 24. */
    public static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    /** A player's name when the server knows it, otherwise their UUID. */
    public static String name(MinecraftServer server, UUID player) {
        return Leaderboard.profile(server, player).map(GameProfile::getName).orElse(player.toString());
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------

    private static Optional<AccountRegistry.Entry> live(MinecraftServer server, AccountId id) {
        return AccountRegistry.get(server).entry(id.value()).filter(entry -> !entry.ended());
    }

    /** The party behind a party account, while it exists and parties have accounts. */
    private static Optional<Party> party(AccountRegistry.Entry entry) {
        return partyAccountsOn() ? entry.party().flatMap(FtbTeamsBridge::teamById) : Optional.empty();
    }

    /** {@link BankResult#OK} for a live named account, otherwise why its members can't be changed. */
    private static BankResult namedOnly(Optional<AccountRegistry.Entry> entry) {
        if (entry.isEmpty()) {
            return BankResult.NO_ACCOUNT;
        }
        return switch (entry.get().kind()) {
            case PERSONAL -> BankResult.PERSONAL_ACCOUNT;
            case PARTY -> BankResult.NOT_ALLOWED;
            case NAMED -> BankResult.OK;
        };
    }

    private static UUID newId(AccountRegistry registry) {
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (registry.entry(id).isPresent());
        return id;
    }

    private AccountInfo personalInfo(MinecraftServer server, UUID player) {
        long balance = OwnBank.get(server).accountOf(player).balance();
        return new AccountInfo(personal(player), AccountKind.PERSONAL, name(server, player), player,
                List.of(new AccountMember(player, AccountRole.OWNER)), balance);
    }

    private static AccountInfo namedInfo(MinecraftServer server, AccountId id, AccountRegistry.Entry entry) {
        long balance = OwnBank.get(server).accountOf(id.value()).balance();
        return new AccountInfo(id, AccountKind.NAMED, entry.name(), entry.owner(), entry.members(), balance);
    }

    private static AccountInfo partyInfo(MinecraftServer server, AccountId id, Party party) {
        List<AccountMember> members = new ArrayList<>();
        for (UUID member : party.members()) {
            members.add(new AccountMember(member, member.equals(party.owner()) ? AccountRole.OWNER : AccountRole.MEMBER));
        }
        long balance = OwnBank.get(server).accountOf(id.value()).balance();
        return new AccountInfo(id, AccountKind.PARTY, party.name(), party.owner(), members, balance);
    }
}
