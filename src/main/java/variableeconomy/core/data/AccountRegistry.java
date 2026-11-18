package variableeconomy.core.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountMember;
import variableeconomy.core.api.AccountRole;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every account core has made, saved with the world: its kind, name, owner and members, whether it has ended, and for
 * a party account the FTB Teams party behind it. Also each player's chosen active account. Balances live in the bank,
 * not here.
 * <p>
 * A personal account's ID is its player's UUID, and it is registered the first time the server sees the player. Every
 * other account has an ID of its own. An account that ends stays here, ended, with the owner it had then, so money
 * still owed to it can go to that owner and its ID never names a live account again.
 * <p>
 * Members are kept in the order they joined, owner included, so "the longest-standing member" is the first one after
 * the owner. A party account's members follow its party and aren't kept here. This class only stores; the rules
 * (who may do what, the server's caps) are {@code AccountsService}'s. Server thread only.
 */
public final class AccountRegistry extends SavedData {
    public static final String ID = "variable_economy_accounts";
    private static final SavedData.Factory<AccountRegistry> FACTORY =
            new SavedData.Factory<>(AccountRegistry::new, AccountRegistry::fromTag, null);

    /**
     * One account, as it is now.
     *
     * @param kind    what sort of account it is
     * @param owner   its owner; for an ended account, the owner it had when it ended
     * @param name    a named or party account's name; empty for a personal account, which shows its player's name
     * @param members a named account's members in the order they joined, owner included; empty for other kinds
     * @param party   a party account's FTB Teams party
     * @param ended   whether it has ended
     */
    public record Entry(AccountKind kind, UUID owner, String name, List<AccountMember> members, Optional<UUID> party,
                        boolean ended) {
        public Entry {
            members = List.copyOf(members);
        }

        /** {@code player}'s role in a named account, if they're a member. */
        public Optional<AccountRole> roleOf(UUID player) {
            return members.stream().filter(m -> m.player().equals(player)).map(AccountMember::role).findFirst();
        }

        Entry withMembers(UUID newOwner, List<AccountMember> newMembers) {
            return new Entry(kind, newOwner, name, newMembers, party, ended);
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    /** Each player's chosen active account, when it isn't their personal one. */
    private final Map<UUID, UUID> active = new HashMap<>();

    public static AccountRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public Optional<Entry> entry(UUID id) {
        return Optional.ofNullable(entries.get(id));
    }

    /** Whether {@code id} is a personal account the server has seen. Personal accounts never end. */
    public boolean isPersonal(UUID id) {
        Entry entry = entries.get(id);
        return entry != null && entry.kind() == AccountKind.PERSONAL;
    }

    /** Every account that hasn't ended, by ID, in the order they were made. */
    public Map<UUID, Entry> live() {
        Map<UUID, Entry> live = new LinkedHashMap<>();
        entries.forEach((id, entry) -> {
            if (!entry.ended()) {
                live.put(id, entry);
            }
        });
        return live;
    }

    /** Registers {@code player}'s personal account; returns true if it is new. */
    public boolean registerPersonal(UUID player) {
        if (entries.containsKey(player)) {
            return false;
        }
        entries.put(player, new Entry(AccountKind.PERSONAL, player, "", List.of(), Optional.empty(), false));
        setDirty();
        return true;
    }

    /** Makes a new named account with {@code owner} its only member. The caller has checked the rules. */
    public void createNamed(UUID id, UUID owner, String name) {
        entries.put(id, new Entry(AccountKind.NAMED, owner, name, List.of(new AccountMember(owner, AccountRole.OWNER)),
                Optional.empty(), false));
        setDirty();
    }

    /** The live named account called {@code name}, ignoring case. */
    public Optional<UUID> namedByName(String name) {
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            if (!entry.ended() && entry.kind() == AccountKind.NAMED && entry.name().equalsIgnoreCase(name)) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /** The live named account called {@code name}, or else the live party account, ignoring case: for admin commands. */
    public Optional<UUID> byName(String name) {
        Optional<UUID> named = namedByName(name);
        if (named.isPresent()) {
            return named;
        }
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            if (!entry.ended() && entry.kind() == AccountKind.PARTY && entry.name().equalsIgnoreCase(name)) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /** Replaces a named account's owner and members (in joining order, owner included). */
    public void setMembers(UUID id, UUID owner, List<AccountMember> members) {
        Entry old = entries.get(id);
        if (old == null || old.ended() || old.kind() != AccountKind.NAMED) {
            throw new IllegalStateException("Not a live named account: " + id);
        }
        entries.put(id, old.withMembers(owner, members));
        setDirty();
    }

    /** The live account of the party {@code party}. */
    public Optional<UUID> partyAccount(UUID party) {
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            if (!entry.ended() && entry.party().filter(party::equals).isPresent()) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /** Makes a new party account for {@code party}. The caller has checked it has none. */
    public void createParty(UUID id, UUID party, UUID owner, String name) {
        entries.put(id, new Entry(AccountKind.PARTY, owner, name, List.of(), Optional.of(party), false));
        setDirty();
    }

    /** Brings a live party account's owner and name up to date with its party. */
    public void updateParty(UUID id, UUID owner, String name) {
        Entry old = entries.get(id);
        if (old != null && !old.ended() && (!old.owner().equals(owner) || !old.name().equals(name))) {
            entries.put(id, new Entry(old.kind(), owner, name, old.members(), old.party(), false));
            setDirty();
        }
    }

    /**
     * Marks an account ended, remembering {@code lastOwner}, and forgets it as anyone's active account. Returns false
     * if it was unknown, personal or already ended.
     */
    public boolean end(UUID id, UUID lastOwner) {
        Entry old = entries.get(id);
        if (old == null || old.ended() || old.kind() == AccountKind.PERSONAL) {
            return false;
        }
        entries.put(id, new Entry(old.kind(), lastOwner, old.name(), old.members(), old.party(), true));
        active.values().removeIf(id::equals);
        setDirty();
        return true;
    }

    /** {@code player}'s chosen active account, if they chose one other than their personal account. */
    public Optional<UUID> chosenActive(UUID player) {
        return Optional.ofNullable(active.get(player));
    }

    /** Remembers {@code player}'s active account; their own UUID (their personal account) clears the choice. */
    public void setActive(UUID player, UUID account) {
        boolean changed = account.equals(player) ? active.remove(player) != null : !account.equals(active.put(player, account));
        if (changed) {
            setDirty();
        }
    }

    public static AccountRegistry fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        AccountRegistry registry = new AccountRegistry();
        for (Tag element : tag.getList("accounts", Tag.TAG_COMPOUND)) {
            CompoundTag a = (CompoundTag) element;
            if (!a.hasUUID("id") || !a.hasUUID("owner")) {
                continue;
            }
            AccountKind kind;
            try {
                kind = AccountKind.valueOf(a.getString("kind"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            List<AccountMember> members = new ArrayList<>();
            for (Tag m : a.getList("members", Tag.TAG_COMPOUND)) {
                CompoundTag member = (CompoundTag) m;
                try {
                    members.add(new AccountMember(member.getUUID("player"), AccountRole.valueOf(member.getString("role"))));
                } catch (IllegalArgumentException e) {
                    // An unreadable member is left out rather than losing the account.
                }
            }
            Optional<UUID> party = a.hasUUID("party") ? Optional.of(a.getUUID("party")) : Optional.empty();
            registry.entries.put(a.getUUID("id"), new Entry(kind, a.getUUID("owner"), a.getString("name"), members, party,
                    a.getBoolean("ended")));
        }
        for (Tag element : tag.getList("active", Tag.TAG_COMPOUND)) {
            CompoundTag a = (CompoundTag) element;
            if (a.hasUUID("player") && a.hasUUID("account")) {
                registry.active.put(a.getUUID("player"), a.getUUID("account"));
            }
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        entries.forEach((id, entry) -> {
            CompoundTag a = new CompoundTag();
            a.putUUID("id", id);
            a.putString("kind", entry.kind().name());
            a.putUUID("owner", entry.owner());
            if (!entry.name().isEmpty()) {
                a.putString("name", entry.name());
            }
            if (!entry.members().isEmpty()) {
                ListTag members = new ListTag();
                for (AccountMember member : entry.members()) {
                    CompoundTag m = new CompoundTag();
                    m.putUUID("player", member.player());
                    m.putString("role", member.role().name());
                    members.add(m);
                }
                a.put("members", members);
            }
            entry.party().ifPresent(party -> a.putUUID("party", party));
            if (entry.ended()) {
                a.putBoolean("ended", true);
            }
            list.add(a);
        });
        tag.put("accounts", list);
        ListTag choices = new ListTag();
        active.forEach((player, account) -> {
            CompoundTag a = new CompoundTag();
            a.putUUID("player", player);
            a.putUUID("account", account);
            choices.add(a);
        });
        tag.put("active", choices);
        return tag;
    }
}
