package com.jadecode.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jadecode.agent.AgentExecutor;
import com.jadecode.config.WorkspacePaths;
import com.jadecode.hooks.Hooks;
import com.jadecode.session.SessionRuntime;

/** 用真实清单验证 UUID 优先和标题回退；运行时不 start，任何模型请求都会使测试失败。 */
class SessionHostLookupTest {
    @TempDir Path workspace;

    private SessionHost host() {
        return new SessionHost(new WorkspacePaths(workspace), generation -> new SessionRuntime(
                new AgentExecutor(request -> { throw new AssertionError("Lookup must not call a model"); },
                        Map.of(), "test", 1000, new Hooks()), ignored -> { }, ignored -> { }), ignored -> { });
    }

    /** 准备真实会话清单与名称，身份仍由存储层生成，不伪造 UUID 目录。 */
    private String create(SessionHost host, String title) throws Exception {
        host.open(null);
        String id = host.current().sessionId();
        host.rename(id, title);
        return id;
    }

    @Test void anExistingUuidWinsOverAnIdenticalTitleAndAcceptsUpperCase() throws Exception {
        try (var host = host()) {
            String id = create(host, "Original");
            create(host, id); // 另一会话恰好以该 UUID 作标题，仍应优先恢复原身份。
            assertEquals(List.of(id), host.find(id).stream().map(item -> item.id()).toList());
            assertEquals(List.of(id), host.find(" " + id.toUpperCase(Locale.ROOT) + " ").stream().map(item -> item.id()).toList());
        }
    }

    @Test void anUnmatchedUuidFallsBackToAllExactTitleMatches() throws Exception {
        try (var host = host()) {
            String title = "00000000-0000-0000-0000-000000000001";
            String first = create(host, title);
            String second = create(host, title);
            var ids = host.find(title).stream().map(item -> item.id()).toList();
            assertEquals(2, ids.size());
            assertTrue(ids.containsAll(List.of(first, second)), "duplicate titles must remain explicit choices");
            assertTrue(host.find("00000000-0000-0000-0000-000000000002").isEmpty());
        }
    }

    @Test void ordinaryTextAndUuidShortFormsUseOnlyTitleMatching() throws Exception {
        try (var host = host()) {
            String first = create(host, "Daily Review");
            String shortForm = create(host, "1-1-1-1-1");
            assertEquals(first, host.find("review").getFirst().id());
            assertEquals(shortForm, host.find("1-1-1-1-1").getFirst().id());
        }
    }
}
