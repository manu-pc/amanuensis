package com.local.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MessageMapBuilderTest {

    private static MessageMapBuilder.FileData file(String rel, String... keyValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        return new MessageMapBuilder.FileData(rel, values);
    }

    private static String key(String block, int line) {
        return block + "_slash_Step_0_gml_" + line + "_0";
    }

    // ---------------------------------------------------------------
    // descomposición de claves
    // ---------------------------------------------------------------

    @Test
    void parseSplitsTheRealKeyShape() {
        MessageKey k = MessageKey.parse("obj_npc_room_slash_Other_10_gml_240_0");
        assertNotNull(k);
        assertEquals("obj_npc_room", k.entity());
        assertEquals("Other_10", k.event());
        assertEquals(240, k.line());
        assertEquals(0, k.index());
        assertFalse(k.bSuffix());
        assertEquals("obj_npc_room Other_10", k.block());
    }

    @Test
    void parseKeepsTheModAddedBSuffixApart() {
        MessageKey plain = MessageKey.parse("scr_text_slash_scr_text_gml_7513_0");
        MessageKey b = MessageKey.parse("scr_text_slash_scr_text_gml_7513_0_b");
        assertNotNull(b);
        assertTrue(b.bSuffix());
        assertEquals(plain.block(), b.block());
        // a de sufixo vai despois na orde do código
        assertTrue(plain.compareTo(b) < 0);
    }

    @Test
    void parseReturnsNullForTheOpaqueKeys() {
        assertNull(MessageKey.parse("obj_lang_settings_3_0"));
        assertNull(MessageKey.parse("date"));
        assertNull(MessageKey.parse("// From prev chapters"));
        assertNull(MessageKey.parse(null));
    }

    // ---------------------------------------------------------------
    // aliñamento
    // ---------------------------------------------------------------

    @Test
    void lcsPairsAlignsAroundAnInsertion() {
        List<int[]> pairs = MessageMapBuilder.lcsPairs(
                List.of("a", "b", "c"),
                List.of("a", "x", "b", "c"));
        assertEquals(List.of("0-0", "1-2", "2-3"), asText(pairs));
    }

    @Test
    void lcsPairsAlignsAroundADeletion() {
        List<int[]> pairs = MessageMapBuilder.lcsPairs(
                List.of("a", "b", "c", "d"),
                List.of("a", "c", "d"));
        assertEquals(List.of("0-0", "2-1", "3-2"), asText(pairs));
    }

    @Test
    void lcsPairsNeverCrossesOrder() {
        List<int[]> pairs = MessageMapBuilder.lcsPairs(
                List.of("a", "b"),
                List.of("b", "a"));
        assertEquals(1, pairs.size()); // unha das dúas, non as dúas cruzadas
    }

    private static List<String> asText(List<int[]> pairs) {
        return pairs.stream().map(p -> p[0] + "-" + p[1]).toList();
    }

    // ---------------------------------------------------------------
    // construción do mapa
    // ---------------------------------------------------------------

    @Test
    void lineDriftDoesNotBreakTheMatch() {
        // o mesmo bloque recompilado: en ch2 inseriuse unha liña antes, así que
        // todos os números de liña se moven e as claves deixan de coincidir
        var ch1 = file("lang/chapter1/strings.json",
                key("obj_a", 10), "hello",
                key("obj_a", 20), "bye");
        var ch2 = file("lang/chapter2/strings.json",
                key("obj_a", 12), "new line",
                key("obj_a", 14), "hello",
                key("obj_a", 24), "bye");

        MessageMap map = MessageMapBuilder.build(List.of(ch1, ch2));

        MessageMap.Group g = map.find("lang/chapter1/strings.json", key("obj_a", 10));
        assertNotNull(g, "a mensaxe debe atoparse aínda que a clave cambiase");
        assertEquals("hello", g.base());
        assertEquals(List.of("lang/chapter2/strings.json"),
                g.others("lang/chapter1/strings.json", key("obj_a", 10)).stream()
                        .map(MessageMap.Member::relPath).toList());
        assertEquals(key("obj_a", 14),
                g.others("lang/chapter1/strings.json", key("obj_a", 10)).get(0).key());

        assertNull(map.find("lang/chapter2/strings.json", key("obj_a", 12)),
                "unha liña nova non se repite en ningures");
    }

    @Test
    void sameKeyWithDifferentTextIsNotTheSameMessage() {
        // caso real: obj_savepoint_…_gml_89_0 é outra mensaxe en ch3
        var ch2 = file("lang/chapter2/strings.json", key("obj_savepoint", 89), "power of the cat sign");
        var ch3 = file("lang/chapter3/strings.json", key("obj_savepoint", 89), "power of undeserved fame");

        MessageMap map = MessageMapBuilder.build(List.of(ch2, ch3));
        assertTrue(map.isEmpty(), "mesma clave pero outro texto: non se pode agrupar");
    }

    @Test
    void sameTextInDifferentCodeBlocksIsNotTheSameMessage() {
        // "Check" aparece en 68 sitios que non teñen nada que ver
        var ch1 = file("lang/chapter1/strings.json",
                key("obj_shop", 5), "Check",
                key("scr_text", 5), "Check");
        var ch2 = file("lang/chapter2/strings.json",
                key("obj_shop", 9), "Check",
                key("scr_text", 9), "Check");

        MessageMap map = MessageMapBuilder.build(List.of(ch1, ch2));

        MessageMap.Group shop = map.find("lang/chapter1/strings.json", key("obj_shop", 5));
        assertNotNull(shop);
        assertEquals(2, shop.members().size());
        assertEquals(key("obj_shop", 9),
                shop.others("lang/chapter1/strings.json", key("obj_shop", 5)).get(0).key());
    }

    @Test
    void opaqueKeysMatchByExactKeyAndText() {
        var ch1 = file("lang/chapter1/strings.json", "obj_lang_settings_3_0", "English");
        var ch2 = file("lang/chapter2/strings.json", "obj_lang_settings_3_0", "English");
        var ch3 = file("lang/chapter3/strings.json", "obj_lang_settings_3_0", "Galego");

        MessageMap map = MessageMapBuilder.build(List.of(ch1, ch2, ch3));
        MessageMap.Group g = map.find("lang/chapter1/strings.json", "obj_lang_settings_3_0");
        assertNotNull(g);
        assertEquals(2, g.members().size(), "só as dúas co mesmo texto");
    }

    @Test
    void aMessageDuplicatedInEveryChapterStaysInSeparateGroups() {
        // as dúas copias van en paralelo en todos os capítulos: o aliñamento
        // empareja a primeira coa primeira e a segunda coa segunda
        var ch1 = file("lang/chapter1/strings.json",
                key("obj_a", 10), "dup",
                key("obj_a", 11), "dup");
        var ch2 = file("lang/chapter2/strings.json",
                key("obj_a", 10), "dup",
                key("obj_a", 11), "dup");

        MessageMap map = MessageMapBuilder.build(List.of(ch1, ch2));
        assertEquals(2, map.groups().size());
        assertTrue(map.groups().stream().noneMatch(MessageMap.Group::ambiguous));
    }

    @Test
    void aMessageThatLosesACopyBetweenChaptersIsMarkedAmbiguous() {
        // ch1 ten dúas copias, ch2 quedou só coa primeira e ch3 só coa segunda:
        // as parellas cruzan e todo acaba nun grupo. O texto segue sendo o mesmo,
        // pero a correspondencia clave a clave xa non é única
        var ch1 = file("lang/chapter1/strings.json",
                key("obj_a", 10), "dup",
                key("obj_a", 20), "dup");
        var ch2 = file("lang/chapter2/strings.json", key("obj_a", 10), "dup");
        var ch3 = file("lang/chapter3/strings.json", key("obj_a", 20), "dup");

        MessageMap map = MessageMapBuilder.build(List.of(ch1, ch2, ch3));
        assertEquals(1, map.groups().size());
        MessageMap.Group g = map.groups().get(0);
        assertTrue(g.ambiguous());
        assertEquals(4, g.members().size());
    }

    @Test
    void groupsNeverHoldTwoDifferentTexts() {
        var ch1 = file("lang/chapter1/strings.json",
                key("obj_a", 1), "x", key("obj_a", 2), "y", key("obj_a", 3), "z");
        var ch2 = file("lang/chapter2/strings.json",
                key("obj_a", 1), "x", key("obj_a", 2), "OTHER", key("obj_a", 3), "z");
        var ch3 = file("lang/chapter3/strings.json",
                key("obj_a", 1), "x", key("obj_a", 2), "y", key("obj_a", 3), "z");

        int[] conflicts = new int[1];
        MessageMap map = MessageMapBuilder.build(List.of(ch1, ch2, ch3), conflicts);
        assertEquals(0, conflicts[0]);
        for (MessageMap.Group g : map.groups()) {
            assertEquals(1, g.members().stream()
                    .map(m -> g.base())
                    .distinct().count());
        }
        // "y" de ch2 non existe: o grupo de "y" só ten ch1 e ch3
        MessageMap.Group y = map.find("lang/chapter1/strings.json", key("obj_a", 2));
        assertNotNull(y);
        assertEquals(List.of("lang/chapter1/strings.json", "lang/chapter3/strings.json"),
                y.members().stream().map(MessageMap.Member::relPath).toList());
    }

    @Test
    void beforeListsOnlyEarlierChapters() {
        var ch1 = file("lang/chapter1/strings.json", key("obj_a", 1), "x");
        var ch2 = file("lang/chapter2/strings.json", key("obj_a", 1), "x");
        var ch3 = file("lang/chapter3/strings.json", key("obj_a", 1), "x");

        MessageMap map = MessageMapBuilder.build(List.of(ch1, ch2, ch3));
        MessageMap.Group g = map.find("lang/chapter3/strings.json", key("obj_a", 1));
        assertNotNull(g);
        assertEquals(2, g.before("lang/chapter3/strings.json").size());
        assertEquals(0, g.before("lang/chapter1/strings.json").size());
    }

    // ---------------------------------------------------------------
    // serialización
    // ---------------------------------------------------------------

    @Test
    void jsonRoundtripKeepsEverything() {
        var ch1 = file("lang/chapter1/strings.json", key("obj_a", 1), "x", key("obj_a", 2), "d");
        var ch2 = file("lang/chapter2/strings.json", key("obj_a", 1), "x", key("obj_a", 3), "d");

        MessageMap original = MessageMapBuilder.build(List.of(ch1, ch2));
        MessageMap reread = MessageMap.parse(original.toJson("agora"));

        assertEquals(original.files(), reread.files());
        assertEquals(original.groups().size(), reread.groups().size());
        for (MessageMap.Group g : original.groups()) {
            MessageMap.Member first = g.members().get(0);
            MessageMap.Group other = reread.find(first.relPath(), first.key());
            assertNotNull(other);
            assertEquals(g.base(), other.base());
            assertEquals(g.members(), other.members());
        }
    }

    @Test
    void aMapFromTheFutureIsIgnoredInsteadOfBreakingTheApp() {
        var json = MessageMapBuilder.build(List.of(
                file("lang/chapter1/strings.json", key("obj_a", 1), "x"),
                file("lang/chapter2/strings.json", key("obj_a", 1), "x")))
                .toJson(null);
        json.addProperty("version", MessageMap.VERSION + 1);
        assertTrue(MessageMap.parse(json).isEmpty());
    }

    @Test
    void chapterOrderPutsTheMenuFirst() {
        assertEquals(0, MessageMapBuilder.chapterOf(java.nio.file.Path.of("lang/strings.json")));
        assertEquals(2, MessageMapBuilder.chapterOf(java.nio.file.Path.of("lang/chapter2/strings.json")));
    }
}
