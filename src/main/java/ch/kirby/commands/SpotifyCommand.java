package ch.kirby.commands;

import ch.kirby.SQL.DBConnection;
import ch.kirby.core.command.ButtonHandler;
import ch.kirby.core.command.Command;
import ch.kirby.model.SpotifyStats;
import ch.kirby.presence.CommandUsageTracker;
import ch.kirby.service.StatsService;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.entity.User;
import discord4j.core.spec.EmbedCreateSpec;
import discord4j.core.spec.InteractionFollowupCreateSpec;
import discord4j.core.spec.MessageEditSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.Connection;
import java.util.List;

import static ch.kirby.util.SharedFormatter.*;

public class SpotifyCommand implements Command, ButtonHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpotifyCommand.class);

    @Override
    public String getName() {
        return "spotify";
    }

    @Override
    public Mono<Void> handle(ChatInputInteractionEvent event) {
        CommandUsageTracker.getInstance().incrementStats();
        return event.deferReply()
                .then(Mono.defer(() -> {
                    User commandUser = event.getInteraction().getUser();
                    User targetUser = event.getOption("user")
                            .flatMap(opt -> opt.getValue().map(v -> v.asUser().block()))
                            .orElse(null);

                    int dayspan = event.getOption("dayspan")
                            .flatMap(opt -> opt.getValue())
                            .map(v -> Integer.parseInt(v.getRaw()))
                            .orElse(7);

                    User user = (targetUser != null) ? targetUser : commandUser;
                    long userId = user.getId().asLong();
                    String displayName = user.getUsername();

                    return fetchSpotifyDataParallel(userId, displayName, dayspan)
                            .map(embed -> InteractionFollowupCreateSpec.builder()
                                    .addEmbed(embed)
                                    .addComponent(defaultStatsComponents("spotify", dayspan, userId))
                                    .build());
                }))
                .flatMap(event::createFollowup)
                // a rejected followup used to surface only as a WARN, leaving the interaction unanswered
                .onErrorResume(error -> {
                    LOGGER.error("Failed to deliver /spotify reply", error);
                    return event.createFollowup(InteractionFollowupCreateSpec.builder()
                            .addEmbed(errorEmbed(error))
                            .build());
                })
                .then();
    }

    @Override
    public String getCommandPrefix() {
        return "spotify";
    }

    @Override
    public Mono<Void> handleButton(ButtonInteractionEvent event) {
        String[] parts = event.getCustomId().split("_");
        int dayspan = Integer.parseInt(parts[2]);
        long userId = Long.parseLong(parts[4]);

        return Mono.justOrEmpty(event.getMessage()).flatMap(message -> {
            var embed = message.getEmbeds().get(0);
            var title = embed.getTitle().orElse("");
            var displayName = title.replace("🎧 Spotify Stats for ", "");

            var loadingSpec = MessageEditSpec.builder()
                    .embeds(List.of(loadingEmbed()))
                    .components(List.of(disabledStatsComponents("spotify", dayspan, userId)))
                    .build();

            return message.edit(loadingSpec)
                    .then(fetchSpotifyDataParallel(userId, displayName, dayspan))
                    .flatMap(newEmbed -> {
                        var resultSpec = MessageEditSpec.builder()
                                .embeds(List.of(newEmbed))
                                .components(List.of(defaultStatsComponents("spotify", dayspan, userId)))
                                .build();
                        return message.edit(resultSpec);
                    })
                    // without this the message would sit on the loading embed forever
                    .onErrorResume(error -> {
                        LOGGER.error("Failed to refresh /spotify message", error);
                        return message.edit(MessageEditSpec.builder()
                                .embeds(List.of(errorEmbed(error)))
                                .components(List.of(defaultStatsComponents("spotify", dayspan, userId)))
                                .build());
                    });
        }).then();
    }

    private Mono<EmbedCreateSpec> fetchSpotifyDataParallel(long userId, String displayName, int dayspan) {
        Mono<List<SpotifyStats>> songsMono = Mono.fromCallable(() -> {
            try (Connection conn = new DBConnection().SQLDBConnection()) {
                StatsService service = new StatsService(conn);
                return service.getTopSongsForUser(userId, displayName, dayspan);
            }
        }).subscribeOn(Schedulers.boundedElastic());

        Mono<List<SpotifyStats>> artistsMono = Mono.fromCallable(() -> {
            try (Connection conn = new DBConnection().SQLDBConnection()) {
                StatsService service = new StatsService(conn);
                return service.getTopArtistsForUser(userId, displayName, dayspan);
            }
        }).subscribeOn(Schedulers.boundedElastic());

        return Mono.zip(songsMono, artistsMono)
                .map(tuple -> formatSpotifyStats(displayName, tuple.getT1(), tuple.getT2(), dayspan));
    }
}
