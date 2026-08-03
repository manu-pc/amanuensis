package com.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.local.JsonIo;

class KeyMergeTest {

    private static JsonObject json(String... keyValues) {
        JsonObject obj = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            obj.addProperty(keyValues[i], keyValues[i + 1]);
        }
        return obj;
    }

    private static Map<String, KeyMerge.KeyEdit> edits(String key, String base, String value) {
        Map<String, KeyMerge.KeyEdit> m = new LinkedHashMap<>();
        m.put(key, new KeyMerge.KeyEdit(base, value));
        return m;
    }

    /**
     * A regresión que importa: o bug real que fixo que 575 liñas volvesen ao inglés.
     * A árbore de traballo ten unha clave desactualizada (en inglés), o servidor
     * tena traducida, e o usuario non a tocou: ten que quedar a do servidor.
     */
    @Test
    void staleWorkingTreeDoesNotRevertRemoteTranslation() {
        String english = "\\EB* I don't see you changing your sign./";
        String galician = "\\EB* Non te vexo cambiando o sinal./";

        JsonObject head = json("stale", galician, "mine", "* Vello meu/");
        JsonObject theirs = json("stale", galician, "mine", "* Vello meu/");
        // o usuario só editou "mine"; "stale" segue en inglés no seu disco
        Map<String, KeyMerge.KeyEdit> edits = edits("mine", "* Vello meu/", "* Novo meu/");

        JsonObject toCommit = KeyMerge.buildCommitContent(head, edits);
        assertEquals(galician, JsonIo.stringOrNull(toCommit, "stale"),
                "unha clave non editada non pode moverse, nin sequera se o disco está desactualizado");
        assertEquals("* Novo meu/", JsonIo.stringOrNull(toCommit, "mine"));
        assertFalse(toCommit.toString().contains(english));

        Map<String, String> conflicts = new LinkedHashMap<>();
        JsonObject merged = KeyMerge.reconcile(theirs, edits, conflicts);
        assertTrue(conflicts.isEmpty());
        assertEquals(galician, JsonIo.stringOrNull(merged, "stale"));
        assertEquals("* Novo meu/", JsonIo.stringOrNull(merged, "mine"));
    }

    @Test
    void unledgeredKeysAreNeverTouched() {
        JsonObject theirs = json("a", "servidor-a", "b", "servidor-b", "c", "servidor-c");
        JsonObject merged = KeyMerge.reconcile(theirs, edits("b", "servidor-b", "meu-b"),
                new LinkedHashMap<>());

        assertEquals("servidor-a", JsonIo.stringOrNull(merged, "a"));
        assertEquals("meu-b", JsonIo.stringOrNull(merged, "b"));
        assertEquals("servidor-c", JsonIo.stringOrNull(merged, "c"));
    }

    @Test
    void oursWinsWhenTheRemoteIsStillAtOurBase() {
        Map<String, String> conflicts = new LinkedHashMap<>();
        JsonObject merged = KeyMerge.reconcile(json("k", "base"), edits("k", "base", "meu"), conflicts);

        assertEquals("meu", JsonIo.stringOrNull(merged, "k"));
        assertTrue(conflicts.isEmpty());
    }

    @Test
    void identicalValueOnBothSidesIsNotAConflict() {
        Map<String, String> conflicts = new LinkedHashMap<>();
        JsonObject merged = KeyMerge.reconcile(json("k", "mesmo"), edits("k", "base", "mesmo"), conflicts);

        assertEquals("mesmo", JsonIo.stringOrNull(merged, "k"));
        assertTrue(conflicts.isEmpty(), "escribir o mesmo texto ca outra persoa non é un conflito");
    }

    @Test
    void divergentValuesFromTheSameBaseIsAConflict() {
        Map<String, String> conflicts = new LinkedHashMap<>();
        JsonObject merged = KeyMerge.reconcile(json("k", "dela"), edits("k", "base", "meu"), conflicts);

        assertEquals(Map.of("k", "meu"), conflicts);
        assertEquals("dela", JsonIo.stringOrNull(merged, "k"),
                "mentres non se resolva, mantense o do servidor");
    }

    @Test
    void keysAddedRemotelyArePreserved() {
        JsonObject theirs = json("k", "base", "nova-do-servidor", "texto");
        JsonObject merged = KeyMerge.reconcile(theirs, edits("k", "base", "meu"), new LinkedHashMap<>());

        assertEquals("texto", JsonIo.stringOrNull(merged, "nova-do-servidor"));
    }

    /**
     * Unha clave que o servidor eliminou non se recrea nunca. O que cambiou é como
     * se informa: antes contaba como conflito, e o usuario recibía unha rama e unha
     * proposta de fusión dicindo que «outra persoa editou esa liña á vez». Non a
     * editou ninguén: deixou de existir ao recompilar o xogo (a reestruturación do
     * capítulo 5 borrou 77 claves dos capítulos 1-4). E esa proposta non se podía
     * fusionar, porque reviviría unha clave eliminada a mantenta.
     */
    @Test
    void editOfAKeyRemovedUpstreamIsReportedAsObsoleteNotAsAConflict() {
        Map<String, String> conflicts = new LinkedHashMap<>();
        Set<String> dropped = new LinkedHashSet<>();
        JsonObject merged = KeyMerge.reconcile(json("outra", "x"), edits("k", "base", "meu"),
                conflicts, dropped);

        assertTrue(conflicts.isEmpty(), "non hai ninguén co que entrar en conflito");
        assertEquals(Set.of("k"), dropped);
        assertFalse(merged.has("k"), "e segue sen recrearse a clave eliminada");
    }

    @Test
    void aRealConflictIsStillAConflictWhenThereAreAlsoObsoleteKeys() {
        Map<String, String> conflicts = new LinkedHashMap<>();
        Set<String> dropped = new LinkedHashSet<>();
        Map<String, KeyMerge.KeyEdit> both = new LinkedHashMap<>();
        both.put("viva", new KeyMerge.KeyEdit("base", "meu"));
        both.put("morta", new KeyMerge.KeyEdit("base", "meu"));

        KeyMerge.reconcile(json("viva", "dela"), both, conflicts, dropped);

        assertEquals(Map.of("viva", "meu"), conflicts, "a clave que segue viva si é conflito");
        assertEquals(Set.of("morta"), dropped);
    }

    @Test
    void buildCommitContentIgnoresKeysMissingFromHead() {
        JsonObject head = json("a", "x");
        JsonObject out = KeyMerge.buildCommitContent(head, edits("desaparecida", "b", "c"));

        assertEquals(1, out.size());
        assertEquals("x", JsonIo.stringOrNull(out, "a"));
    }

    @Test
    void buildCommitContentKeepsKeyOrderOfHead() {
        JsonObject head = json("z", "1", "a", "2", "m", "3");
        JsonObject out = KeyMerge.buildCommitContent(head, edits("a", "2", "editado"));

        assertEquals(java.util.List.of("z", "a", "m"), new java.util.ArrayList<>(out.keySet()));
    }
}
