package org.pexserver.pac.check.shared;

import io.papermc.paper.configuration.GlobalConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Objects;

/** Resolves server, world, entity, and team collision switches before inspecting entity movement. */
public final class EntityCollisionPolicy {
    private EntityCollisionPolicy() { }

    public static boolean canCollide(Entity first, Entity second) {
        if (first == null || second == null || first == second) return false;
        if (first.getUniqueId() == null || second.getUniqueId() == null) return false;
        if (!sameWorld(first, second) || !paperWorldAllowsCollision(first.getWorld(), first, second)) return false;
        if (!entityCollidable(first, second) || !entityCollidable(second, first)) return false;
        if (first instanceof Player && second instanceof Player
                && !GlobalConfiguration.get().collisions.enablePlayerCollisions) return false;

        Scoreboard main = Bukkit.getScoreboardManager() == null ? null
                : Bukkit.getScoreboardManager().getMainScoreboard();
        if (!allowsOnScoreboard(main, first, second)) return false;
        Scoreboard firstBoard = first instanceof Player player ? player.getScoreboard() : null;
        if (!Objects.equals(firstBoard, main) && !allowsOnScoreboard(firstBoard, first, second))
            return false;
        Scoreboard secondBoard = second instanceof Player player ? player.getScoreboard() : null;
        return Objects.equals(secondBoard, main) || Objects.equals(secondBoard, firstBoard)
                || allowsOnScoreboard(secondBoard, first, second);
    }

    private static boolean allowsOnScoreboard(Scoreboard scoreboard, Entity first, Entity second) {
        if (scoreboard == null) return true;
        Team firstTeam = entityTeam(scoreboard, first);
        Team secondTeam = entityTeam(scoreboard, second);
        boolean sameTeam = firstTeam != null && secondTeam != null
                && firstTeam.getName().equals(secondTeam.getName());
        Team.OptionStatus firstRule = firstTeam == null ? Team.OptionStatus.ALWAYS
                : firstTeam.getOption(Team.Option.COLLISION_RULE);
        Team.OptionStatus secondRule = secondTeam == null ? Team.OptionStatus.ALWAYS
                : secondTeam.getOption(Team.Option.COLLISION_RULE);
        return allowsPair(firstRule, secondRule, sameTeam);
    }

    private static boolean paperWorldAllowsCollision(World world, Entity first, Entity second) {
        if (!(world instanceof CraftWorld craftWorld)) return true;
        var collisions = craftWorld.getHandle().paperConfig().collisions;
        boolean playerInvolved = first instanceof Player || second instanceof Player;
        boolean vehicleInvolved = first instanceof Vehicle || second instanceof Vehicle;
        return allowsPaperWorldCollision(collisions.maxEntityCollisions,
                collisions.onlyPlayersCollide, collisions.allowVehicleCollisions,
                playerInvolved, vehicleInvolved);
    }

    private static boolean sameWorld(Entity first, Entity second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld());
    }

    static boolean allowsPaperWorldCollision(int maxEntityCollisions,
                                             boolean onlyPlayersCollide,
                                             boolean allowVehicleCollisions,
                                             boolean playerInvolved,
                                             boolean vehicleInvolved) {
        // Paper exposes the actual world collision limit here. Vanilla's
        // max_entity_cramming gamerule only controls cramming damage; it does
        // not turn entity pushing/collision off, so it must not gate this check.
        if (maxEntityCollisions <= 0) return false;
        if (!onlyPlayersCollide) return true;
        if (!playerInvolved) return false;
        return !vehicleInvolved || allowVehicleCollisions;
    }

    static boolean allowsPair(Team.OptionStatus first, Team.OptionStatus second, boolean sameTeam) {
        return allows(first, sameTeam) && allows(second, sameTeam);
    }

    static boolean allows(Team.OptionStatus status, boolean sameTeam) {
        return switch (status) {
            case ALWAYS -> true;
            case NEVER -> false;
            case FOR_OWN_TEAM -> sameTeam;
            case FOR_OTHER_TEAMS -> !sameTeam;
        };
    }

    private static Team entityTeam(Scoreboard scoreboard, Entity entity) {
        if (scoreboard == null) return null;
        try {
            return scoreboard.getEntityTeam(entity);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean entityCollidable(Entity entity, Entity other) {
        // Paper explicitly warns that isCollidable() is not authoritative for
        // players: clients can predict player collisions, so player policy is
        // controlled by scoreboard teams instead. The actual uncancelled
        // EntityCollideWithEntityEvent plus the team/world checks below are the
        // evidence for player pairs.
        if (!(entity instanceof LivingEntity living)) return true;
        return entityCollisionFlagAllows(entity instanceof Player, living.isCollidable(),
                living.getCollidableExemptions().contains(other.getUniqueId()));
    }

    static boolean entityCollisionFlagAllows(boolean player, boolean collidable, boolean exemption) {
        // Bukkit exemptions invert the flag in both directions. For players,
        // both the flag and exemptions are advisory; team rules govern them.
        return player || collidable != exemption;
    }

}
