package variableeconomy.core.compat;

import variableeconomy.core.CoreModule;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import variableeconomy.core.bank.Party;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The only class allowed to import FTB Teams, an optional dependency. Without it nothing here touches its classes:
 * they're only referenced from {@link Present}, which loads only when FTB Teams does.
 * <p>
 * Only party teams count. FTB Teams gives every player a personal "player team" too; that isn't a shared account.
 * Checked against 2101.1.11 by reflection once; if the API doesn't match, the mod's own teams are used instead.
 * Server thread only.
 */
public final class FtbTeamsBridge {
    public static final String MOD_ID = "ftbteams";
    private static final boolean AVAILABLE = ModList.get().isLoaded(MOD_ID) && Present.probe();

    private FtbTeamsBridge() {
    }

    /** Whether FTB Teams is installed and its API matches, so its parties can have accounts. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** The party {@code player} belongs to; empty when they're in none, or teams aren't loaded yet. */
    public static Optional<Party> teamOf(UUID player) {
        if (!AVAILABLE) {
            return Optional.empty();
        }
        try {
            return Present.teamOf(player);
        } catch (RuntimeException | LinkageError e) {
            CoreModule.LOGGER.error("FTB Teams lookup failed", e);
            return Optional.empty();
        }
    }

    /** The party with the ID {@code team}, if it still exists. */
    public static Optional<Party> teamById(UUID team) {
        if (!AVAILABLE) {
            return Optional.empty();
        }
        try {
            return Present.teamById(team);
        } catch (RuntimeException | LinkageError e) {
            CoreModule.LOGGER.error("FTB Teams lookup failed", e);
            return Optional.empty();
        }
    }

    /** Calls {@code onDeleted} when a party is deleted (its last member left, or it was disbanded). */
    public static void onPartyDeleted(Consumer<Party> onDeleted) {
        if (AVAILABLE) {
            Present.onPartyDeleted(onDeleted);
        }
    }

    /** What happens to parties, besides being deleted ({@link #onPartyDeleted}). Each call comes after the change. */
    public interface PartyListener {
        /** A party was created; {@code team} is it as it is now. */
        void created(Party team);

        /** {@code player} joined the party. */
        void joined(Party team, UUID player);

        /** {@code player} left the party; {@code deleted} when that ended it (then {@link #onPartyDeleted} fires too). */
        void left(Party team, UUID player, boolean deleted);

        /** The party's owner changed from {@code from} to {@code to}. */
        void ownerChanged(Party team, UUID from, UUID to);
    }

    /** Calls {@code listener} as parties change. Each event is hooked on its own, so one FTB Teams change can't stop the rest. */
    public static void listen(PartyListener listener) {
        if (AVAILABLE) {
            try {
                Present.listen(listener);
            } catch (RuntimeException | LinkageError e) {
                CoreModule.LOGGER.error("Couldn't listen to FTB Teams party changes; account events for parties won't be posted", e);
            }
        }
    }

    /** Everything that touches FTB Teams classes. */
    private static final class Present {
        static boolean probe() {
            try {
                requireReturn(FTBTeamsAPI.class.getMethod("api").getReturnType(), FTBTeamsAPI.API.class);
                requireReturn(FTBTeamsAPI.API.class.getMethod("getManager").getReturnType(), TeamManager.class);
                requireReturn(TeamManager.class.getMethod("getTeamForPlayerID", UUID.class).getReturnType(), Optional.class);
                requireReturn(TeamManager.class.getMethod("getTeamByID", UUID.class).getReturnType(), Optional.class);
                requireReturn(Team.class.getMethod("isPartyTeam").getReturnType(), boolean.class);
                requireReturn(Team.class.getMethod("getId").getReturnType(), UUID.class);
                requireReturn(Team.class.getMethod("getOwner").getReturnType(), UUID.class);
                requireReturn(Team.class.getMethod("getMembers").getReturnType(), Set.class);
                requireReturn(Team.class.getMethod("getShortName").getReturnType(), String.class);
                TeamEvent.class.getField("DELETED");
                CoreModule.LOGGER.info("FTB Teams found: its parties can share accounts in the mod's own bank");
                return true;
            } catch (ReflectiveOperationException | LinkageError e) {
                CoreModule.LOGGER.error("FTB Teams is installed but its team API does not match what this mod was built "
                        + "against (2101.1.11); parties get no accounts", e);
                return false;
            }
        }

        static Optional<Party> teamOf(UUID player) {
            FTBTeamsAPI.API api = FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) {
                return Optional.empty();
            }
            return api.getManager().getTeamForPlayerID(player).filter(Team::isPartyTeam).map(Present::party);
        }

        static Optional<Party> teamById(UUID id) {
            FTBTeamsAPI.API api = FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) {
                return Optional.empty();
            }
            return api.getManager().getTeamByID(id).filter(Team::isPartyTeam).map(Present::party);
        }

        static void onPartyDeleted(Consumer<Party> onDeleted) {
            TeamEvent.DELETED.register(event -> {
                Team team = event.getTeam();
                if (team.isPartyTeam()) {
                    onDeleted.accept(party(team));
                }
            });
        }

        static void listen(PartyListener listener) {
            TeamEvent.CREATED.register(event -> {
                if (event.getTeam().isPartyTeam()) {
                    listener.created(party(event.getTeam()));
                }
            });
            TeamEvent.PLAYER_JOINED_PARTY.register(event -> {
                if (event.getTeam().isPartyTeam()) {
                    listener.joined(party(event.getTeam()), event.getPlayer().getUUID());
                }
            });
            TeamEvent.PLAYER_LEFT_PARTY.register(event -> {
                if (event.getTeam().isPartyTeam()) {
                    listener.left(party(event.getTeam()), event.getPlayerId(), event.getTeamDeleted());
                }
            });
            TeamEvent.OWNERSHIP_TRANSFERRED.register(event -> {
                if (event.getTeam().isPartyTeam() && event.getFrom() != null && event.getToProfile() != null) {
                    listener.ownerChanged(party(event.getTeam()), event.getFrom().getUUID(), event.getToProfile().getId());
                }
            });
        }

        private static Party party(Team team) {
            UUID owner = team.getOwner();
            List<UUID> members = new ArrayList<>();
            members.add(owner);
            team.getMembers().stream().filter(id -> !id.equals(owner)).sorted().forEach(members::add);
            return new Party(team.getId(), displayName(team.getShortName()), owner, members);
        }

        /**
         * A party's name as players type it: FTB Teams' short name (anything but letters, digits and '_' replaced by '_') without the
         * {@code #} and ID it ends with, so a command can take it as one word.
         */
        private static String displayName(String shortName) {
            int hash = shortName.lastIndexOf('#');
            return hash > 0 ? shortName.substring(0, hash) : shortName;
        }

        private static void requireReturn(Class<?> actual, Class<?> expected) throws NoSuchMethodException {
            if (actual != expected) {
                throw new NoSuchMethodException("expected " + expected.getName() + " but found " + actual.getName());
            }
        }
    }
}
