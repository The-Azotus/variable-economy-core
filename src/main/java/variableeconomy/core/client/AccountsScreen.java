package variableeconomy.core.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import variableeconomy.core.api.AccountKind;
import variableeconomy.core.api.AccountRole;
import variableeconomy.core.network.BankPayloads;
import variableeconomy.core.network.BankPayloads.AccountAction;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The Accounts screen, opened from the bank screen over the same open bank: the player's accounts (click one to see it),
 * the shown account's members, and their invitations. An account's owner invites, changes roles, hands it over, removes
 * members and deletes it; members leave. Opening accounts and inviting show only when the server allows them. Display
 * only: the server checks every request and answers in the status line.
 */
public final class AccountsScreen extends Screen {
    private static final int MAX_WIDTH = 440;
    private static final int MAX_HEIGHT = 300;
    private static final int MARGIN = 8;
    private static final int ROW_HEIGHT = 16;
    private static final int LEFT_WIDTH = 150;
    private static final int TOP = 24;
    /** How long a two-click button stays armed, in milliseconds. */
    private static final long CONFIRM_MS = 3000;

    private final BankScreen parent;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int accountRows;
    private int memberRows;
    private int memberScroll;
    private int seenVersion = -1;
    private boolean returning;

    private EditBox nameBox;
    private EditBox inviteBox;
    private String nameText = "";
    private String inviteText = "";
    private AccountRole inviteRole = AccountRole.MEMBER;
    /** The two-click action waiting for its second click, and when it was armed. */
    private String armed = "";
    private long armedAt;

