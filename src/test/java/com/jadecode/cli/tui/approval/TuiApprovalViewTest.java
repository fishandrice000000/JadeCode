package com.jadecode.cli.tui.approval;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.jadecode.cli.tui.UiEvent;
import com.jadecode.tui.core.ActionToken;
import com.jadecode.tui.core.Frame;
import com.jadecode.tui.core.PresentedFrame;
import com.jadecode.tui.core.RenderContext;
import com.jadecode.tui.geometry.Rect;
import com.jadecode.tui.text.StyledLine;
import com.jadecode.tui.theme.Theme;
import com.jadecode.tui.theme.ThemePalette;

/** 审批显示证据/水位/无默认/小窗不发布批准动作的测试。 */
class TuiApprovalViewTest {
    @Test void eachVerticalChoiceUsesItsOwnColorInDarkAndLightThemes() {
        for (String name : List.of("dark", "light")) {
            var palette = new com.jadecode.tui.theme.ThemeManager(name, null, true, "light", "dark").preview();
            for (int choice = 0; choice < 3; choice++) {
                var state = new ApprovalViewState(); state.show(request, 0, 1);
                for (int i = 0; i <= choice; i++) state.move(1, true);
                var rows = new ApprovalView().render(state, new RenderContext(80, 24, palette), 80, 20);
                String label = List.of("Allow once", "Allow similar", "Deny").get(choice);
                var selected = rows.lines().stream().flatMap(line -> line.spans().stream())
                        .filter(span -> span.text().contains(label)).findFirst().orElseThrow();
                String token = List.of("success", "warning", "error").get(choice);
                assertEquals(palette.color(token), selected.style().bg());
                assertNotEquals(selected.style().fg(), selected.style().bg());
                assertEquals(3, rows.buttons().stream().map(ApprovalView.Button::row).distinct().count());
            }
        }
        // 这类绿底用 RGB 简单加权容易误配白字；实际对比度应选择黑字。
        var custom = new Theme("custom", "dark", java.util.Map.of("success",
                com.jadecode.tui.text.Color.rgb(80, 170, 110)), true, 1);
        var state = new ApprovalViewState(); state.show(request, 0, 1); state.move(1, true);
        var selected = new ApprovalView().render(state, new RenderContext(80, 24, custom), 80, 20)
                .lines().stream().flatMap(line -> line.spans().stream())
                .filter(span -> span.text().contains("Allow once")).findFirst().orElseThrow();
        assertEquals(com.jadecode.tui.text.Color.rgb(0, 0, 0), selected.style().fg());
    }
    private final Theme theme = Theme.from(ThemePalette.builtin("dark"), true, 1);
    private final UiEvent.ApprovalRequested request = new UiEvent.ApprovalRequested("id-1", "leader",
            "bash", "printf 'x'\nrm -rf /tmp/demo", "Needs confirmation", "bash: rm", 7L);

    private PresentedFrame frame(ActionToken.Kind kind, long revision) {
        var token = new ActionToken(kind, "id-1", revision);
        var frame = new Frame(3, 1, 1, List.of(StyledLine.empty()), Frame.NO_CURSOR,
                List.of(new Frame.Hit(new Rect(0, 0, 10, 1), token)));
        return new PresentedFrame(3, 1, 80, 24, 1, List.of(token));
    }

