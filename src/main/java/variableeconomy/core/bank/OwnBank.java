package variableeconomy.core.bank;

import variableeconomy.core.coin.Coins;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * This mod's own bank: one balance per account ID, in bits, saved with the world. Balances and amounts are
 * {@code long}. A balance exists once money is first paid in, and is forgotten again when emptied. Which accounts
 * exist, and who may use them, is core's account registry's business, not the bank's.
 * <p>
 * Server thread only: the balances are an unsynchronized map.
 */
public final class OwnBank extends SavedData implements Bank {
    public static final String ID = "variable_economy_bank";
    private static final SavedData.Factory<OwnBank> FACTORY = new SavedData.Factory<>(OwnBank::new, OwnBank::fromTag, null);

    private final Map<UUID, Long> balances = new HashMap<>();

    public static OwnBank get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    @Override
    public Optional<Bank.Account> account(UUID id) {
        return Optional.of(accountOf(id));
    }

    /** The account {@code id}. Always reachable. */
    public Bank.Account accountOf(UUID id) {
        return new Account(id);
    }

    /**
     * Ends an account's balance: pays all of it into {@code heir}'s account and forgets it. Returns what was paid.
     * Can't fail (the heir's balance saturates rather than overflow), so no money is lost when an account ends.
     */
    public long close(UUID account, UUID heir) {
        long balance = balances.getOrDefault(account, 0L);
        if (balance > 0 && !account.equals(heir)) {
            new Account(heir).add(balance);
            new Account(account).set(0);
        }
        return account.equals(heir) ? 0 : balance;
    }

    @Override
    public Map<UUID, Long> balances() {
        return Map.copyOf(balances);
    }

    /** Omega down to Bit. */
    @Override
    public List<Denomination> coins() {
        return Coins.denominations();
    }

    public static OwnBank fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        OwnBank bank = new OwnBank();
        CompoundTag accounts = tag.getCompound("accounts");
        for (String key : accounts.getAllKeys()) {
            try {
                long balance = accounts.getLong(key);
                if (balance > 0) {
                    bank.balances.put(UUID.fromString(key), balance);
                }
            } catch (IllegalArgumentException e) {
                // Not a UUID; skip it rather than lose the whole bank.
            }
        }
        return bank;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag accounts = new CompoundTag();
        balances.forEach((id, balance) -> accounts.putLong(id.toString(), balance));
        tag.put("accounts", accounts);
        return tag;
    }

    private final class Account implements Bank.Account {
        private final UUID id;

        private Account(UUID id) {
            this.id = id;
        }

        @Override
        public long balance() {
            return balances.getOrDefault(id, 0L);
        }

        @Override
        public void deposit(long amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Negative deposit: " + amount);
            }
            add(amount);
        }

        @Override
        public boolean deduct(long amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Negative deduction: " + amount);
            }
            long current = balance();
            if (current < amount) {
                return false;
            }
            set(current - amount);
            return true;
        }

        /** Saturates instead of failing; callers check first, so only a heir near the limit ever reaches it. */
        void add(long amount) {
            long current = balance();
            set(current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount);
        }

        void set(long balance) {
            if (balance == 0) {
                balances.remove(id);
            } else {
                balances.put(id, balance);
            }
            setDirty();
        }
    }
}
