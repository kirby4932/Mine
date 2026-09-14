package ch.kirby.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application.name is a TEXT column, so no schema limit bounds a game name.
 * These cases assert the field value can never exceed what Discord accepts.
 */
class BreakdownLimitTest {

    private static final int DISCORD_FIELD_LIMIT = 1024;

    private static Map<String, Double> games(int count, int nameLength) {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            breakdown.put(("game" + i + "-").repeat(nameLength).substring(0, nameLength), (double) (count - i));
        }
        return breakdown;
    }

    @Test
    void neverExceedsLimitAcrossGameCounts() {
        for (int count = 1; count <= 400; count++) {
            String value = SharedFormatter.formatBreakdown(games(count, 24));
            assertTrue(value.length() <= DISCORD_FIELD_LIMIT,
                    count + " games produced " + value.length() + " chars");
        }
    }

    @Test
    void neverExceedsLimitAcrossNameLengths() {
        for (int nameLength = 1; nameLength <= 600; nameLength += 7) {
            String value = SharedFormatter.formatBreakdown(games(12, nameLength));
            assertTrue(value.length() <= DISCORD_FIELD_LIMIT,
                    "name length " + nameLength + " produced " + value.length() + " chars");
        }
    }

    @Test
    void singleGameLongerThanTheLimitIsClampedNotDropped() {
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("x".repeat(4000), 12.5);

        String value = SharedFormatter.formatBreakdown(breakdown);

        assertTrue(value.length() <= DISCORD_FIELD_LIMIT);
        assertTrue(value.startsWith("• xxx"), "the game should still be visible");
        assertTrue(value.endsWith("…"));
    }

    @Test
    void hiddenCountMatchesWhatWasDropped() {
        String value = SharedFormatter.formatBreakdown(games(400, 24));

        int rendered = value.split("•", -1).length - 1;
        int hidden = Integer.parseInt(value.replaceAll("(?s).*… and (\\d+) more$", "$1"));

        assertTrue(value.length() <= DISCORD_FIELD_LIMIT);
        assertFalse(value.isBlank());
        assertTrue(rendered + hidden == 400,
                "rendered " + rendered + " + hidden " + hidden + " should equal 400");
    }
}
