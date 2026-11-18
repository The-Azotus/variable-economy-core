package variableeconomy.core;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;

/**
 * The core's server settings. They come from {@code config/variable_economy-server.toml},
 * whose keys are named below; {@link CoreConfig} installs them when the mod is constructed. A server config only
 * loads when a server starts, so until then each value is the config's default.
 */
public final class CoreSettings {
    /** Where the values come from. */
    public interface Source {
        /**
         * Whether players without operator permission must use a block, not commands (access_mode BLOCK_ONLY).
         *
         * @return the value
         */
        boolean blockOnly();

        /**
         * Item data that doesn't stop a copy counting as plain (ignored_components).
         *
         * @return the value
         */
        java.util.Set<net.minecraft.resources.ResourceLocation> ignoredComponents();

        /**
         * Whether the leaderboard is shown (leaderboard.enabled).
         *
         * @return the value
         */
        boolean leaderboardEnabled();

        /**
         * How many players the leaderboard lists (leaderboard.size).
         *
         * @return the value
         */
        int leaderboardSize();

        /**
         * Whether operators are left off the leaderboard (leaderboard.exclude_operators).
         *
         * @return the value
         */
        boolean leaderboardExcludeOperators();

        /**
         * Whether the leaderboard ranks accounts rather than players (leaderboard.rank_by ACCOUNT).
         *
         * @return the value
         */
        boolean leaderboardByAccount();

        /**
         * Whether players can open extra, named accounts (accounts.extra_enabled).
         *
         * @return the value
         */
        boolean extraAccountsEnabled();

        /**
         * How many accounts one player may own, personal included (accounts.max_per_player).
         *
         * @return the value
         */
        int maxAccountsPerPlayer();

        /**
         * Whether named accounts can have members (accounts.shared_enabled).
         *
         * @return the value
         */
        boolean sharedAccountsEnabled();

        /**
         * How many members one account may have, owner included (accounts.max_members).
         *
         * @return the value
         */
        int maxMembers();

        /**
         * Whether FTB Teams parties get an account (accounts.ftb_party_account).
         *
         * @return the value
         */
        boolean ftbPartyAccounts();

        /**
         * How many lines of history each account keeps (history.size).
         *
         * @return the value
         */
        int historySize();

        /**
         * Whether players can pay each other with /pay (pay.enabled).
         *
         * @return the value
         */
        boolean payEnabled();

        /**
         * The fee on each /pay, as a percentage of the amount (pay.fee_percent).
         *
         * @return the value
         */
        double payFeePercent();

        /**
         * The smallest amount /pay sends (pay.min_amount).
         *
         * @return the value
         */
        long payMinAmount();
    }

    private static volatile Source source;

    private CoreSettings() {
    }

    /** Installs where the values come from. Once, while the mod is constructed. */
    public static void install(Source values) {
        source = Objects.requireNonNull(values, "values");
    }

    private static Source source() {
        Source installed = source;
        if (installed == null) {
            throw new IllegalStateException("CoreSettings isn't installed yet");
        }
        return installed;
    }

    /** Whether players without operator permission must use a block, not commands (access_mode BLOCK_ONLY). */
    public static boolean blockOnly() {
        return source().blockOnly();
    }

    /** Item data that doesn't stop a copy counting as plain (ignored_components). */
    public static java.util.Set<net.minecraft.resources.ResourceLocation> ignoredComponents() {
        return source().ignoredComponents();
    }

    /** Whether the leaderboard is shown (leaderboard.enabled). */
    public static boolean leaderboardEnabled() {
        return source().leaderboardEnabled();
    }

    /** How many players the leaderboard lists (leaderboard.size). */
    public static int leaderboardSize() {
        return source().leaderboardSize();
    }

    /** Whether operators are left off the leaderboard (leaderboard.exclude_operators). */
    public static boolean leaderboardExcludeOperators() {
        return source().leaderboardExcludeOperators();
    }

    /** Whether the leaderboard ranks accounts rather than players (leaderboard.rank_by ACCOUNT). */
    public static boolean leaderboardByAccount() {
        return source().leaderboardByAccount();
    }

    /** Whether players can open extra, named accounts (accounts.extra_enabled). */
    public static boolean extraAccountsEnabled() {
        return source().extraAccountsEnabled();
    }

    /** How many accounts one player may own, personal included (accounts.max_per_player). */
    public static int maxAccountsPerPlayer() {
        return source().maxAccountsPerPlayer();
    }

    /** Whether named accounts can have members (accounts.shared_enabled). */
    public static boolean sharedAccountsEnabled() {
        return source().sharedAccountsEnabled();
    }

    /** How many members one account may have, owner included (accounts.max_members). */
    public static int maxMembers() {
        return source().maxMembers();
    }

    /** Whether FTB Teams parties get an account (accounts.ftb_party_account). */
    public static boolean ftbPartyAccounts() {
        return source().ftbPartyAccounts();
    }

    /** How many lines of history each account keeps (history.size); 0 keeps none. */
    public static int historySize() {
        return source().historySize();
    }

    /** Whether players can pay each other with /pay (pay.enabled). */
    public static boolean payEnabled() {
        return source().payEnabled();
    }

    /** The fee on each /pay, as a percentage of the amount (pay.fee_percent). */
    public static double payFeePercent() {
        return source().payFeePercent();
    }

    /** The smallest amount /pay sends (pay.min_amount). */
    public static long payMinAmount() {
        return source().payMinAmount();
    }

    /** Whether {@code source} may use the core's commands: always for operators and the console, otherwise unless block-only. */
    public static boolean commandsAllowed(CommandSourceStack source) {
        return commandsAllowed(blockOnly(), source.hasPermission(Commands.LEVEL_GAMEMASTERS));
    }

    /** Whether {@code player} may use the core's commands. */
    public static boolean commandsAllowed(Player player) {
        return commandsAllowed(blockOnly(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS));
    }

    /** Operators always may; everyone else unless the server is block-only. */
    public static boolean commandsAllowed(boolean blockOnly, boolean operator) {
        return operator || !blockOnly;
    }
}
