package variableeconomy.core.compat;

import variableeconomy.core.CoreModule;
import com.enviouse.progressivestages.common.api.ProgressiveStagesAPI;
import com.enviouse.progressivestages.common.api.StageActorChangeEvent;
import com.enviouse.progressivestages.common.api.StageChangeEvent;
import com.enviouse.progressivestages.common.api.StagesBulkChangedEvent;
import com.enviouse.progressivestages.common.lock.LockRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The only class allowed to import ProgressiveStages, an optional dependency. Without the mod nothing is locked and
 * nothing here touches its classes: they're only referenced from {@link Present}, which loads only when the mod does.
 * <p>
 * "Locked" is ProgressiveStages' own item lock: the player is missing a stage the item requires. It deliberately
 * ignores creative bypass, per-category enforcement switches and structure locks, which say whether an item can be
 * <em>used</em> right now, not whether the player has reached it.
 * <p>
 * Checked against the 3.1.1 jar. The lock check lives outside ProgressiveStages' {@code api} package, so it is probed
 * by reflection once; if it doesn't match, or fails later, the market logs an error and hides nothing.
 * Server thread only.
 */
public final class StagesBridge {
    public static final String MOD_ID = "progressivestages";
    private static final boolean AVAILABLE = ModList.get().isLoaded(MOD_ID) && Present.probe();
    /** Set when a lock check throws; from then on nothing is hidden, rather than failing every trade. */
    private static boolean failed;

    private StagesBridge() {
    }

    /** Whether ProgressiveStages is loaded and its lock API matches, so items can be locked at all. */
    public static boolean isAvailable() {
        return AVAILABLE && !failed;
    }

    /** Whether {@code item} is locked for {@code player}: they lack a stage it needs. Always false without the mod. */
    public static boolean isLocked(ServerPlayer player, Item item) {
        if (!isAvailable()) {
            return false;
        }
        try {
            return Present.isLocked(player, item);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            CoreModule.LOGGER.error("ProgressiveStages lock check failed; items are no longer locked", e);
            return false;
        }
    }

    /**
     * Whether {@code player} has the stage {@code stage}. True without the mod, or once its checks have failed (nothing
     * is locked then either).
     */
    public static boolean hasStage(ServerPlayer player, String stage) {
        if (!isAvailable()) {
            return true;
        }
        try {
            return Present.hasStage(player, stage);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            CoreModule.LOGGER.error("ProgressiveStages stage check failed; items are no longer locked and every stage "
                    + "counts as reached", e);
            return true;
        }
    }

    /**
     * Calls {@code onChange} whenever a player's stages may have changed: with that player, or with null when it may
     * be anyone (a team's stages changed). Does nothing without the mod.
     */
    public static void listen(Consumer<@Nullable ServerPlayer> onChange) {
        if (AVAILABLE) {
            Present.listen(onChange);
        }
    }

    /** Everything that touches ProgressiveStages classes. */
    private static final class Present {
        static boolean probe() {
            try {
                requireReturn(LockRegistry.class.getMethod("getInstance").getReturnType(), LockRegistry.class);
                requireReturn(LockRegistry.class.getMethod("isItemBlockedFor", ServerPlayer.class, Item.class).getReturnType(),
                        boolean.class);
                requireReturn(ProgressiveStagesAPI.class.getMethod("hasStage", ServerPlayer.class, String.class).getReturnType(),
                        boolean.class);
                CoreModule.LOGGER.info("ProgressiveStages found: items a player has not unlocked are locked for them");
                return true;
            } catch (ReflectiveOperationException | LinkageError e) {
                CoreModule.LOGGER.error("ProgressiveStages is installed but its item lock API does not match what this mod "
                        + "was built against (3.1.1); stage locks are off", e);
                return false;
            }
        }

        static boolean isLocked(ServerPlayer player, Item item) {
            LockRegistry locks = LockRegistry.getInstance();
            return locks != null && locks.isItemBlockedFor(player, item);
        }

        static boolean hasStage(ServerPlayer player, String stage) {
            return ProgressiveStagesAPI.hasStage(player, stage);
        }

        static void listen(Consumer<@Nullable ServerPlayer> onChange) {
            NeoForge.EVENT_BUS.addListener((StageChangeEvent event) -> onChange.accept(event.getPlayer()));
            NeoForge.EVENT_BUS.addListener((StagesBulkChangedEvent event) -> onChange.accept(event.getPlayer()));
            // An actor can be a team; its members aren't named, so treat it as anyone.
            NeoForge.EVENT_BUS.addListener((StageActorChangeEvent event) -> onChange.accept(null));
        }

        private static void requireReturn(Class<?> actual, Class<?> expected) throws NoSuchMethodException {
            if (actual != expected) {
                throw new NoSuchMethodException("expected " + expected.getName() + " but found " + actual.getName());
            }
        }
    }
}
