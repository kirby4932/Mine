package ch.kirby.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedFormatterTest {

    private static final int DISCORD_FIELD_LIMIT = 1024;

    @Test
    void breakdownOfMichi69Over365DaysFitsDiscordFieldLimit() {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("League of Legends", 1763.64);
        breakdown.put("CurseForge", 239.11);
        breakdown.put("ARK Survival Ascended", 125.90);
        breakdown.put("ARK: Survival Ascended", 104.34);
        breakdown.put("The Binding of Isaac: Rebirth", 103.77);
        breakdown.put("Bloons TD 6", 100.90);
        breakdown.put("Minecraft", 27.21);
        breakdown.put("HELLDIVERS\u2122 2", 25.16);
        breakdown.put("Baldur's Gate 3", 18.51);
        breakdown.put("No Man's Sky", 16.84);
        breakdown.put("GTFO", 10.99);
        breakdown.put("Monster Hunter Wilds", 5.18);
        breakdown.put("Grounded 2", 5.05);
        breakdown.put("PEAK", 4.86);
        breakdown.put("Sand: Raiders Of Sophie", 3.63);
        breakdown.put("Dead by Daylight", 1.78);
        breakdown.put("The Forest", 1.68);
        breakdown.put("Space Engineers", 1.27);
        breakdown.put("Gamble With Your Friends", 1.27);
        breakdown.put("Deathground", 1.12);
        breakdown.put("Cuphead", 0.92);
        breakdown.put("Cyberpunk 2077", 0.07);
        breakdown.put("Alien: Isolation", 0.06);
        breakdown.put("Where Winds Meet", 0.06);
        breakdown.put("Vampire Survivors", 0.05);
        breakdown.put("The Last Meadow", 0.03);
        breakdown.put("Wordle", 0.03);
        breakdown.put("Roblox", 0.01);
        breakdown.put("Nightfall", 0.01);
        breakdown.put("Celeste", 0.00);
        breakdown.put("The Walking Dead", 0.00);

        String value = SharedFormatter.formatBreakdown(breakdown);

        assertTrue(value.length() <= DISCORD_FIELD_LIMIT, "was " + value.length());
        assertTrue(value.startsWith("\u2022 League of Legends: `1,763.64 h`"));
    }

    @Test
    void breakdownTruncatesAndReportsHiddenCount() {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            breakdown.put("Game with a fairly long title " + i, 100.0 - i);
        }

        String value = SharedFormatter.formatBreakdown(breakdown);

        assertTrue(value.length() <= DISCORD_FIELD_LIMIT, "was " + value.length());
        assertTrue(value.contains("\u2026 and "));
        assertTrue(value.endsWith(" more"));
    }

    @Test
    void shortBreakdownIsNotTruncated() {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("Factorio", 12.5);
        breakdown.put("Celeste", 3.25);

        String value = SharedFormatter.formatBreakdown(breakdown);

        assertFalse(value.contains("\u2026 and "));
        assertTrue(value.contains("\u2022 Factorio: `12.50 h`"));
        assertTrue(value.contains("\u2022 Celeste: `3.25 h`"));
    }

    @Test
    void emptyBreakdownHasPlaceholder() {
        assertTrue(SharedFormatter.formatBreakdown(new LinkedHashMap<>()).contains("No data found"));
    }
}
