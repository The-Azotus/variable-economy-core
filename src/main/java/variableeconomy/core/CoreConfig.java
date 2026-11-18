package variableeconomy.core;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Core's server config, {@code config/variable_economy-server.toml}, loaded when a server starts. {@link #install} hands
 * its values to {@link CoreSettings}, which the rest of core reads.
 */
public final class CoreConfig {
    /** The config's file name. */
    public static final String FILE = "variable_economy-server.toml";
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<AccessMode> ACCESS_MODE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> IGNORED_COMPONENTS;
    public static final ModConfigSpec.BooleanValue LEADERBOARD_ENABLED;
    public static final ModConfigSpec.IntValue LEADERBOARD_SIZE;
    public static final ModConfigSpec.BooleanValue LEADERBOARD_EXCLUDE_OPERATORS;
    public static final ModConfigSpec.EnumValue<RankBy> LEADERBOARD_RANK_BY;
    public static final ModConfigSpec.BooleanValue EXTRA_ENABLED;
    public static final ModConfigSpec.IntValue MAX_PER_PLAYER;
    public static final ModConfigSpec.BooleanValue SHARED_ENABLED;
    public static final ModConfigSpec.IntValue MAX_MEMBERS;
    public static final ModConfigSpec.BooleanValue FTB_PARTY_ACCOUNT;
    public static final ModConfigSpec.IntValue HISTORY_SIZE;
    public static final ModConfigSpec.BooleanValue PAY_ENABLED;
    public static final ModConfigSpec.DoubleValue PAY_FEE_PERCENT;
    public static final ModConfigSpec.LongValue PAY_MIN_AMOUNT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ACCESS_MODE = builder
                .comment("How players reach the bank.",
                        "COMMAND_AND_BLOCK: the /bank command or the Bank block.",
                        "BLOCK_ONLY: only the Bank block (a Bank Card still opens the bank). Players without operator permission",
                        "(level 2) can't open the bank by command; operators and the console always can.")
                .defineEnum("access_mode", AccessMode.COMMAND_AND_BLOCK);
        IGNORED_COMPONENTS = builder
                .comment("Item data ignored when deciding whether a copy is plain enough to sell or deliver. The market",
                        "only buys items as they come fresh (not enchanted, damaged or renamed), but some mods add data to",
                        "every copy a player holds: Relics (and Reliquified Artifacts) adds relics:data, its levels and",
                        "rolled stats, as soon as a relic is in an inventory. Data listed here doesn't stop a sale; it is",
                        "lost with the item, and bought items always come fresh. List data component IDs.")
                .defineListAllowEmpty("ignored_components", List.of("relics:data"),
                        () -> "relics:data", id -> id instanceof String s && ResourceLocation.tryParse(s) != null);

        builder.comment("Accounts. Every player has a personal account, which always exists. These settings add more.").push("accounts");
        EXTRA_ENABLED = builder
                .comment("Players can open extra, named accounts (/bank account create, or the bank screen). Turning this off",
                        "stops new ones; existing accounts keep working.")
                .define("extra_enabled", false);
        MAX_PER_PLAYER = builder
                .comment("How many accounts one player may own, their personal account included. Ownership passing to a",
                        "member when the owner leaves may go past it.")
                .defineInRange("max_per_player", 3, 1, 100);
        SHARED_ENABLED = builder
                .comment("Named accounts can have members: members who deposit, spend and withdraw, and deposit-only members",
                        "who can only pay in. Needs extra_enabled, since only named accounts can be shared. Turning this off",
                        "stops new members; existing members stay.")
                .define("shared_enabled", false);
        MAX_MEMBERS = builder
                .comment("How many members one account may have, its owner included.")
                .defineInRange("max_members", 8, 2, 100);
        FTB_PARTY_ACCOUNT = builder
                .comment("With FTB Teams installed, each party gets a shared account its members use. When false (or FTB Teams",
                        "is removed), every party account ends at the next server start and its balance goes to the party's owner.")
                .define("ftb_party_account", true);
        builder.pop();

        builder.comment("/pay <player> <amount>: players paying each other, from their active account to the recipient's.").push("pay");
        PAY_ENABLED = builder
                .comment("Allow /pay. When false it answers that payments are turned off.")
                .define("enabled", true);
        PAY_FEE_PERCENT = builder
                .comment("A fee on each payment, as a percentage of the amount, rounded up to a whole bit. The sender pays it on",
                        "top, the recipient gets the whole amount, and the fee is destroyed: a money sink. 0: no fee.")
                .defineInRange("fee_percent", 0.0, 0.0, 100.0);
        PAY_MIN_AMOUNT = builder
                .comment("The smallest amount one payment may send, in bits.")
                .defineInRange("min_amount", 1L, 1L, Long.MAX_VALUE);
        builder.pop();

        builder.comment("Each account's transaction history: every balance change, with its reason and who caused it.").push("history");
        HISTORY_SIZE = builder
                .comment("How many of its newest changes each account keeps, for /bank history and the bank screen. 0 keeps none.")
                .defineInRange("size", 50, 0, 1000);
        builder.pop();

