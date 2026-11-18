package variableeconomy.core.api;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Taking items from a player and giving items to them, the way every module does it: only the main inventory (the
 * hotbar and the 27 storage slots) counts, never armor, the offhand or other mods' slots, and nothing is ever dropped
 * on the ground. Check first, then change: {@link #count} before {@link #remove}, {@link #room} before
 * {@link #insert}. Both changes check again and throw, changing nothing, if the check was skipped.
 * <p>
 * Server thread only, as the inventory isn't synchronized. After changing it, the inventory is marked changed so the
 * player sees it.
 */
public final class PlayerItems {
    private PlayerItems() {
    }

    /**
     * How many items in the player's main inventory match.
     *
     * @param player  the player
     * @param matches which stacks count, such as plain copies of one item ({@link CoreApi#isPlain})
     * @return the number of items in matching stacks
     */
    public static long count(Player player, Predicate<ItemStack> matches) {
        Objects.requireNonNull(matches, "matches");
        long count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && matches.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Removes exactly {@code quantity} matching items from the main inventory, from the first slot on.
     *
     * @param player   the player
     * @param matches  which stacks may be taken from
     * @param quantity how many to remove; at least 1
     * @throws IllegalArgumentException if the quantity is below 1 or more than {@link #count} finds, before anything
     *                                  is removed
     */
    public static void remove(Player player, Predicate<ItemStack> matches, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity below 1: " + quantity);
        }
        if (count(player, matches) < quantity) {
            throw new IllegalArgumentException("Fewer than " + quantity + " matching items to remove");
        }
        NonNullList<ItemStack> items = player.getInventory().items;
        int remaining = quantity;
        for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
            ItemStack stack = items.get(slot);
            if (!stack.isEmpty() && matches.test(stack)) {
                int taken = Math.min(remaining, stack.getCount());
                stack.shrink(taken);
                if (stack.isEmpty()) {
                    items.set(slot, ItemStack.EMPTY);
                }
                remaining -= taken;
            }
        }
        player.getInventory().setChanged();
    }

    /**
     * How many items exactly like {@code like} fit in the main inventory: room in stacks of exactly the same item and
     * data, plus empty slots. Counted here rather than with {@code Inventory.add}, which in creative mode reports
     * success after deleting what didn't fit.
     *
     * @param player the player
     * @param like   the item and data to fit; its count is ignored
     * @return how many fit
     */
    public static long room(Player player, ItemStack like) {
        int maxStack = like.getMaxStackSize();
        long room = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) {
                room += maxStack;
            } else if (ItemStack.isSameItemSameComponents(stack, like)) {
                room += Math.max(0, maxStack - stack.getCount());
            }
        }
        return room;
    }

    /**
     * Puts {@code quantity} copies of {@code like} into the main inventory: first topping up stacks of exactly the
     * same item and data, then filling empty slots.
     *
     * @param player   the player
     * @param like     the item and data to give; its count is ignored
     * @param quantity how many to give; at least 1
     * @throws IllegalArgumentException if the quantity is below 1 or more than {@link #room} finds, before anything is
     *                                  given
     */
    public static void insert(Player player, ItemStack like, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity below 1: " + quantity);
        }
        if (like.isEmpty()) {
            throw new IllegalArgumentException("Nothing to insert");
        }
        if (room(player, like) < quantity) {
            throw new IllegalArgumentException("Room for fewer than " + quantity + " items");
        }
        NonNullList<ItemStack> items = player.getInventory().items;
        int maxStack = like.getMaxStackSize();
        int remaining = quantity;
        for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
            ItemStack stack = items.get(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, like) && stack.getCount() < maxStack) {
                int added = Math.min(remaining, maxStack - stack.getCount());
                stack.grow(added);
                remaining -= added;
            }
        }
        for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
            if (items.get(slot).isEmpty()) {
                int added = Math.min(remaining, maxStack);
                items.set(slot, like.copyWithCount(added));
                remaining -= added;
            }
        }
        player.getInventory().setChanged();
    }
}