    @Test void noDefaultChoiceAndSuggestionGating() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        assertEquals(-1, state.choice());
        assertNull(state.selectedAnswer());
        state.move(1, true);
        assertEquals(0, state.choice());
        state.move(1, false);
        assertEquals(2, state.choice());
        state.move(-1, false);
        assertEquals(0, state.choice());
    }

    @Test void approvalRequiresPresentedFrameAndLaterInputWatermark() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        var accept = frame(ActionToken.Kind.APPROVE, 7);
        assertFalse(state.canAnswer(11, 1, accept, ActionToken.Kind.APPROVE));
        state.presented(accept, 1, 10);
        assertTrue(state.presented());
        assertTrue(state.canAnswer(11, 1, accept, ActionToken.Kind.APPROVE));
        assertFalse(state.canAnswer(10, 1, accept, ActionToken.Kind.APPROVE));
        assertFalse(state.canAnswer(11, 2, accept, ActionToken.Kind.APPROVE));
        assertFalse(state.canAnswer(11, 1, accept, ActionToken.Kind.DENY));
    }

    @Test void resizeInvalidatesEvidenceAndSmallWindowHasNoButtons() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        state.presented(frame(ActionToken.Kind.APPROVE, 7), 1, 4);
        assertTrue(state.presented());
        state.invalidate();
        assertFalse(state.presented());
        var view = new ApprovalView();
        var small = view.render(state, new RenderContext(12, 6, theme), 12, 4);
        assertTrue(small.buttons().isEmpty());
        assertTrue(small.lines().stream().anyMatch(line -> line.text().contains("Enlarge")));
    }

    @Test void reasonIsHiddenAndUnselectedDenyStaysNeutral() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        var rendered = new ApprovalView().render(state, new RenderContext(80, 24, theme), 80, 20);
        String all = rendered.lines().stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
        assertFalse(all.contains("Needs confirmation"), "TUI must not draw the reason segment");
        assertTrue(all.strip().startsWith("┌"), all);
        assertTrue(all.strip().endsWith("┘"), all);
        var denySpan = rendered.lines().stream().flatMap(line -> line.spans().stream())
                .filter(span -> span.text().contains("Deny")).findFirst().orElseThrow();
        assertFalse(denySpan.style().bold(), "unselected Deny must not be bold");
        assertFalse(denySpan.style().fg().equals(theme.style("error").fg()), "unselected Deny must be neutral");
        state.move(-1, true); // 从 -1 向上到 Deny（index 2）
        assertEquals(2, state.choice());
        var selectedDeny = new ApprovalView().render(state, new RenderContext(80, 24, theme), 80, 20)
                .lines().stream().flatMap(line -> line.spans().stream())
                .filter(span -> span.text().contains("Deny")).findFirst().orElseThrow();
        assertTrue(selectedDeny.style().bold(), "selected Deny may be emphasized");
    }

    @Test void smallBudgetNeverPublishesApprovalTokens() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        for (int budget : List.of(1, 3, 5, 6)) {
            var rendered = new ApprovalView().render(state, new RenderContext(40, 12, theme), 40, budget);
            assertTrue(rendered.buttons().stream().allMatch(button -> button.kind() == ActionToken.Kind.DENY),
                    "budget " + budget + " must expose only safe denial");
            assertTrue(rendered.lines().size() <= budget, "budget " + budget + " overflowed");
        }
    }

    @Test void selectedButtonUsesSemanticBackgroundAndCoordinatesMatchPanelText() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        state.move(1, true); // Allow once
        var panelTheme = new com.jadecode.tui.theme.ThemeManager("dark", null, true, "light", "dark").preview();
        var rendered = new ApprovalView().render(state, new RenderContext(80, 24, panelTheme), 80, 20);
        assertTrue(rendered.lines().stream().allMatch(line -> line.width() == 80));
        assertTrue(rendered.lines().getFirst().text().startsWith("┌")
                && rendered.lines().getFirst().text().endsWith("┐"), rendered.lines().getFirst().text());
        var selected = rendered.lines().stream().flatMap(line -> line.spans().stream())
                .filter(span -> span.text().contains("Allow once")).findFirst().orElseThrow();
        assertEquals(panelTheme.color("success"), selected.style().bg(), "selected full label must have green background");
        assertFalse(selected.style().inverse(), "explicit colors must not be inverted again");
        var button = rendered.buttons().stream().filter(value -> value.kind() == ActionToken.Kind.APPROVE)
                .findFirst().orElseThrow();
        String line = rendered.lines().get(button.row()).text();
        assertEquals(button.from(), line.indexOf("["), "hit/from must be post-border panel coordinates");
        assertTrue(button.to() > button.from());
        assertTrue(rendered.choiceRow() == button.row());
        assertEquals(button.from(), rendered.choiceColumn());
    }

    @Test void optionsAlwaysUseSeparateRowsBeforeBudgeting() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        var panelTheme = new com.jadecode.tui.theme.ThemeManager("dark", null, true, "light", "dark").preview();
        var wide = new ApprovalView().render(state, new RenderContext(80, 24, panelTheme), 80, 20);
        // 宽屏也必须一项一行，不能退回旧横排。
        long wideRows = wide.lines().stream().filter(line -> line.text().contains("Allow once")
                && line.text().contains("Deny")).count();
        assertEquals(0, wideRows, "wide panel must keep vertical options");
        assertEquals(3, wide.buttons().stream().map(ApprovalView.Button::row).distinct().count());
        var narrow = new ApprovalView().render(state, new RenderContext(34, 24, panelTheme), 34, 20);
        long stacked = narrow.lines().stream().filter(line -> line.text().contains("Allow once")).count()
                + narrow.lines().stream().filter(line -> line.text().contains("Deny")).count();
        assertTrue(stacked >= 2, "narrow panel must stack buttons and still show each label");
        assertTrue(narrow.buttons().stream().map(ApprovalView.Button::row).distinct().count() >= 2);
        assertTrue(narrow.lines().getFirst().text().startsWith("┌")
                && narrow.lines().getFirst().text().endsWith("┐"), narrow.lines().getFirst().text());
        assertTrue(narrow.lines().getLast().text().startsWith("└")
                && narrow.lines().getLast().text().endsWith("┘"), narrow.lines().getLast().text());
    }

    @Test void noColorUsesSelectionMarkerInsteadOfInverse() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        state.move(1, true);
        var plain = new Theme(theme.name(), theme.appearance(), theme.colors(), false, 1);
        var rendered = new ApprovalView().render(state, new RenderContext(80, 24, plain), 80, 20);
        var selected = rendered.lines().stream().flatMap(line -> line.spans().stream())
                .filter(span -> span.text().contains("Allow once")).findFirst().orElseThrow();
        assertTrue(selected.text().contains("›"), "NO_COLOR keeps an explicit selection marker");
        assertFalse(selected.style().inverse(), "NO_COLOR must not emit inverse attributes");
    }


    @Test void singlePersonApprovalHidesAllActorLabelsButTeamShowsSource() {
        var state = new ApprovalViewState();
        var solo = new UiEvent.ApprovalRequested("id-9", "leader/sub-3", "bash", "{}", "r", "", 3);
        state.show(solo, 0, 1);
        var soloLines = new ApprovalView().render(state, new RenderContext(80, 24, theme), 80, 20, false);
        String soloTitle = soloLines.lines().getFirst().text();
        assertTrue(soloTitle.startsWith("┌─ Approval required · bash"), soloTitle);
        assertFalse(soloTitle.contains("Leader"), soloTitle);
        assertFalse(soloTitle.contains("sub-3"), soloTitle);
        assertEquals("leader/sub-3", state.request().actor(), "display rule must not change approval identity");
        var teamLines = new ApprovalView().render(state, new RenderContext(80, 24, theme), 80, 20, true);
        assertTrue(teamLines.lines().getFirst().text().contains("Leader / sub-3"),
                teamLines.lines().getFirst().text());

        var dockState = new com.jadecode.cli.tui.TuiState(); dockState.installGeneration(0);
        dockState.approvals.addLast(solo); dockState.approval.show(solo, 0, 1);
        String single = new com.jadecode.cli.tui.view.DockView().render(dockState,
                new RenderContext(80, 24, theme), 80, 20).lines().stream()
                .map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(single.contains("Approval required · bash"));
        assertFalse(single.contains("Leader"));
        // 只有真实常驻 Team 成员才显示受信来源。
        dockState.actors.put("agent_abc", "RUNNING");
        String team = new com.jadecode.cli.tui.view.DockView().render(dockState,
                new RenderContext(80, 24, theme), 80, 20).lines().stream()
                .map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(team.contains("Approval required · Leader / sub-3 · bash"), team);
    }

    @Test void dockReplacesEditorWithBoundedPanelAndRespectsBudget() {
        var state = new com.jadecode.cli.tui.TuiState();
        state.installGeneration(0);
        state.approvals.addLast(request);
        state.approval.show(request, 0, 1);
        var editor = new com.jadecode.tui.components.Editor(
                new com.jadecode.tui.input.KeyBindings(java.util.Map.of()), null);
        editor.insert("KEEP_DRAFT", false);
        state.editor = editor;
        var dock = new com.jadecode.cli.tui.view.DockView().render(state,
                new RenderContext(80, 24, theme), 80, 12);
        String all = dock.lines().stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
        assertFalse(all.contains("KEEP_DRAFT"), "approval must replace, not draw, the editor");
        assertTrue(all.contains("Parameters:"));
        assertTrue(all.contains("Allow once"));
        assertTrue(all.contains("Needs confirmation") == false, "reason stays out of the TUI");
        assertTrue(dock.lines().size() <= 12);
        assertTrue(dock.lines().stream().anyMatch(line -> line.text().startsWith("┌")));
        assertTrue(dock.lines().stream().anyMatch(line -> line.text().startsWith("└")));
        // Dock 只做父区域平移：hit 的列必须已经是完成边框/留白后的面板坐标。
        var approve = dock.hits().stream().filter(hit -> hit.action().kind() == ActionToken.Kind.APPROVE)
                .findFirst().orElseThrow();
        String hitLine = dock.lines().get(approve.rect().y()).text();
        assertTrue(hitLine.substring(approve.rect().x(), approve.rect().x() + approve.rect().width()).contains("Allow once"),
                hitLine);
        assertEquals(hitLine.indexOf("["), approve.rect().x(), "hit x must match post-border label start");
    }

    @Test void fullParametersAreVisibleAndScrollable() {
        var state = new ApprovalViewState();
        state.show(request, 0, 1);
        var rendered = new ApprovalView().render(state, new RenderContext(60, 20, theme), 60, 20);
        String all = rendered.lines().stream().map(StyledLine::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("Parameters:"));
        assertTrue(all.contains("rm -rf /tmp/demo"));
        assertTrue(all.contains("Long-term scope:"));
        assertTrue(state.totalLines() > 0);
        state.page(2, 100);
        state.scroll(1);
        assertEquals(1, state.offset());
    }
}
