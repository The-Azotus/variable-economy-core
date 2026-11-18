package variableeconomy.core.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.Reason;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Payments owed while the bank couldn't be reached, saved with the world so a restart never loses them: every module's
 * payments, made through core's money service. They are paid on the server tick as soon as the bank is back, and
 * count on the leaderboard meanwhile. The mod's own bank is always reachable, so in practice this stays empty.
 * <p>
 * Server thread only.
 */
public final class OwedPayments extends SavedData {
    public static final String ID = "variable_economy_owed_payments";
    private static final SavedData.Factory<OwedPayments> FACTORY =
            new SavedData.Factory<>(OwedPayments::new, OwedPayments::fromTag, null);
    /** How long to wait after a payment fails before trying again: 10 seconds, so a bank that's down isn't asked every tick. */
    public static final int RETRY_TICKS = 200;

    /** Money owed to an account, and why. */
    public record Payment(AccountId account, long amount, Reason reason) {
    }

    private final List<Payment> owed = new ArrayList<>();
    /** The server tick before which a payment that failed isn't tried again. Not saved: a restart tries at once. */
    private long retryAt;

    public static OwedPayments get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public void owe(Payment payment) {
        owed.add(payment);
        setDirty();
    }

    public void paid(Payment payment) {
        owed.remove(payment);
        setDirty();
    }

    /** A copy, safe to iterate while paying. */
    public List<Payment> list() {
        return List.copyOf(owed);
    }

    public boolean isEmpty() {
        return owed.isEmpty();
    }

    /** Whether payments may be tried at server tick {@code tick}. */
    public boolean retryDue(long tick) {
        return tick >= retryAt;
    }

    /** A payment failed at server tick {@code tick}: wait {@link #RETRY_TICKS} before trying again. */
    public void failedAt(long tick) {
        retryAt = tick + RETRY_TICKS;
    }

    /** Ends the wait after a failed payment. GameTests only. */
    public void retryNowForTesting() {
        retryAt = 0;
    }

    public static OwedPayments fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        OwedPayments data = new OwedPayments();
        for (Tag element : tag.getList("owed", Tag.TAG_COMPOUND)) {
            CompoundTag o = (CompoundTag) element;
            ResourceLocation reason = ResourceLocation.tryParse(o.getString("reason"));
            if (o.hasUUID("account") && reason != null && o.getLong("amount") > 0) {
                Optional<UUID> actor = o.hasUUID("actor") ? Optional.of(o.getUUID("actor")) : Optional.empty();
                data.owed.add(new Payment(new AccountId(o.getUUID("account")), o.getLong("amount"), new Reason(reason, actor)));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Payment payment : owed) {
            CompoundTag o = new CompoundTag();
            o.putUUID("account", payment.account().value());
            o.putLong("amount", payment.amount());
            o.putString("reason", payment.reason().id().toString());
            payment.reason().actor().ifPresent(actor -> o.putUUID("actor", actor));
            list.add(o);
        }
        tag.put("owed", list);
        return tag;
    }
}
