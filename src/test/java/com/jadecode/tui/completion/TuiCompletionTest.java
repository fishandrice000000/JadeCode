package com.jadecode.tui.completion;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.cli.tui.CommandCatalog;
import com.jadecode.tui.input.InputEvent;

/** 路径 fuzzy/命令候选/请求版本校验测试。 */
class TuiCompletionTest {
    @Test void pathCompletionFindsNestedFilesAndDirectories(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main"));
        Files.writeString(dir.resolve("src/main/App.java"), "class App {}");
        Files.writeString(dir.resolve("src/README.md"), "readme");
        var provider = new PathCompletionProvider(dir);
        var request = new CompletionRequest.Request(1, 1, 1, "src/", 4, 0, 4, CompletionRequest.Kind.PATH);
        var result = provider.complete(request).get();
        assertTrue(result.error().isEmpty());
        assertTrue(result.candidates().stream().anyMatch(c -> c.label().endsWith("src/main/")), result.candidates().toString());
        assertTrue(result.candidates().stream().anyMatch(c -> c.label().equals("src/README.md")));
        var fuzzy = new CompletionRequest.Request(2, 1, 1, "src/ap", 6, 0, 6, CompletionRequest.Kind.PATH);
        assertEquals("src/main/App.java", provider.complete(fuzzy).get().candidates().getFirst().label());
        provider.close();
    }

    @Test void commandCompletionUsesCatalogAndKeepsExecutableFlag() {
        var catalog = new CommandCatalog(Path.of("."));
        var request = new CompletionRequest.Request(1, 2, 3, "/se", 3, 0, 3, CompletionRequest.Kind.COMMAND);
        var result = catalog.completionProvider().complete(request).join();
        var settings = result.candidates().stream().filter(c -> c.id().equals("/settings")).findFirst().orElseThrow();
        assertTrue(settings.executableOnEnter());
        assertEquals("/settings ", settings.label());
        catalog.close();
    }

    @Test void staleResultsDoNotMatchChangedTextOrVersion() {
        var request = new CompletionRequest.Request(5, 2, 9, "/set", 4, 0, 4, CompletionRequest.Kind.COMMAND);
        var result = CompletionRequest.Result.of(request, List.of());
        assertTrue(result.matches(5, 2, 9, "/set"));
        assertFalse(result.matches(6, 2, 9, "/set"));
        assertFalse(result.matches(5, 2, 10, "/set"));
        assertFalse(result.matches(5, 2, 9, "/sett"));
    }

    @Test void fuzzyScoreRanksPrefixAndSubsequence() {
        assertTrue(PathCompletionProvider.fuzzyScore("app", "src/App.java") > 0);
        assertEquals(-1, PathCompletionProvider.fuzzyScore("zzz", "src/App.java"));
        assertTrue(PathCompletionProvider.fuzzyScore("src", "src/App.java")
                > PathCompletionProvider.fuzzyScore("app", "src/App.java"));
    }
}
