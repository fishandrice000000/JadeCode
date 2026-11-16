package com.jadecode.cli.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.bootstrap.JadeCodeFactory;
import com.jadecode.config.AppConfig;
import com.jadecode.config.JadeSettings;

/** 工厂实际装配的资源清单通过 display/wire 公开名字，不将项目/技能原文重复传到终端。 */
class BackendResourcesTest {
    @Test
    void exposesOnlyNamesFromTheActuallyLoadedCatalog(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "PRIVATE_INSTRUCTION_BODY");
        Path skill = Files.createDirectories(workspace.resolve("skills/local"));
        Files.writeString(skill.resolve("SKILL.md"),
                "---\nname: local\ndescription: local fixture\n---\nPRIVATE_SKILL_BODY");
        var config = new AppConfig("dummy", "http://127.0.0.1:9", "fixture", 128,
                com.jadecode.config.ModelProvider.ANTHROPIC_COMPATIBLE);
        try (var runtime = com.jadecode.bootstrap.JadeCodeFactory.create(
                new com.jadecode.bootstrap.JadeCodeFactory.Dependencies(config, workspace, (request, ignoredStream) -> {
                    throw new AssertionError("Resource display must not call a model");
                }, com.jadecode.config.JadeSettings.defaults(),
                        (com.jadecode.config.JadeSettings.defaults()).permissions().state(null),
                        com.jadecode.support.TestPaths.jade(workspace), null, null, ignored -> {
                        }, () -> com.jadecode.llm.ResponseStream.NONE, com.jadecode.execution.ExecutionObserver.NONE,
                        com.jadecode.hooks.Hooks::new,
                        (summary, paths) -> new com.jadecode.context.ProductionCompactor(summary, paths,
                                (com.jadecode.config.JadeSettings.defaults()).contextCompaction())))) {
            var display = runtime.display();
            assertEquals(List.of("AGENTS.md"), display.resources().get("Context"));
            assertEquals(List.of("local"), display.resources().get("Skills"));
            String wire = BackendSnapshot.capture(1, display, null, JadeSettings.defaults(), 0, List.of(),
                    java.util.Map.of(), "", "", java.util.Map.of(), java.util.Map.of()).toJson().toString();
            assertTrue(wire.contains("AGENTS.md"));
            assertTrue(wire.contains("Skills"));
            assertFalse(wire.contains("PRIVATE_"));
        }
    }
}
