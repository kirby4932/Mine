package ch.kirby.util;

import ch.kirby.model.GameStats;
import discord4j.core.object.component.ActionComponent;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.spec.EmbedCreateSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ch.kirby.model.SpotifyStats;


public class SharedFormatter {

    // Discord rejects an embed field value longer than this with a 400, so we only ever build up to it
    private static final int FIELD_VALUE_LIMIT = 1024;

    public static String formatBreakdown(Map<String, Double> breakdown) {
        List<String> lines = breakdown.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .map(e -> String.format("• %s: `%,.2f h`\n", e.getKey(), e.getValue()))
                .toList();

        if (lines.isEmpty()) {
            return "No data found";
        }

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String line : lines) {
            // hold back room for the overflow notice so appending it later cannot push us over
            boolean isLast = shown == lines.size() - 1;
            int reserve = isLast ? 0 : overflowNotice(lines.size()).length();
            if (sb.length() + line.length() + reserve > FIELD_VALUE_LIMIT) break;
            sb.append(line);
            shown++;
        }
        if (shown == 0) {
            return clampFieldValue(lines.get(0));
        }
        if (shown < lines.size()) {
            sb.append(overflowNotice(lines.size() - shown));
        }
        return sb.toString();
    }

    private static String overflowNotice(int hidden) {
        return String.format("… and %d more", hidden);
    }

    // backstop: Application.name is a TEXT column, so nothing in the schema bounds a single line
    private static String clampFieldValue(String value) {
        return value.length() <= FIELD_VALUE_LIMIT
                ? value
                : value.substring(0, FIELD_VALUE_LIMIT - 1) + "…";
    }

    public static EmbedCreateSpec errorEmbed(Throwable error) {
        String reason = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        return EmbedCreateSpec.builder()
                .title("⚠️ Something went wrong")
                .description(clampFieldValue(reason))
                .build();
    }

    public static EmbedCreateSpec formatLeaderboard(List<GameStats> stats, String game, int dayspan) {
        return formatLeaderboard(stats, game, dayspan, false, null);
    }

    public static EmbedCreateSpec formatLeaderboard(List<GameStats> stats, String game, int dayspan, boolean serverScoped, String serverName) {
        String title;
        if (serverScoped) {
            title = (game == null)
                    ? "🏆 Server Game Leaderboard"
                    : ("spotify".equalsIgnoreCase(game)
                    ? "🎧 Server Spotify Leaderboard"
                    : "🎮 Server Leaderboard for " + game);
        } else {
            title = (game == null)
                    ? "🏆 Global Game Leaderboard"
                    : ("spotify".equalsIgnoreCase(game)
                    ? "🎧 Spotify Listener Leaderboard"
                    : "🎮 Leaderboard for " + game);
        }

        StringBuilder description = new StringBuilder();
        int rank = 1;
        for (GameStats stat : stats) {
            description.append(String.format(
                    "`#%02d` **%s** - %s: `%.2f hours`\n",
                    rank++, stat.getUsername(), stat.getGamename(), stat.getHoursPlayed()
            ));
        }

        if (stats.isEmpty()) {
            description.append("No data found for this server.");
        }

        var builder = EmbedCreateSpec.builder()
                .title(title)
                .description(description.toString());

        String footer = "Timespan: " + dayspan + " days";
        if (serverScoped && serverName != null) {
            footer = serverName + " | " + footer;
        }
        builder.footer(footer, null);

        return builder.build();
    }

    public static EmbedCreateSpec formatSpotifyStats(String username, List<SpotifyStats> songs, List<SpotifyStats> artists, int dayspan) {
        StringBuilder songSection = new StringBuilder();
        int rank = 1;
        for (SpotifyStats song : songs) {
            songSection.append(String.format("`#%02d` %-30s: `%,.2f min`\n",
                    rank++, truncate(song.getValue(), 30), song.getMinutesPlayed()));
        }

        StringBuilder artistSection = new StringBuilder();
        rank = 1;
        for (SpotifyStats artist : artists) {
            artistSection.append(String.format("`#%02d` %-30s: `%,.2f min`\n",
                    rank++, truncate(artist.getValue(), 30), artist.getMinutesPlayed()));
        }

        return EmbedCreateSpec.builder()
                .title("🎧 Spotify Stats for " + username)
                .addField("🎵 Top Songs", clampFieldValue(songSection.length() > 0 ? songSection.toString() : "No data found"), false)
                .addField("🎤 Top Artists", clampFieldValue(artistSection.length() > 0 ? artistSection.toString() : "No data found"), false)
                .footer("Timespan: " + dayspan + " days", null)
                .build();
    }

    private static String truncate(String text, int maxLength) {
        return (text.length() <= maxLength) ? text : text.substring(0, maxLength - 1) + "…";
    }

    public static List<EmbedCreateSpec> defaultStatsEmbed(GameStats stats, int dayspan) {
        List<EmbedCreateSpec> specs = new ArrayList<>();
        specs.add(
                EmbedCreateSpec.builder()
                        .title("Stats for " + stats.getUsername())
                        .description("Total playtime over the last " + dayspan + " days.")
                        .addField("Total Hours", stats.getTotalHours() + "h", false)
                        .addField("Breakdown", clampFieldValue(formatBreakdown(stats.getGameBreakdown())), false)
                        .build()
        );
        return specs;
    }

    public static ActionRow defaultStatsComponents(String commandPrefix, int dayspan, long userId) {
        return ActionRow.of(
                styleButton(commandPrefix, 7, dayspan, userId),
                styleButton(commandPrefix, 14, dayspan, userId),
                styleButton(commandPrefix, 30, dayspan, userId)
        );
    }

    public static ActionRow disabledStatsComponents(String commandPrefix, int dayspan, long userId) {
        return ActionRow.of(
                styleButton(commandPrefix, 7, dayspan, userId).disabled(),
                styleButton(commandPrefix, 14, dayspan, userId).disabled(),
                styleButton(commandPrefix, 30, dayspan, userId).disabled()
        );
    }

    public static EmbedCreateSpec loadingEmbed() {
        return EmbedCreateSpec.builder()
                .description("Loading...")
                .build();
    }

    private static Button styleButton(String commandPrefix, int value, int selected, long userId) {
        String label = value + " Days";
        String id = commandPrefix + "_dayspan_" + value + "_user_" + userId;

        return (value == selected)
                ? Button.primary(id, label)
                : Button.secondary(id, label);
    }
}
