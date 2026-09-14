package ch.kirby.commands;

import ch.kirby.SQL.DBConnection;
import ch.kirby.core.command.ButtonHandler;
import ch.kirby.model.GameStats;
import ch.kirby.service.StatsService;
import ch.kirby.core.command.Command;
import ch.kirby.util.SharedFormatter;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.entity.Guild;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.InteractionFollowupCreateSpec;
import discord4j.core.spec.MessageEditSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ch.kirby.util.SharedFormatter.*;

public class ServerLeaderboardCommand implements Command, ButtonHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerLeaderboardCommand.class);

    @Override
    public String getName() {
        return "serverleaderboard";
    }

    @Override
    public Mono<Void> handle(ChatInputInteractionEvent event) {
        return event.getInteraction().getGuildId()
                .map(guildId -> event.deferReply()
                        .then(event.getInteraction().getGuild()
                                .flatMap(guild -> fetchMemberIds(guild)
                                        .flatMap(memberIds -> Mono.fromCallable(() -> {
                                            try (Connection conn = new DBConnection().SQLDBConnection()) {
                                                StatsService service = new StatsService(conn);

                                                String game = event.getOption("game")
                                                        .flatMap(opt -> opt.getValue())
                                                        .map(v -> v.getRaw())
                                                        .orElse(null);

                                                int dayspan = event.getOption("dayspan")
                                                        .flatMap(opt -> opt.getValue())
                                                        .map(v -> Integer.parseInt(v.getRaw()))
                                                        .orElse(7);

                                                List<GameStats> leaderboard = "spotify".equalsIgnoreCase(game)
                                                        ? service.getServerSpotifyLeaderboard(memberIds, dayspan)
                                                        : service.getServerGameLeaderboard(memberIds, game, dayspan);

                                                EmbedCreateSpec embed = SharedFormatter.formatLeaderboard(
                                                        leaderboard, game, dayspan, true, guild.getName());

                                                return InteractionFollowupCreateSpec.builder()
                                                        .addEmbed(embed)
                                                        .addComponent(defaultStatsComponents("serverleaderboard", dayspan, 0L))
                                                        .build();
                                            }
                                        }).subscribeOn(Schedulers.boundedElastic()))))
                        .flatMap(event::createFollowup)
                        // a rejected followup used to surface only as a WARN, leaving the interaction unanswered
                        .onErrorResume(error -> {
                            LOGGER.error("Failed to deliver /serverleaderboard reply", error);
                            return event.createFollowup(InteractionFollowupCreateSpec.builder()
                                    .addEmbed(errorEmbed(error))
                                    .build());
                        })
                        .then())
                .orElseGet(() -> event.reply("This command can only be used in a server.").withEphemeral(true));
    }

    private Mono<Set<Long>> fetchMemberIds(Guild guild) {
        return guild.getMembers()
                .map(member -> member.getId().asLong())
                .collect(Collectors.toSet());
    }

    @Override
    public String getCommandPrefix() {
        return "serverleaderboard";
    }

    @Override
    public Mono<Void> handleButton(ButtonInteractionEvent event) {
        int dayspan = Integer.parseInt(event.getCustomId().split("_")[2]);

        return event.getInteraction().getGuildId()
                .map(guildId -> Mono.justOrEmpty(event.getMessage()).flatMap(message -> {
                    var embed = message.getEmbeds().get(0);
                    var title = embed.getTitle().orElse("");

                    String game;
                    if (title.contains("Server Game Leaderboard")) {
                        game = null;
                    } else if (title.contains("Server Spotify")) {
                        game = "spotify";
                    } else {
                        game = title.replace("🎮 Server Leaderboard for ", "");
                    }

                    var loadingSpec = MessageEditSpec.builder()
                            .embeds(List.of(loadingEmbed()))
                            .components(List.of(disabledStatsComponents("serverleaderboard", dayspan, 0L)))
                            .build();

                    return message.edit(loadingSpec)
                            .then(event.getInteraction().getGuild()
                                    .flatMap(guild -> fetchMemberIds(guild)
                                            .flatMap(memberIds -> Mono.fromCallable(() -> {
                                                try (Connection conn = new DBConnection().SQLDBConnection()) {
                                                    StatsService service = new StatsService(conn);
                                                    List<GameStats> leaderboard = "spotify".equalsIgnoreCase(game)
                                                            ? service.getServerSpotifyLeaderboard(memberIds, dayspan)
                                                            : service.getServerGameLeaderboard(memberIds, game, dayspan);
                                                    return new LeaderboardResult(leaderboard, guild.getName());
                                                }
                                            }).subscribeOn(Schedulers.boundedElastic()))))
                            .flatMap(result -> {
                                EmbedCreateSpec newEmbed = formatLeaderboard(
                                        result.leaderboard, game, dayspan, true, result.serverName);
                                var resultSpec = MessageEditSpec.builder()
                                        .embeds(List.of(newEmbed))
                                        .components(List.of(defaultStatsComponents("serverleaderboard", dayspan, 0L)))
                                        .build();
                                return message.edit(resultSpec);
                            })
                            // without this the message would sit on the loading embed forever
                            .onErrorResume(error -> {
                                LOGGER.error("Failed to refresh /serverleaderboard message", error);
                                return message.edit(MessageEditSpec.builder()
                                        .embeds(List.of(errorEmbed(error)))
                                        .components(List.of(defaultStatsComponents("serverleaderboard", dayspan, 0L)))
                                        .build());
                            });
                }).then())
                .orElse(Mono.empty());
    }

    private record LeaderboardResult(List<GameStats> leaderboard, String serverName) {}
}
