package variableeconomy.core.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import variableeconomy.core.bank.CoinMath;
import variableeconomy.core.bank.Denomination;
import variableeconomy.core.menu.BankMenu;
import variableeconomy.core.command.HistoryCommands;
import variableeconomy.core.network.BankPayloads;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;
import variableeconomy.core.service.BankService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The bank screen: the leaderboard beside the bank itself. The panel shows the balance and the coins carried; clicking
 * a coin withdraws one (with Shift, a stack, or as many as the balance covers), the box withdraws an amount as the
 * fewest coins, and Deposit pays every carried coin in. The arrows show the player's other accounts: deposits and
 * withdrawals act on the account shown, and "Use for trades" makes it their active account. "Accounts…" opens the
 * {@link AccountsScreen}.
 *
 * <p>The screen sizes itself to the window: on a wide window the panel sits beside the leaderboard, on a narrow one
 * below it.
 */
public final class BankScreen extends AbstractContainerScreen<BankMenu> {
    static final int MAX_WIDTH = 480;
    static final int MAX_HEIGHT = 320;
    /** Below this width the panel goes under the leaderboard instead of beside it. */
    static final int WIDE_MIN_WIDTH = 400;
    static final int SIDE_WIDTH = 150;
    static final int MARGIN = 8;
    static final int TITLE_Y = 4;
    static final int TITLE_HEIGHT = 14;
    static final int HEADER_Y = 24;
    static final int LIST_X = MARGIN;
    static final int LIST_Y = 36;
    static final int ROW_HEIGHT = 18;
    static final int SCROLLBAR_WIDTH = 4;
    static final int STATUS_HEIGHT = 16;
    /** The narrow layout's panel: the account row, the balance line, the coins, the withdraw row and its note. */
    static final int BOTTOM_PANEL_HEIGHT = 92;
    /** The account row's arrow buttons. */
    static final int ARROW_WIDTH = 14;
    /** The wide panel's coin grid: four coins a row, each cell this tall (icon, then the count under it). */
    static final int GRID_COLUMNS = 4;
    static final int GRID_CELL_HEIGHT = 28;
    /** The narrow panel's single row of coins, each cell this wide. */
    static final int ROW_CELL_WIDTH = 20;

    static final int PANEL = 0xF0202024;
    static final int PANEL_BORDER = 0xFF5A5A64;
    static final int ROW_ALT = 0x18FFFFFF;
    static final int ROW_HOVER = 0x30FFFFFF;
    static final int ROW_SELECTED = 0x50FFFFFF;
    static final int TEXT = 0xFFFFFF;
    static final int TEXT_DIM = 0xA0A0A8;
    static final int TEXT_GOOD = 0x6EE07A;
    static final int TEXT_BAD = 0xFF6B6B;
    static final int TEXT_MONEY = 0xF2C94C;

    private EditBox withdrawAmount;
    private Button withdrawButton;
    private Button depositButton;
    /** Show the previous or next of the player's accounts. */
    private Button previousButton;
    private Button nextButton;
    /** Makes the shown account the active one. */
    private Button useButton;
    private Button manageButton;
    /** Set while the Accounts screen replaces this one, so the bank's state isn't cleared. */
    private boolean toAccounts;
    private boolean leaderboardRequested;
    /** Switches the list between the leaderboard and the shown account's history. */
    private Button listButton;
    private boolean showingHistory;
    /** The account and balance the history was last asked for, so it's asked again when either changes. */
    @Nullable
    private UUID historyAccount;
    private long historyBalance;

    // The layout, worked out from the window size in init(). Positions are relative to leftPos/topPos.
    private boolean wide;
    private int listWidth;
    private int rows;
    private int panelX;
    private int panelY;
    private int panelWidth;
    /** Top of the coins: a grid when wide, one row when narrow. */
    private int coinsY;
    /** The balance line, and in the wide layout the use and manage buttons. */
    private int balanceY;
    private int buttonsY;
    /** Where the shown account's name goes, between the arrows. */
    private int labelX;
    private int labelWidth;
    private int withdrawY;
    private int noteY;
    private int depositY;
    private int statusY;
    private int scroll;
    /** Whether the scrollbar's thumb is being dragged. */
    private boolean draggingScrollbar;

