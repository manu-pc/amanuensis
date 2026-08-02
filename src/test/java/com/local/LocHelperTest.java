package com.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocHelperTest {

    private LocHelper helper(Path dir, String json) throws IOException {
        Path file = dir.resolve("strings.json");
        Files.writeString(file, json);
        return new LocHelper(file.toString());
    }

    @Test
    void keepsOnlyStringValuesInFileOrder(@TempDir Path dir) throws IOException {
        LocHelper h = helper(dir, """
                {
                  "a": "primeira",
                  "meta": [1, 2, 3],
                  "obj": {"x": 1},
                  "num": 7,
                  "b": "segunda"
                }
                """);

        assertEquals(2, h.getLineCount());
        assertEquals("a", h.getKey(0));
        assertEquals("primeira", h.getOriginal(0));
        assertEquals("b", h.getKey(1));
        assertEquals("segunda", h.getOriginal(1));
    }

    @Test
    void delegatesStripAndReapply(@TempDir Path dir) throws IOException {
        LocHelper h = helper(dir, """
                {"l": "\\\\E0* Sure..^1. okay^1, we can try again./%"}
                """);

        assertEquals("Sure... okay, we can try again.", h.stripFormatting(0));
        assertEquals(h.getOriginal(0), h.reapplyFormatting(0, h.stripFormatting(0)));
    }

    @Test
    void markerSummaryReportsWhatTheBadgesNeed(@TempDir Path dir) throws IOException {
        LocHelper h = helper(dir, """
                {"l": "\\\\cYcor\\\\cW e ~1 efecto&segunda liña^3/"}
                """);

        var s = h.markerSummary(0);
        assertEquals(1, s.newlineCount());
        assertEquals(1, h.countNewlines(0));
        assertTrue(s.hasColor());
        assertTrue(s.hasTilde());
        assertTrue(s.hasPause());
        assertEquals(2, s.colors().size());
    }

    @Test
    void tokensAreCachedAndInvalidatedOnUpdate(@TempDir Path dir) throws IOException {
        LocHelper h = helper(dir, """
                {"l": "* Ola/"}
                """);

        assertSame(h.tokens(0), h.tokens(0), "os tokens deben vir da caché");

        h.updateOriginal(0, "* Adeus/%");
        assertEquals("* Adeus/%", h.getOriginal(0));
        assertEquals("Adeus", h.stripFormatting(0));
    }
}
