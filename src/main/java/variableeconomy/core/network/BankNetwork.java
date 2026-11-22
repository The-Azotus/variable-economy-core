package variableeconomy.core.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import variableeconomy.core.client.ClientBankState;

/** The bank's packets. */
public final class BankNetwork {
    private static final String PROTOCOL_VERSION = "3";

    private BankNetwork() {
    }

    /**
     * Handlers run on the main thread (the registrar default), which the bank requires. The clientbound handlers are
     * lambdas rather than method references so a dedicated server never loads the client classes.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(BankPayloads.BankRequest.TYPE, BankPayloads.BankRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                BankSync.handleBankRequest(payload, player);
            }
        });
        registrar.playToServer(BankPayloads.ShowAccountRequest.TYPE, BankPayloads.ShowAccountRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                BankSync.handleShowAccount(payload, player);
            }
        });
        registrar.playToServer(BankPayloads.AccountRequest.TYPE, BankPayloads.AccountRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                BankSync.handleAccountRequest(payload, player);
            }
        });
        registrar.playToServer(BankPayloads.HistoryRequest.TYPE, BankPayloads.HistoryRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                BankSync.handleHistoryRequest(payload, player);
            }
        });
        registrar.playToServer(BankPayloads.LeaderboardRequest.TYPE, BankPayloads.LeaderboardRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                BankSync.handleLeaderboardRequest(player);
            }
        });
        registrar.playToClient(BankPayloads.BankState.TYPE, BankPayloads.BankState.CODEC,
                (payload, context) -> ClientBankState.applyState(payload));
        registrar.playToClient(BankPayloads.BankOutcome.TYPE, BankPayloads.BankOutcome.CODEC,
                (payload, context) -> ClientBankState.applyOutcome(payload));
        registrar.playToClient(BankPayloads.AccountOutcome.TYPE, BankPayloads.AccountOutcome.CODEC,
                (payload, context) -> ClientBankState.applyAccountOutcome(payload));
        registrar.playToClient(BankPayloads.History.TYPE, BankPayloads.History.CODEC,
                (payload, context) -> ClientBankState.applyHistory(payload));
        registrar.playToClient(BankPayloads.Leaderboard.TYPE, BankPayloads.Leaderboard.CODEC,
                (payload, context) -> ClientBankState.applyLeaderboard(payload));
    }
}