    AccountsScreen(BankScreen parent) {
        super(Component.translatable("screen.variable_economy.accounts.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(MAX_WIDTH, width - 2 * MARGIN);
        panelHeight = Math.min(MAX_HEIGHT, height - 2 * MARGIN);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        seenVersion = ClientBankState.version();
        int bottom = top + panelHeight - 18;

        // Left: the accounts, then invitations, then opening a new one.
        List<BankPayloads.AccountLine> accounts = ClientBankState.accounts();
        List<BankPayloads.InviteLine> invites = ClientBankState.invites();
        int createY = bottom - 20;
        int invitesHeight = invites.isEmpty() ? 0 : 12 + invites.size() * ROW_HEIGHT;
        accountRows = Math.max(1, (createY - 4 - invitesHeight - (top + TOP + 12)) / ROW_HEIGHT);
        UUID shown = ClientBankState.shown().map(BankPayloads.AccountLine::id).orElse(null);
        for (int i = 0; i < accounts.size() && i < accountRows; i++) {
            BankPayloads.AccountLine line = accounts.get(i);
            Component label = line.kind() == AccountKind.PERSONAL ? ClientBankState.label(line)
                    : Component.translatable("screen.variable_economy.bank.account.label", ClientBankState.label(line),
                    ClientBankState.role(line.role()));
            Button row = addRenderableWidget(Button.builder(label, b -> show(line.id()))
                    .bounds(left + MARGIN, top + TOP + 12 + i * ROW_HEIGHT, LEFT_WIDTH, ROW_HEIGHT - 1).build());
            row.active = !line.id().equals(shown);
        }
        int invitesY = createY - 4 - invitesHeight + 12;
        for (int i = 0; i < invites.size(); i++) {
            BankPayloads.InviteLine invite = invites.get(i);
            int y = invitesY + i * ROW_HEIGHT;
            addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.accounts.accept"),
                            b -> send(AccountAction.ACCEPT, invite.account(), "", invite.account(), AccountRole.MEMBER))
                    .bounds(left + MARGIN + LEFT_WIDTH - 92, y, 46, ROW_HEIGHT - 1).build());
            addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.accounts.decline"),
                            b -> send(AccountAction.DECLINE, invite.account(), "", invite.account(), AccountRole.MEMBER))
                    .bounds(left + MARGIN + LEFT_WIDTH - 45, y, 45, ROW_HEIGHT - 1).build());
        }
        nameBox = new EditBox(font, left + MARGIN, createY + 2, LEFT_WIDTH - 44, 14,
                Component.translatable("screen.variable_economy.accounts.create.hint"));
        nameBox.setHint(Component.translatable("screen.variable_economy.accounts.create.hint").withColor(BankScreen.TEXT_DIM));
        nameBox.setFilter(text -> text.length() <= 24 && text.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-'));
        nameBox.setValue(nameText);
        nameBox.setResponder(text -> nameText = text);
        Button create = addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.accounts.create"), b -> {
                    if (!nameText.isEmpty()) {
                        send(AccountAction.CREATE, new UUID(0, 0), nameText, new UUID(0, 0), AccountRole.OWNER);
                        nameText = "";
                    }
                })
                .bounds(left + MARGIN + LEFT_WIDTH - 42, createY, 42, 18).build());
        if (ClientBankState.extraEnabled()) {
            addRenderableWidget(nameBox);
        } else {
            create.visible = false;
        }

        // Right: the shown account's members and what the player may do with it.
        int rightX = left + MARGIN + LEFT_WIDTH + 10;
        int rightWidth = left + panelWidth - MARGIN - rightX;
        Optional<BankPayloads.AccountLine> line = ClientBankState.shown();
        boolean owner = line.isPresent() && line.get().kind() == AccountKind.NAMED && line.get().role() == AccountRole.OWNER;
        int inviteY = bottom - 42;
        memberRows = Math.max(1, (inviteY - 4 - (top + TOP + 24)) / ROW_HEIGHT);
        List<BankPayloads.MemberLine> members = ClientBankState.members();
        memberScroll = Mth.clamp(memberScroll, 0, Math.max(0, members.size() - memberRows));
        if (owner) {
            UUID me = Objects.requireNonNull(minecraft).player == null ? null : minecraft.player.getUUID();
            for (int i = 0; i < memberRows && memberScroll + i < members.size(); i++) {
                BankPayloads.MemberLine member = members.get(memberScroll + i);
                if (member.role() == AccountRole.OWNER || member.player().equals(me)) {
                    continue;
                }
                int y = top + TOP + 24 + i * ROW_HEIGHT;
                UUID account = line.get().id();
                AccountRole next = member.role() == AccountRole.MEMBER ? AccountRole.DEPOSIT_ONLY : AccountRole.MEMBER;
                addRenderableWidget(Button.builder(roleText(member.role()),
                                b -> send(AccountAction.SET_ROLE, account, "", member.player(), next))
                        .bounds(rightX + rightWidth - 184, y, 74, ROW_HEIGHT - 1)
                        .tooltip(Tooltip.create(Component.translatable("screen.variable_economy.accounts.role.tooltip"))).build());
                addTwoClick("owner:" + member.player(), Component.translatable("screen.variable_economy.accounts.make_owner"),
                        "screen.variable_economy.accounts.make_owner.tooltip", rightX + rightWidth - 108, y, 60,
                        () -> send(AccountAction.SET_ROLE, account, "", member.player(), AccountRole.OWNER));
                addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.accounts.remove"),
                                b -> send(AccountAction.REMOVE, account, "", member.player(), AccountRole.MEMBER))
                        .bounds(rightX + rightWidth - 46, y, 46, ROW_HEIGHT - 1).build());
            }
        }
        inviteBox = new EditBox(font, rightX, inviteY + 2, rightWidth - 140, 14, Component.translatable("screen.variable_economy.accounts.invite.hint"));
        inviteBox.setHint(Component.translatable("screen.variable_economy.accounts.invite.hint").withColor(BankScreen.TEXT_DIM));
        inviteBox.setFilter(text -> text.length() <= 16);
        inviteBox.setValue(inviteText);
        inviteBox.setResponder(text -> inviteText = text);
        if (owner && ClientBankState.sharedEnabled()) {
            addRenderableWidget(inviteBox);
            addRenderableWidget(Button.builder(roleText(inviteRole), b -> {
                        inviteRole = inviteRole == AccountRole.MEMBER ? AccountRole.DEPOSIT_ONLY : AccountRole.MEMBER;
                        rebuildWidgets();
                    })
                    .bounds(rightX + rightWidth - 136, inviteY, 80, 18)
                    .tooltip(Tooltip.create(Component.translatable("screen.variable_economy.accounts.role.tooltip"))).build());
            UUID account = line.get().id();
            addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.accounts.invite"), b -> {
                        if (!inviteText.isEmpty()) {
                            send(AccountAction.INVITE, account, inviteText, account, inviteRole);
                            inviteText = "";
                        }
                    })
                    .bounds(rightX + rightWidth - 54, inviteY, 54, 18).build());
        }
        if (line.isPresent() && line.get().kind() == AccountKind.NAMED) {
            UUID account = line.get().id();
            addTwoClick("leave", Component.translatable("screen.variable_economy.accounts.leave"),
                    "screen.variable_economy.accounts.leave.tooltip", rightX, bottom - 20, 70,
                    () -> send(AccountAction.LEAVE, account, "", account, AccountRole.MEMBER));
            if (owner) {
                addTwoClick("delete", Component.translatable("screen.variable_economy.accounts.delete"),
                        "screen.variable_economy.accounts.delete.tooltip", rightX + 74, bottom - 20, 70,
                        () -> send(AccountAction.DELETE, account, "", account, AccountRole.MEMBER));
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.variable_economy.accounts.back"), b -> onClose())
                .bounds(left + panelWidth - MARGIN - 60, bottom - 20, 60, 18).build());
    }

    /** A button that acts on its second click within a few seconds, saying so after the first. */
    private void addTwoClick(String id, Component label, String tooltip, int x, int y, int width, Runnable action) {
        boolean live = armed.equals(id) && System.currentTimeMillis() - armedAt < CONFIRM_MS;
        Component message = live ? Component.translatable("screen.variable_economy.accounts.confirm") : label;
        addRenderableWidget(Button.builder(message, b -> {
                    if (armed.equals(id) && System.currentTimeMillis() - armedAt < CONFIRM_MS) {
                        armed = "";
                        action.run();
                    } else {
                        armed = id;
                        armedAt = System.currentTimeMillis();
                    }
                    rebuildWidgets();
                })
                .bounds(x, y, width, y > top + panelHeight - 40 ? 18 : ROW_HEIGHT - 1)
                .tooltip(Tooltip.create(Component.translatable(tooltip))).build());
    }

    private static Component roleText(AccountRole role) {
        return Component.translatable("screen.variable_economy.accounts.role." + role.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void show(UUID account) {
        memberScroll = 0;
        armed = "";
        PacketDistributor.sendToServer(new BankPayloads.ShowAccountRequest(account));
    }

    private static void send(AccountAction action, UUID account, String text, UUID player, AccountRole role) {
        PacketDistributor.sendToServer(new BankPayloads.AccountRequest(action, account, text, player, role));
    }

    // ---- State and input -------------------------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        boolean expired = !armed.isEmpty() && System.currentTimeMillis() - armedAt >= CONFIRM_MS;
        if (expired) {
            armed = "";
        }
        if (seenVersion != ClientBankState.version() || expired) {
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX > left + MARGIN + LEFT_WIDTH) {
            int max = Math.max(0, ClientBankState.members().size() - memberRows);
            int scrolled = Mth.clamp(memberScroll - (int) Math.signum(scrollY), 0, max);
            if (scrolled != memberScroll) {
                memberScroll = scrolled;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Keep typing in a box from triggering the inventory key.
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

    @Override
    public void onClose() {
        // Back to the bank screen, over the same open bank.
        returning = true;
        parent.returnedFromAccounts();
        Objects.requireNonNull(minecraft).setScreen(parent);
    }

    @Override
    public void removed() {
        // Closed some other way (the server closed the bank): nothing to go back to.
        if (!returning) {
            parent.returnedFromAccounts();
            ClientBankState.clear();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- Rendering -------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int x = left;
        int y = top;
        graphics.drawString(font, title, x + MARGIN, y + 7, BankScreen.TEXT, false);
        graphics.drawString(font, Component.translatable("screen.variable_economy.accounts.yours"), x + MARGIN, y + TOP, BankScreen.TEXT_DIM, false);
        int bottom = y + panelHeight - 18;
        List<BankPayloads.InviteLine> invites = ClientBankState.invites();
        if (!invites.isEmpty()) {
            int createY = bottom - 20;
            int invitesTop = createY - 4 - (12 + invites.size() * ROW_HEIGHT);
            graphics.drawString(font, Component.translatable("screen.variable_economy.accounts.invitations"), x + MARGIN, invitesTop,
                    BankScreen.TEXT_DIM, false);
            for (int i = 0; i < invites.size(); i++) {
                BankPayloads.InviteLine invite = invites.get(i);
                String text = Component.translatable("screen.variable_economy.accounts.invitation", invite.name(),
                        ClientBankState.role(invite.role())).getString();
                graphics.drawString(font, font.plainSubstrByWidth(text, LEFT_WIDTH - 96), x + MARGIN, invitesTop + 12 + i * ROW_HEIGHT + 4,
                        BankScreen.TEXT, false);
            }
        }
        if (!ClientBankState.extraEnabled()) {
            graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.variable_economy.accounts.create.off").getString(),
                    LEFT_WIDTH), x + MARGIN, bottom - 16, BankScreen.TEXT_DIM, false);
        }

        int rightX = x + MARGIN + LEFT_WIDTH + 10;
        int rightWidth = x + panelWidth - MARGIN - rightX;
        Optional<BankPayloads.AccountLine> shown = ClientBankState.shown();
        if (shown.isPresent()) {
            BankPayloads.AccountLine line = shown.get();
            Component header = line.kind() == AccountKind.PARTY
                    ? Component.translatable("screen.variable_economy.accounts.party_members")
                    : Component.translatable("screen.variable_economy.accounts.members", ClientBankState.label(line));
            graphics.drawString(font, font.plainSubstrByWidth(header.getString(), rightWidth), rightX, y + TOP, BankScreen.TEXT_DIM, false);
            graphics.drawString(font, Component.translatable("screen.variable_economy.accounts.balance",
                    PriceFormat.money(PriceFormat.amount(line.balance()))), rightX, y + TOP + 11, BankScreen.TEXT_MONEY, false);
            List<BankPayloads.MemberLine> members = ClientBankState.members();
            if (line.kind() == AccountKind.PERSONAL) {
                graphics.drawString(font, Component.translatable("screen.variable_economy.accounts.no_members"), rightX, y + TOP + 28,
                        BankScreen.TEXT_DIM, false);
            }
            for (int i = 0; i < memberRows && memberScroll + i < members.size(); i++) {
                BankPayloads.MemberLine member = members.get(memberScroll + i);
                int rowY = y + TOP + 24 + i * ROW_HEIGHT;
                if (i % 2 == 1) {
                    graphics.fill(rightX, rowY, rightX + rightWidth, rowY + ROW_HEIGHT - 1, BankScreen.ROW_ALT);
                }
                graphics.drawString(font, font.plainSubstrByWidth(member.name(), rightWidth - 190), rightX + 2, rowY + 4,
                        member.role() == AccountRole.OWNER ? BankScreen.TEXT_MONEY : BankScreen.TEXT, false);
                boolean owner = line.kind() == AccountKind.NAMED && line.role() == AccountRole.OWNER;
                if (!owner || member.role() == AccountRole.OWNER) {
                    Component role = ClientBankState.role(member.role());
                    graphics.drawString(font, role, rightX + rightWidth - font.width(role) - 2, rowY + 4, BankScreen.TEXT_DIM, false);
                }
            }
            if (line.kind() == AccountKind.NAMED && line.role() == AccountRole.OWNER && !ClientBankState.sharedEnabled()) {
                graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.variable_economy.accounts.invite.off").getString(),
                        rightWidth), rightX, bottom - 38, BankScreen.TEXT_DIM, false);
            }
        }

        Component status = ClientBankState.status();
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), panelWidth - 2 * MARGIN), x + MARGIN, y + panelHeight - 12,
                    ClientBankState.statusIsError() ? BankScreen.TEXT_BAD : BankScreen.TEXT_GOOD, false);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(left - 1, top - 1, left + panelWidth + 1, top + panelHeight + 1, BankScreen.PANEL_BORDER);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, BankScreen.PANEL);
        graphics.fill(left + MARGIN, top + 18, left + panelWidth - MARGIN, top + 19, BankScreen.PANEL_BORDER);
    }
}
