package ch.kirby.commands;

import ch.kirby.SQL.DBConnection;
import ch.kirby.core.command.ButtonHandler;
import ch.kirby.model.GameStats;
import ch.kirby.presence.CommandUsageTracker;
import ch.kirby.service.StatsService;
import ch.kirby.core.command.Command;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.entity.User;
import discord4j.core.spec.InteractionFollowupCreateSpec;
import discord4j.core.spec.MessageEditSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.sql.Connection;

import java.util.List;

import static ch.kirby.util.SharedFormatter.*;


public class StatsCommand implements Command, ButtonHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatsCommand.class);

    @Override
    public String getName() {
        return "stats";
    }

    @Override
    public Mono<Void> handle(ChatInputInteractionEvent event) {
        CommandUsageTracker.getInstance().incrementStats();
        return event.deferReply()
                .then(Mono.fromCallable(() -> {
                    try (Connection conn = new DBConnection().SQLDBConnection()) {
                        User commandUser = event.getInteraction().getUser();
                        User targetUser = event.getOption("user")
                                .flatMap(opt -> opt.getValue().map(v -> v.asUser().block()))
                                .orElse(null);

                        User user = (targetUser != null) ? targetUser : commandUser;
                        long userId = user.getId().asLong();

                        int dayspan = event.getOption("dayspan")
                                .flatMap(opt -> opt.getValue())
                                .map(v -> Integer.parseInt(v.getRaw()))
                                .orElse(7);

                        StatsService service = new StatsService(conn);
                        GameStats stats = service.getStats(commandUser, targetUser, dayspan);
                        String commandPrefix = "stats";

                        return InteractionFollowupCreateSpec.builder()
                                .addEmbed(defaultStatsEmbed(stats, dayspan).get(0))
                                .addComponent(defaultStatsComponents(commandPrefix, dayspan, userId))
                                .build();

                    } catch (Exception e) {
                        return InteractionFollowupCreateSpec.builder()
                                .content("⚠️ Error: " + e.getMessage())
                                .build();
                    }
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(event::createFollowup)
                // a rejected followup used to surface only as a WARN, leaving the interaction unanswered
                .onErrorResume(error -> {
                    LOGGER.error("Failed to deliver /stats reply", error);
                    return event.createFollowup(InteractionFollowupCreateSpec.builder()
                            .addEmbed(errorEmbed(error))
                            .build());
                })
                .then();
    }

    @Override
    public String getCommandPrefix() {
        return "stats";
    }

    @Override
    public Mono<Void> handleButton(ButtonInteractionEvent event) {
        String[] parts = event.getCustomId().split("_");
        int dayspan = Integer.parseInt(parts[2]);
        long userId = Long.parseLong(parts[4]);

        return Mono.justOrEmpty(event.getMessage()).flatMap(message -> {
            var embed = message.getEmbeds().get(0);
            var title = embed.getTitle().orElse("");
            var displayName = title.replace("Stats for ", "").trim();

            var loadingSpec = MessageEditSpec.builder()
                    .embeds(List.of(loadingEmbed()))
                    .components(List.of(disabledStatsComponents("stats", dayspan, userId)))
                    .build();

            return message.edit(loadingSpec)
                    .then(Mono.fromCallable(() -> {
                        try (Connection conn = new DBConnection().SQLDBConnection()) {
                            StatsService service = new StatsService(conn);
                            return service.getStats(userId, displayName, dayspan);
                        }
                    }).subscribeOn(Schedulers.boundedElastic()))
                    .flatMap(stats -> {
                        var resultSpec = MessageEditSpec.builder()
                                .embeds(defaultStatsEmbed(stats, dayspan))
                                .components(List.of(defaultStatsComponents("stats", dayspan, userId)))
                                .build();
                        return message.edit(resultSpec);
                    })
                    // without this the message would sit on the loading embed forever
                    .onErrorResume(error -> {
                        LOGGER.error("Failed to refresh /stats message", error);
                        return message.edit(MessageEditSpec.builder()
                                .embeds(List.of(errorEmbed(error)))
                                .components(List.of(defaultStatsComponents("stats", dayspan, userId)))
                                .build());
                    });
        }).then();
    }
}