        builder.comment("The leaderboard in the bank screen and /bank top: the richest players, or accounts. An account's worth is",
                "its balance plus money owed to it and money add-ons hold for it (open exchange orders, for example).").push("leaderboard");
        LEADERBOARD_ENABLED = builder
                .comment("Show the leaderboard. When false the bank screen says it is turned off and no balances are sent.")
                .define("enabled", true);
        LEADERBOARD_SIZE = builder
                .comment("How many lines the leaderboard lists.")
                .defineInRange("size", 10, 1, 100);
        LEADERBOARD_RANK_BY = builder
                .comment("OWNER: one line per player, adding up every account they own (personal, named and party).",
                        "ACCOUNT: one line per account, a personal account under its player's name.")
                .defineEnum("rank_by", RankBy.OWNER);
        LEADERBOARD_EXCLUDE_OPERATORS = builder
                .comment("Leave operators, and accounts they own, off the leaderboard, for servers where admins hold test money.")
                .define("exclude_operators", false);
        builder.pop();
        SPEC = builder.build();
    }

    private CoreConfig() {
    }

    /** Hands {@link CoreSettings} its values. Once, while the mod is constructed. */
    public static void install() {
        CoreSettings.install(new CoreSettings.Source() {
            @Override
            public boolean blockOnly() {
                return accessMode() == AccessMode.BLOCK_ONLY;
            }

            @Override
            public Set<ResourceLocation> ignoredComponents() {
                return CoreConfig.ignoredComponents();
            }

            @Override
            public boolean leaderboardEnabled() {
                return SPEC.isLoaded() ? LEADERBOARD_ENABLED.get() : LEADERBOARD_ENABLED.getDefault();
            }

            @Override
            public int leaderboardSize() {
                return SPEC.isLoaded() ? LEADERBOARD_SIZE.get() : LEADERBOARD_SIZE.getDefault();
            }

            @Override
            public boolean leaderboardExcludeOperators() {
                return SPEC.isLoaded() ? LEADERBOARD_EXCLUDE_OPERATORS.get() : LEADERBOARD_EXCLUDE_OPERATORS.getDefault();
            }

            @Override
            public boolean leaderboardByAccount() {
                return (SPEC.isLoaded() ? LEADERBOARD_RANK_BY.get() : LEADERBOARD_RANK_BY.getDefault()) == RankBy.ACCOUNT;
            }

            @Override
            public boolean extraAccountsEnabled() {
                return SPEC.isLoaded() ? EXTRA_ENABLED.get() : EXTRA_ENABLED.getDefault();
            }

            @Override
            public int maxAccountsPerPlayer() {
                return SPEC.isLoaded() ? MAX_PER_PLAYER.get() : MAX_PER_PLAYER.getDefault();
            }

            @Override
            public boolean sharedAccountsEnabled() {
                return SPEC.isLoaded() ? SHARED_ENABLED.get() : SHARED_ENABLED.getDefault();
            }

            @Override
            public int maxMembers() {
                return SPEC.isLoaded() ? MAX_MEMBERS.get() : MAX_MEMBERS.getDefault();
            }

            @Override
            public boolean payEnabled() {
                return SPEC.isLoaded() ? PAY_ENABLED.get() : PAY_ENABLED.getDefault();
            }

            @Override
            public double payFeePercent() {
                return SPEC.isLoaded() ? PAY_FEE_PERCENT.get() : PAY_FEE_PERCENT.getDefault();
            }

            @Override
            public long payMinAmount() {
                return SPEC.isLoaded() ? PAY_MIN_AMOUNT.get() : PAY_MIN_AMOUNT.getDefault();
            }

            @Override
            public int historySize() {
                return SPEC.isLoaded() ? HISTORY_SIZE.get() : HISTORY_SIZE.getDefault();
            }

            @Override
            public boolean ftbPartyAccounts() {
                return SPEC.isLoaded() ? FTB_PARTY_ACCOUNT.get() : FTB_PARTY_ACCOUNT.getDefault();
            }
        });
    }

    /** How the leaderboard ranks: one line per player (adding up what they own), or per account. */
    public enum RankBy { OWNER, ACCOUNT }

    /** Whether players reach the bank by command and block, or by block only. */
    public enum AccessMode { COMMAND_AND_BLOCK, BLOCK_ONLY }

    // A server config only loads when a server starts, so each getter falls back to the default before then.

    public static AccessMode accessMode() {
        return SPEC.isLoaded() ? ACCESS_MODE.get() : ACCESS_MODE.getDefault();
    }

    /** {@link #IGNORED_COMPONENTS} as IDs. Clients get the server's value: NeoForge syncs server configs. */
    public static Set<ResourceLocation> ignoredComponents() {
        Set<ResourceLocation> ids = new HashSet<>();
        for (String id : SPEC.isLoaded() ? IGNORED_COMPONENTS.get() : IGNORED_COMPONENTS.getDefault()) {
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            if (parsed != null) {
                ids.add(parsed);
            }
        }
        return ids;
    }
}