    public BankScreen(BankMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override
    protected void init() {
        imageWidth = Math.min(MAX_WIDTH, width - MARGIN);
        imageHeight = Math.min(MAX_HEIGHT, height - MARGIN);
        super.init();
        layout();
        int x = leftPos;
        int y = topPos;
        // init() runs again on every resize; keep what the player typed.
        String withdrawText = withdrawAmount != null ? withdrawAmount.getValue() : "";

        int boxWidth = wide ? panelWidth - 64 : 70;
        withdrawAmount = new EditBox(font, x + panelX, y + withdrawY + 2, boxWidth, 14, Component.translatable("screen.variable_economy.bank.amount"));
        withdrawAmount.setHint(Component.translatable("screen.variable_economy.bank.amount").withColor(TEXT_DIM));
        withdrawAmount.setFilter(s -> s.isEmpty() || (s.length() <= 19 && s.chars().allMatch(Character::isDigit)));
        withdrawAmount.setValue(withdrawText);
        addRenderableWidget(withdrawAmount);
        int withdrawWidth = wide ? 62 : 56;
        withdrawButton = addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.bank.withdraw"), b -> withdraw())
                .bounds(x + panelX + boxWidth + 2, y + withdrawY, withdrawWidth, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy.bank.withdraw.tooltip")))
                .build());
        if (wide) {
            depositButton = addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.bank.deposit"), b -> deposit())
                    .bounds(x + panelX, y + depositY, panelWidth, 18).build());
        } else {
            // Narrow: Deposit takes what's left of the withdraw row.
            int rest = panelX + panelWidth - (panelX + boxWidth + 2 + withdrawWidth + 2);
            depositButton = addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.bank.deposit"), b -> deposit())
                    .bounds(x + panelX + boxWidth + withdrawWidth + 4, y + withdrawY, rest, 18).build());
        }
        // The account row: the arrows around the shown account's name; when narrow, the use and manage buttons follow.
        int manageWidth = 50;
        int useWidth = wide ? panelWidth - manageWidth - 2 : 96;
        int arrowsRight = wide ? panelX + panelWidth : panelX + panelWidth - useWidth - manageWidth - 4;
        previousButton = addRenderableWidget(Button.builder(Component.literal("<"), b -> cycle(-1))
                .bounds(x + panelX, y + panelY, ARROW_WIDTH, 16)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy.bank.account.previous"))).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"), b -> cycle(1))
                .bounds(x + arrowsRight - ARROW_WIDTH, y + panelY, ARROW_WIDTH, 16)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy.bank.account.next"))).build());
        labelX = panelX + ARROW_WIDTH + 2;
        labelWidth = arrowsRight - ARROW_WIDTH - 2 - labelX;
        int buttonsLeft = wide ? panelX : arrowsRight + 2;
        useButton = addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.bank.use"), b -> use())
                .bounds(x + buttonsLeft, y + buttonsY, useWidth, 16).build());
        manageButton = addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.bank.manage"), b -> manage())
                .bounds(x + buttonsLeft + useWidth + 2, y + buttonsY, manageWidth, 16)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy.bank.manage.tooltip"))).build());
        listButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
                    showingHistory = !showingHistory;
                    scroll = 0;
                    historyAccount = null;
                })
                .bounds(x + LIST_X, y + HEADER_Y - 4, 70, 14)
                .tooltip(Tooltip.create(Component.translatable("screen.variable_economy.history.show.tooltip"))).build());
        if (!leaderboardRequested) {
            leaderboardRequested = true;
            PacketDistributor.sendToServer(new BankPayloads.LeaderboardRequest());
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
        refreshButtons();
    }

    /** Works out where everything goes for the current screen size. */
    private void layout() {
        int right = imageWidth - MARGIN;
        int bottom = imageHeight - STATUS_HEIGHT;
        statusY = imageHeight - 12;
        wide = imageWidth >= WIDE_MIN_WIDTH;
        if (wide) {
            panelWidth = SIDE_WIDTH;
            panelX = right - SIDE_WIDTH;
            panelY = HEADER_Y;
            // From the bottom up: Deposit, the note, the withdraw row.
            depositY = bottom - 18;
            noteY = depositY - 12;
            withdrawY = noteY - 20;
            balanceY = panelY + 20;
            buttonsY = panelY + 44;
            coinsY = panelY + 64;
            listWidth = panelX - MARGIN - SCROLLBAR_WIDTH - 2 - LIST_X;
            rows = Math.max(1, (bottom - LIST_Y) / ROW_HEIGHT);
        } else {
            panelX = MARGIN;
            panelWidth = right - MARGIN;
            panelY = bottom - BOTTOM_PANEL_HEIGHT;
            buttonsY = panelY;
            balanceY = panelY + 20;
            coinsY = panelY + 32;
            withdrawY = panelY + 60;
            noteY = withdrawY + 21;
            depositY = withdrawY;
            listWidth = right - SCROLLBAR_WIDTH - 2 - LIST_X;
            rows = Math.max(1, (panelY - 4 - LIST_Y) / ROW_HEIGHT);
        }
    }

    // ---- State -----------------------------------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        refreshButtons();
    }

    /** Client-side hints only; the server re-checks everything and replies with the real outcome. */
    private void refreshButtons() {
        // Joining or leaving an account changes these while the screen is open.
        boolean several = ClientBankState.accounts().size() > 1;
        previousButton.visible = several;
        nextButton.visible = several;
        boolean active = ClientBankState.showingActive();
        boolean canSpend = ClientBankState.canSpendShown();
        useButton.active = !active && canSpend;
        useButton.setMessage(Component.translatable(active ? "screen.variable_economy.bank.in_use" : "screen.variable_economy.bank.use"));
        useButton.setTooltip(Tooltip.create(Component.translatable(canSpend || active
                ? "screen.variable_economy.bank.use.tooltip" : "screen.variable_economy.bank.cant_use.tooltip")));
        listButton.setMessage(Component.translatable(showingHistory
                ? "screen.variable_economy.leaderboard.show" : "screen.variable_economy.history.show"));
        if (showingHistory) {
            Optional<BankPayloads.AccountLine> line = ClientBankState.shown();
            if (line.isPresent() && (!line.get().id().equals(historyAccount) || line.get().balance() != historyBalance)) {
                historyAccount = line.get().id();
                historyBalance = line.get().balance();
                PacketDistributor.sendToServer(new BankPayloads.HistoryRequest(historyAccount));
            }
        }
        long carried = ClientBankState.carriedCoinValue();
        depositButton.active = carried > 0;
        depositButton.setMessage(carried > 0
                ? Component.translatable("screen.variable_economy.bank.deposit_for", PriceFormat.money(PriceFormat.amount(carried)))
                : Component.translatable("screen.variable_economy.bank.deposit"));
        depositButton.setTooltip(Tooltip.create(Component.translatable(carried > 0
                ? "screen.variable_economy.bank.deposit.tooltip" : "screen.variable_economy.bank.result.no_coins")));
        long amount = withdrawValue();
        withdrawButton.active = amount > 0 && withdrawProblem(amount, coinsFor(amount)) == null;
    }

    /** The amount typed to withdraw, or 0 if none. */
    private long withdrawValue() {
        try {
            return Math.max(0, Long.parseLong(withdrawAmount.getValue()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Why the typed amount can't be withdrawn, or null if it looks fine. The server re-checks everything. */
    @Nullable
    private static Component withdrawProblem(long amount, @Nullable long[] counts) {
        if (!ClientBankState.canSpendShown()) {
            return Component.translatable("screen.variable_economy.bank.result.not_allowed");
        }
        if (counts == null) {
            return Component.translatable("screen.variable_economy.bank.result.bank_unavailable");
        }
        if (!ClientBankState.looksAffordable(amount)) {
            return Component.translatable("screen.variable_economy.bank.result.no_funds");
        }
        if (!ClientBankState.coinsFit(counts)) {
            return Component.translatable("screen.variable_economy.bank.result.no_space");
        }
        return null;
    }

    /** The fewest coins for {@code amount}, or null if the coins aren't known. */
    @Nullable
    private static long[] coinsFor(long amount) {
        List<Denomination> coins = ClientBankState.coins();
        return coins.isEmpty() ? null : CoinMath.breakdown(coins, amount);
    }

    /** Shows the account {@code step} places along the list, wrapping round. */
    private void cycle(int step) {
        List<BankPayloads.AccountLine> accounts = ClientBankState.accounts();
        if (accounts.size() < 2) {
            return;
        }
        int at = 0;
        Optional<BankPayloads.AccountLine> shown = ClientBankState.shown();
        for (int i = 0; i < accounts.size(); i++) {
            if (shown.isPresent() && accounts.get(i).id().equals(shown.get().id())) {
                at = i;
            }
        }
        int next = Math.floorMod(at + step, accounts.size());
        PacketDistributor.sendToServer(new BankPayloads.ShowAccountRequest(accounts.get(next).id()));
    }

    private void use() {
        ClientBankState.shown().ifPresent(line ->
                PacketDistributor.sendToServer(BankPayloads.AccountRequest.of(BankPayloads.AccountAction.USE, line.id())));
    }

    private void manage() {
        toAccounts = true;
        minecraft.setScreen(new AccountsScreen(this));
    }

    /** Back from the Accounts screen. */
    void returnedFromAccounts() {
        toAccounts = false;
    }

    private void deposit() {
        PacketDistributor.sendToServer(new BankPayloads.BankRequest(true, 0, Optional.empty()));
    }

    private void withdraw() {
        long amount = withdrawValue();
        if (amount > 0) {
            PacketDistributor.sendToServer(new BankPayloads.BankRequest(false, amount, Optional.empty()));
        }
    }

    /** How many of {@code coin} a click withdraws: one, or with Shift a stack, or as many as the balance covers. */
    private static int coinsPerClick(Denomination coin) {
        if (!hasShiftDown()) {
            return 1;
        }
        long affordable = ClientBankState.balance() / coin.value();
        return (int) Math.max(1, Math.min(BankService.maxStackSize(coin), affordable));
    }

    @Override
    public void removed() {
        // The Accounts screen stands in for this one over the same open menu: keep the menu and the state.
        if (!toAccounts) {
            super.removed();
            ClientBankState.clear();
        }
    }

    // ---- Input -----------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && isOverScrollbar(mouseX, mouseY)) {
            // A click on the track jumps there; holding the button drags the thumb.
            draggingScrollbar = true;
            scrollTo(mouseY);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            int coin = coinAt(mouseX, mouseY);
            if (coin >= 0) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                Denomination picked = ClientBankState.coins().get(coin);
                PacketDistributor.sendToServer(BankPayloads.BankRequest.withdrawCoins(picked.item(), coinsPerClick(picked)));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        if (x >= LIST_X && x < LIST_X + listWidth + 2 + SCROLLBAR_WIDTH && y >= LIST_Y && y < LIST_Y + rows * ROW_HEIGHT) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Keep typing in the box from triggering the inventory key, which would close the screen.
        if (keyCode != GLFW.GLFW_KEY_ESCAPE && getFocused() instanceof EditBox box && box.canConsumeInput()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                setFocused(null);
                return true;
            }
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** The cell of coin {@code index}: x, y, width (relative to the screen's corner). */
    private int[] coinCell(int index) {
        if (wide) {
            int cellWidth = panelWidth / GRID_COLUMNS;
            return new int[] {panelX + (index % GRID_COLUMNS) * cellWidth, coinsY + (index / GRID_COLUMNS) * GRID_CELL_HEIGHT, cellWidth};
        }
        return new int[] {panelX + index * ROW_CELL_WIDTH, coinsY, ROW_CELL_WIDTH};
    }

    /** The coin under the mouse, or -1. */
    private int coinAt(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        List<Denomination> coins = ClientBankState.coins();
        int height = wide ? GRID_CELL_HEIGHT : 18;
        for (int i = 0; i < coins.size(); i++) {
            int[] cell = coinCell(i);
            if (x >= cell[0] && x < cell[0] + cell[2] && y >= cell[1] && y < cell[1] + height) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScrollbar && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Whether the mouse is on the scrollbar of a list long enough to scroll. */
    private boolean isOverScrollbar(double mouseX, double mouseY) {
        double x = mouseX - leftPos - (LIST_X + listWidth + 2);
        double y = mouseY - topPos - LIST_Y;
        return maxScroll() > 0 && x >= -1 && x < SCROLLBAR_WIDTH + 1 && y >= 0 && y < rows * ROW_HEIGHT;
    }

    /** Scrolls so the thumb's middle sits at {@code mouseY}, as far as the list allows. */
    private void scrollTo(double mouseY) {
        int height = rows * ROW_HEIGHT;
        int thumb = thumbHeight();
        if (height <= thumb || maxScroll() == 0) {
            return;
        }
        double fraction = (mouseY - topPos - LIST_Y - thumb / 2.0) / (height - thumb);
        scroll = Mth.clamp((int) Math.round(fraction * maxScroll()), 0, maxScroll());
    }

    /** The scrollbar thumb's height: the share of the list that shows, at least 12 pixels. */
    private int thumbHeight() {
        int height = rows * ROW_HEIGHT;
        return Math.min(height, Math.max(12, height * rows / (rows + maxScroll())));
    }

    private int maxScroll() {
        return Math.max(0, (showingHistory ? historyLines().size() : leaderboardLines().size()) - rows);
    }

    /** The shown account's history, once received for it. */
    private static List<BankPayloads.HistoryLine> historyLines() {
        BankPayloads.History history = ClientBankState.history();
        Optional<BankPayloads.AccountLine> shown = ClientBankState.shown();
        return history == null || shown.isEmpty() || !history.account().equals(shown.get().id()) ? List.of() : history.lines();
    }

    private static List<BankPayloads.LeaderboardLine> leaderboardLines() {
        BankPayloads.Leaderboard board = ClientBankState.leaderboard();
        return board == null || !ClientBankState.leaderboardEnabled() ? List.of() : board.lines();
    }

    // ---- Rendering -------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int coin = coinAt(mouseX, mouseY);
        if (coin >= 0) {
            Denomination picked = ClientBankState.coins().get(coin);
            int count = coinsPerClick(picked);
            graphics.renderComponentTooltip(font, List.of(
                    Component.translatable("screen.variable_economy.bank.click_withdraw", count, picked.item().getDescription(),
                            PriceFormat.money(PriceFormat.amount((long) count * picked.value()))),
                    Component.translatable("screen.variable_economy.bank.coin_worth", picked.item().getDescription(),
                            PriceFormat.money(PriceFormat.amount(picked.value()))).withColor(TEXT_MONEY),
                    Component.translatable("screen.variable_economy.bank.click_hint").withColor(TEXT_DIM)), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        graphics.drawString(font, title, x + MARGIN, y + TITLE_Y + 3, TEXT, false);
        graphics.fill(x + MARGIN, y + TITLE_Y + TITLE_HEIGHT, x + imageWidth - MARGIN, y + TITLE_Y + TITLE_HEIGHT + 1, PANEL_BORDER);
        if (showingHistory) {
            renderHistory(graphics, x, y);
        } else {
            renderLeaderboard(graphics, x, y);
        }
        renderScrollbar(graphics, x, y);
        renderPanel(graphics, x, y, mouseX, mouseY);
    }

    /** The richest players: rank, name and balance, with the player's own line highlighted and their rank above. */
    private void renderLeaderboard(GuiGraphics graphics, int x, int y) {
        BankPayloads.Leaderboard board = ClientBankState.leaderboard();
        List<BankPayloads.LeaderboardLine> lines = leaderboardLines();
        int listRight = LIST_X + listWidth;
        graphics.drawString(font, Component.translatable("screen.variable_economy.leaderboard.title"), x + LIST_X + 74, y + HEADER_Y, TEXT_DIM, false);
        if (board != null && ClientBankState.leaderboardEnabled()) {
            Component own = board.ownRank() > 0
                    ? Component.translatable("screen.variable_economy.leaderboard.you", board.ownRank(), board.ranked())
                    : Component.translatable("screen.variable_economy.leaderboard.you_unranked");
            drawRight(graphics, own, x + listRight - 2, y + HEADER_Y, TEXT_DIM);
        }
        for (int i = 0; i < rows && scroll + i < lines.size(); i++) {
            BankPayloads.LeaderboardLine line = lines.get(scroll + i);
            int rank = scroll + i + 1;
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = line.you() ? ROW_SELECTED : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            int textY = rowY + 5;
            drawRight(graphics, Component.literal("#" + rank), x + LIST_X + 26, textY, rank <= 3 ? TEXT_MONEY : TEXT_DIM);
            graphics.drawString(font, line.name(), x + LIST_X + 32, textY, line.you() ? TEXT_GOOD : TEXT, false);
            drawRight(graphics, PriceFormat.money(PriceFormat.amount(line.balance())), x + listRight - 2, textY, TEXT_MONEY);
        }
        if (lines.isEmpty()) {
            String key = !ClientBankState.leaderboardEnabled() ? "screen.variable_economy.leaderboard.turned_off"
                    : board == null ? "screen.variable_economy.trades.loading" : "screen.variable_economy.leaderboard.none";
            List<FormattedCharSequence> wrapped = font.split(Component.translatable(key), listWidth - 8);
            for (int i = 0; i < wrapped.size(); i++) {
                graphics.drawCenteredString(font, wrapped.get(i), x + LIST_X + listWidth / 2, y + LIST_Y + 20 + i * 10, TEXT_DIM);
            }
        }
    }

    /** The shown account's money movements, newest first: how long ago, why and who, and the change. */
    private void renderHistory(GuiGraphics graphics, int x, int y) {
        int listRight = LIST_X + listWidth;
        graphics.drawString(font, Component.translatable("screen.variable_economy.history.title"), x + LIST_X + 74, y + HEADER_Y, TEXT_DIM, false);
        List<BankPayloads.HistoryLine> lines = historyLines();
        long now = System.currentTimeMillis();
        for (int i = 0; i < rows && scroll + i < lines.size(); i++) {
            BankPayloads.HistoryLine line = lines.get(scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            if (i % 2 == 1) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, ROW_ALT);
            }
            int textY = rowY + 5;
            graphics.drawString(font, HistoryCommands.ago(now, line.time()), x + LIST_X + 2, textY, TEXT_DIM, false);
            Component amount = HistoryCommands.signed(line.amount());
            int amountWidth = font.width(amount);
            drawRight(graphics, amount, x + listRight - 2, textY, line.amount() < 0 ? TEXT_BAD : TEXT_GOOD);
            ResourceLocation reason = ResourceLocation.tryParse(line.reason());
            Component why = reason == null ? Component.literal(line.reason()) : HistoryCommands.reason(reason);
            String text = line.actor().isEmpty() ? why.getString()
                    : why.getString() + Component.translatable("command.variable_economy.history.by", line.actor()).getString();
            int textLeft = LIST_X + 56;
            graphics.drawString(font, font.plainSubstrByWidth(text, listRight - textLeft - amountWidth - 6), x + textLeft, textY, TEXT, false);
        }
        if (lines.isEmpty()) {
            BankPayloads.History history = ClientBankState.history();
            String key = history == null || !history.account().equals(historyAccount) ? "screen.variable_economy.trades.loading"
                    : history.access() == BankPayloads.HistoryAccess.OFF ? "screen.variable_economy.history.off"
                    : history.access() == BankPayloads.HistoryAccess.NOT_ALLOWED ? "screen.variable_economy.history.not_allowed"
                    : "screen.variable_economy.history.none";
            List<FormattedCharSequence> wrapped = font.split(Component.translatable(key), listWidth - 8);
            for (int i = 0; i < wrapped.size(); i++) {
                graphics.drawCenteredString(font, wrapped.get(i), x + LIST_X + listWidth / 2, y + LIST_Y + 20 + i * 10, TEXT_DIM);
            }
        }
    }

    private void renderScrollbar(GuiGraphics graphics, int x, int y) {
        int left = x + LIST_X + listWidth + 2;
        int top = y + LIST_Y;
        int height = rows * ROW_HEIGHT;
        graphics.fill(left, top, left + SCROLLBAR_WIDTH, top + height, 0x40000000);
        if (maxScroll() > 0) {
            int thumb = thumbHeight();
            int thumbY = top + (height - thumb) * scroll / maxScroll();
            graphics.fill(left, thumbY, left + SCROLLBAR_WIDTH, thumbY + thumb, 0xFFA0A0A8);
        }
    }

    /** The balance and carried coins, the coins to click, and what a withdrawal would give, or why it can't. */
    private void renderPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        int left = x + panelX;
        // The shown account, between the arrows: its name, and the player's role when it isn't their own.
        ClientBankState.shown().ifPresent(line -> {
            Component name = line.kind() == variableeconomy.core.api.AccountKind.PERSONAL ? ClientBankState.label(line)
                    : Component.translatable("screen.variable_economy.bank.account.label", ClientBankState.label(line),
                    ClientBankState.role(line.role()));
            String text = font.plainSubstrByWidth(name.getString(), labelWidth);
            graphics.drawString(font, text, x + labelX + (labelWidth - font.width(text)) / 2, y + panelY + 4,
                    line.id().equals(ClientBankState.active()) ? TEXT_GOOD : TEXT, false);
        });
        Component inBankAmount = PriceFormat.money(PriceFormat.amount(ClientBankState.balance()));
        Component inBank = Component.translatable("screen.variable_economy.bank.in_bank", inBankAmount);
        Component carried = Component.translatable("screen.variable_economy.bank.carried",
                PriceFormat.money(PriceFormat.amount(ClientBankState.carriedCoinValue())));
        if (wide) {
            graphics.drawString(font, font.plainSubstrByWidth(inBank.getString(), panelWidth), left, y + balanceY, TEXT_MONEY, false);
            graphics.drawString(font, font.plainSubstrByWidth(carried.getString(), panelWidth), left, y + balanceY + 12, TEXT, false);
        } else {
            String both = inBank.getString() + "   " + carried.getString();
            graphics.drawString(font, font.plainSubstrByWidth(both, panelWidth), left, y + balanceY, TEXT, false);
        }

        List<Denomination> coins = ClientBankState.coins();
        int hovered = coinAt(mouseX, mouseY);
        for (int i = 0; i < coins.size(); i++) {
            Denomination coin = coins.get(i);
            int[] cell = coinCell(i);
            int cellX = x + cell[0];
            int cellY = y + cell[1];
            int height = wide ? GRID_CELL_HEIGHT : 18;
            if (i == hovered) {
                graphics.fill(cellX, cellY, cellX + cell[2], cellY + height, ROW_HOVER);
            }
            int held = ClientBankState.held(coin);
            int iconX = cellX + (cell[2] - 16) / 2;
            ItemStack stack = new ItemStack(coin.item());
            graphics.renderItem(stack, iconX, cellY + 1);
            if (wide) {
                Component count = Component.literal(PriceFormat.count(held));
                graphics.drawCenteredString(font, count, cellX + cell[2] / 2, cellY + 18, held > 0 ? TEXT : TEXT_DIM);
            } else if (held > 0) {
                graphics.renderItemDecorations(font, stack, iconX, cellY + 1, PriceFormat.count(held));
            }
        }

        if (wide) {
            // The how-to, in whatever room is left between the coins and the withdraw row.
            int helpTop = coinsY + 2 * GRID_CELL_HEIGHT + 4;
            List<FormattedCharSequence> help = font.split(Component.translatable("screen.variable_economy.bank.help"), panelWidth);
            for (int i = 0; i < help.size() && helpTop + i * 10 + 9 < withdrawY - 2; i++) {
                graphics.drawString(font, help.get(i), left, y + helpTop + i * 10, TEXT_DIM, false);
            }
        }

        long amount = withdrawValue();
        long[] counts = coinsFor(amount);
        Component problem = amount > 0 ? withdrawProblem(amount, counts) : null;
        Component note;
        int color = TEXT_DIM;
        if (amount <= 0) {
            note = Component.translatable("screen.variable_economy.bank.amount_hint");
        } else if (problem != null) {
            note = problem;
            color = TEXT_BAD;
        } else {
            note = Component.translatable("screen.variable_economy.bank.as", coinList(counts));
        }
        graphics.drawString(font, font.plainSubstrByWidth(note.getString(), panelWidth), left, y + noteY, color, false);
    }

    /** "2 × Crown, 1 × Bit". */
    private static String coinList(long[] counts) {
        List<Denomination> coins = ClientBankState.coins();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < coins.size(); i++) {
            if (counts[i] > 0) {
                parts.add(counts[i] + " × " + coins.get(i).item().getDescription().getString());
            }
        }
        return String.join(", ", parts);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        Component status = ClientBankState.status();
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), imageWidth - 2 * MARGIN), MARGIN, statusY,
                    ClientBankState.statusIsError() ? TEXT_BAD : TEXT_GOOD, false);
        }
    }

    private void drawRight(GuiGraphics graphics, Component text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }
}
