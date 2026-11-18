package variableeconomy.core;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import variableeconomy.core.api.event.BalanceChangedEvent;
import variableeconomy.core.bank.Party;
import variableeconomy.core.block.CoreBlocks;
import variableeconomy.core.coin.Coins;
import variableeconomy.core.command.AdminAuditCommands;
import variableeconomy.core.command.AdminBankCommands;
import variableeconomy.core.command.AdminMoneyCommands;
import variableeconomy.core.command.AdminStatsCommands;
import variableeconomy.core.command.BankCommand;
import variableeconomy.core.command.HistoryCommands;
import variableeconomy.core.command.PayCommand;
import variableeconomy.core.compat.FtbTeamsBridge;
import variableeconomy.core.compat.KubeJsBridge;
import variableeconomy.core.compat.StagesBridge;
import variableeconomy.core.menu.BankMenu;
import variableeconomy.core.network.BankNetwork;
import variableeconomy.core.network.BankSync;
import variableeconomy.core.service.BalanceEvents;
import variableeconomy.core.service.CoreServices;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Core: the bank, accounts, coins, the leaderboard, owed payments, the audit log and item gating. Registers its block,
 * items, menu, packets, commands and server events, and installs the core API. Every add-on builds on this.
 */
@Mod(CoreModule.MOD_ID)
public final class CoreModule {
    /** Core's namespace for its blocks, items, menus, packets and saved data. */
    public static final String MOD_ID = "variable_economy";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** The mod's entry point: registers its server config, then its blocks, menus, packets, commands and server events. */
    public CoreModule(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, CoreConfig.SPEC, CoreConfig.FILE);
        CoreConfig.install();
        CoreServices.install();
        BankMenu.MENUS.register(modBus);
        CoreBlocks.register(modBus);
        Coins.register(modBus);
        modBus.addListener(BankNetwork::register);

        // With ProgressiveStages, which items a player has unlocked changes with their stages.
        StagesBridge.listen(CoreServices.ITEM_GATE::stagesChanged);
        // An open bank screen shows a new balance straight away, whatever changed it.
        NeoForge.EVENT_BUS.addListener((BalanceChangedEvent event) -> BankSync.balanceChanged(event.getServer(), event.getAccount()));
        // Server scripts hear balance changes too, when KubeJS is installed.
        NeoForge.EVENT_BUS.addListener(KubeJsBridge::balanceChanged);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
            LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin")
                    .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS));
            AdminBankCommands.addTo(admin);
            AdminMoneyCommands.addTo(admin);
            AdminAuditCommands.addTo(admin);
            HistoryCommands.addTo(admin);
            AdminStatsCommands.addTo(admin);
            BankCommand.register(event.getDispatcher(), admin);
            PayCommand.register(event.getDispatcher());
        });
        listenToTeams();

        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            BankSync.tick(event.getServer());
            // Payments owed while the bank couldn't be reached.
            CoreServices.MONEY.payOwed(event.getServer());
        });
        // Last of all in the tick, so its changes are complete: lock changes, then balance changes.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ServerTickEvent.Post event) -> {
            CoreServices.ITEM_GATE.flush(event.getServer());
            BalanceEvents.flush();
        });
        // Changes made while the server stops are posted before it saves.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ServerStoppingEvent event) -> BalanceEvents.flush());
        // First at login, so every add-on finds the player's personal account.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                CoreServices.ACCOUNTS.seen(player);
            }
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ServerStartedEvent event) -> CoreServices.ACCOUNTS.seed(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            BalanceEvents.clear();
            CoreServices.ITEM_GATE.clear();
            CoreServices.ACCOUNTS.clearInvitations();
            BankSync.clear();
        });
    }

    /** Party accounts follow FTB Teams parties: created, joined, left, handed over and deleted. */
    private static void listenToTeams() {
        // A deleted FTB Teams party's account goes to its owner, so no money is lost with it.
        FtbTeamsBridge.onPartyDeleted(party -> withServer(server -> {
            long paid = CoreServices.ACCOUNTS.partyDeleted(server, party);
            if (paid > 0) {
                LOGGER.info("FTB Teams party {} was deleted; its {} bits went to its owner {}", party.name(), paid, party.owner());
            }
        }));
        FtbTeamsBridge.listen(new FtbTeamsBridge.PartyListener() {
            @Override
            public void created(Party party) {
                withServer(server -> CoreServices.ACCOUNTS.partyAccount(server, party));
            }

            @Override
            public void joined(Party party, UUID player) {
                withServer(server -> CoreServices.ACCOUNTS.partyMemberJoined(server, party, player));
            }

            @Override
            public void left(Party party, UUID player, boolean deleted) {
                if (!deleted) {
                    withServer(server -> CoreServices.ACCOUNTS.partyMemberLeft(server, party, player));
                }
            }

            @Override
            public void ownerChanged(Party party, UUID from, UUID to) {
                withServer(server -> CoreServices.ACCOUNTS.partyOwnerChanged(server, party, from, party.members().contains(from), to));
            }
        });
    }

    /** Runs {@code action} with the running server, if there is one. */
    private static void withServer(Consumer<MinecraftServer> action) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            action.accept(server);
        }
    }
}
