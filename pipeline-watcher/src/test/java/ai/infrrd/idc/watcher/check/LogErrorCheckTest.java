package ai.infrrd.idc.watcher.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import ai.infrrd.idc.watcher.constants.ErrorCatalog;
import ai.infrrd.idc.watcher.constants.ErrorCatalog.Level;
import ai.infrrd.idc.watcher.env.GrafanaClient.LogLine;

/** Lines below are real usprod/rkt2prod formats from 2026-10-20/21. */
class LogErrorCheckTest {

    private static LogLine line(String svc, String text) {
        return new LogLine(Map.of("instance", svc), text);
    }

    @Test
    void messageStripsPythonPrefix() {
        var msg = LogErrorCheck.message("[2026-10-21 03:50:06,627] [ERROR] [Thread-3] [/var/data/app/error.py] [wrapper:52] [<N/A>]: "
                + "ModelEntities.__init__() missing 1 required positional argument: 'token_type'");
        assertEquals("ModelEntities.__init__() missing 1 required positional argument: 'token_type'", msg);
    }

    @Test
    void messageStripsJavaPrefix() {
        var msg = LogErrorCheck.message("2026-10-21 03:50:06.632 ERROR 7 --- docId:x [tContainer#7-16] "
                + "a.i.i.o.c.FieldExtractorOutputConsumer   : error Unable to complete NER module extraction for documentId: abc");
        assertEquals("error Unable to complete NER module extraction for documentId: abc", msg);
    }

    @Test
    void sameErrorDifferentIdsHaveSameFingerprint() {
        var a = line("orchestrator", "2026-10-21 03:50:06.632 ERROR 7 --- [t] a.b.C : error Document groupId is null for documentId: "
                + "f7e8d23c-e3c8-48b5-b4a8-a90914a132ce_document-1_split-1 after 120 ms");
        var b = line("orchestrator", "2026-10-21 04:11:00.001 ERROR 7 --- [t] a.b.C : error Document groupId is null for documentId: "
                + "0d75bd9c-3afb-4dc2-b78a-14957a8e1dba_document-2 after 7 ms");
        assertEquals(LogErrorCheck.fingerprint(a), LogErrorCheck.fingerprint(b));
    }

    @Test
    void sameMessageDifferentServiceIsDifferentFingerprint() {
        var a = line("orchestrator", "x ERROR 7 --- [t] a.b.C : error boom");
        var b = line("scheduler", "x ERROR 7 --- [t] a.b.C : error boom");
        assertNotEquals(LogErrorCheck.fingerprint(a), LogErrorCheck.fingerprint(b));
    }

    @Test
    void catalogIgnoreEntriesArePlainLiteralsForLogQl() {
        // IGNORE entries are sent to Loki as `!= "<literal>"`; they must not need regex.
        ErrorCatalog.ALL.stream().filter(k -> k.level() == Level.IGNORE).forEach(k -> {
            var p = k.match().pattern();
            assertTrue(p.startsWith("\\Q") && p.endsWith("\\E"), k.id() + " must be built with ignore(...)");
        });
    }

    private static final LogErrorCheck CHECK = new LogErrorCheck(new ai.infrrd.idc.watcher.config.WatcherProperties(
            java.util.List.of(), 168, java.util.List.of(), java.util.List.of(), 30, Map.of(), 0.7, 20,
            java.util.List.of(), java.util.List.of("orch"),
            new ai.infrrd.idc.watcher.config.WatcherProperties.Thresholds(5, 5, 3, 0.2, 20, 10, 50, 3, 0.9, 10)));

    @Test
    void modelTrainingIdIsNotADocId() {
        // euprod 2026-10-26: the scheduler logs a userModel training id, which must not show up as a document.
        var l = line("scheduler", "2026-10-26 04:17:54.785 ERROR 7 --- [scheduling-10] a.i.i.a.s.PageClassificationTrainer : "
                + "Exception occurred while checking pagination progress of userModelId: 418174be-9d73-4779-acc0-af5f60ad7aae , reason: x");
        assertEquals(java.util.List.of(), CHECK.docIds(java.util.List.of(l)));
    }

    @Test
    void realDocIdsAreFound() {
        var py = line("ml-predictor", "[2026-10-21 03:50:08,019] [ERROR] [Thread-3] [rcs_score.py] [execute:30] "
                + "[f7e8d23c-e3c8-48b5-b4a8-a90914a132ce_document-1_split-1]: something failed");
        var javaLine = line("orchestrator", "2026-10-21 03:50:06.633 ERROR 7 --- docId:0d75bd9c-3afb-4dc2-b78a-14957a8e1dba [t] a.b.C : error boom");
        var named = line("pika-checkbox-extraction", "[x] [ERROR] [T] [f] [g:1] [<N/A>]: Error processing queue message for document "
                + "bd323d15-0de9-4cb2-9b01-8cea471ade5c");
        assertEquals(java.util.List.of("f7e8d23c-e3c8-48b5-b4a8-a90914a132ce_document-1_split-1",
                "0d75bd9c-3afb-4dc2-b78a-14957a8e1dba", "bd323d15-0de9-4cb2-9b01-8cea471ade5c"),
                CHECK.docIds(java.util.List.of(py, javaLine, named)));
    }

    @Test
    void searchSnippetIsTheTextBeforeTheFirstVariablePart() {
        assertEquals("Unable to complete NER module extraction for documentId:",
                LogErrorCheck.searchSnippet("Unable to complete NER module extraction for documentId: 3f2a…"));
        assertEquals(null, LogErrorCheck.searchSnippet("500 Internal Server Error"));
    }

    @Test
    void catalogIdsAreUnique() {
        assertEquals(ErrorCatalog.ALL.size(),
                ErrorCatalog.ALL.stream().map(ErrorCatalog.KnownError::id).distinct().count());
    }
}
