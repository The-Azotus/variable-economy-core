package variableeconomy.core.menu;

import variableeconomy.core.CoreSettings;
import variableeconomy.core.CoreModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import variableeconomy.core.coin.Coins;
import variableeconomy.core.block.CoreBlocks;

import java.util.List;

/**
 * The bank screen's server-side half. It has no slots: everything travels in the bank's own packets. It marks which
 * players have the bank open, so requests from anyone else are ignored and updates go only to viewers. Opened from a
 * Bank block it stays valid while the player is near that block; from a Bank Card, while they carry a card; by command,
 * while the access mode allows that player to use commands.
 */
public final class BankMenu extends AbstractContainerMenu {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CoreModule.MOD_ID);
    public static final DeferredHolder<MenuType<?>, MenuType<BankMenu>> TYPE =
            MENUS.register("bank", () -> IMenuTypeExtension.create((id, inventory, data) -> new BankMenu(id, ContainerLevelAccess.NULL, false)));

    /** The block this was opened from, or {@link ContainerLevelAccess#NULL} if opened otherwise (or on the client). */
    private final ContainerLevelAccess block;
    /** Whether a Bank Card opened it. */
    private final boolean card;

    private BankMenu(int containerId, ContainerLevelAccess block, boolean card) {
        super(TYPE.get(), containerId);
        this.block = block;
        this.card = card;
    }

    /** Opens the bank by command. */
    public static void open(ServerPlayer player) {
        open(player, ContainerLevelAccess.NULL, false);
    }

    /** Opens the bank from the block at {@code pos}. */
    public static void openAt(ServerPlayer player, BlockPos pos) {
        open(player, ContainerLevelAccess.create(player.level(), pos), false);
    }

    /** Opens the bank from a Bank Card. */
    public static void openWithCard(ServerPlayer player) {
        open(player, ContainerLevelAccess.NULL, true);
    }

    private static void open(ServerPlayer player, ContainerLevelAccess block, boolean card) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new BankMenu(id, block, card),
                Component.translatable("screen.variable_economy.bank")));
    }

    /** A server-side menu for the block at {@code pos}, without opening a screen. For tests. */
    public static BankMenu forBlock(ServerPlayer player, BlockPos pos) {
        return new BankMenu(0, ContainerLevelAccess.create(player.level(), pos), false);
    }

    /** A server-side menu as {@code /bank} opens it, without opening a screen. For tests. */
    public static BankMenu forCommand() {
        return new BankMenu(0, ContainerLevelAccess.NULL, false);
    }

    /** A server-side menu as a Bank Card opens it, without opening a screen. For tests. */
    public static BankMenu forCard() {
        return new BankMenu(0, ContainerLevelAccess.NULL, true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (card) {
            return carriesBankCard(player);
        }
        if (block != ContainerLevelAccess.NULL) {
            // Vanilla's container rule: the block must still be there and the player within 8 blocks of it.
            return stillValid(block, player, CoreBlocks.BANK.get());
        }
        return CoreSettings.commandsAllowed(player);
    }

    /** Anywhere in the inventory: main, armor or offhand. */
    private static boolean carriesBankCard(Player player) {
        Inventory inventory = player.getInventory();
        for (List<ItemStack> compartment : List.of(inventory.items, inventory.armor, inventory.offhand)) {
            for (ItemStack stack : compartment) {
                if (stack.is(Coins.BANK_CARD.get())) {
                    return true;
                }
            }
        }
        return false;
    }
}
