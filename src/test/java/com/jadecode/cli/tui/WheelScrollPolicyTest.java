package com.jadecode.cli.tui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 固定 Pi wheel-scroll.ts 的隔离向量，以及设置候选/哨兵行为。 */
class WheelScrollPolicyTest {
    @Test void fixedLinesIgnoreTimingAndInvalidFractionsBecomeOne() {
        var policy = new WheelScrollPolicy(3, true);
        for (long time : List.of(0L, 10L, 20L, 1000L)) assertEquals(3, policy.lines(1, time));
        policy.setLines(0);
        policy.setLines(1);
        assertEquals(1, policy.lines(1, 2000));
    }

    @Test void autoStaysOneWhenTerminalAccelerates() {
        var policy = new WheelScrollPolicy(0, false);
        for (long time : List.of(0L, 10L, 20L, 30L)) assertEquals(1, policy.lines(1, time));
    }

    @Test void autoUsesPiVelocityBurstPauseAndDirectionVectors() {
        var policy = new WheelScrollPolicy(0, true);
        assertEquals(List.of(1, 1, 1, 1), scroll(policy, List.of(0L, 150L, 300L, 450L)));
        assertEquals(List.of(1, 2, 2, 2), scroll(policy, List.of(1000L, 1050L, 1100L, 1150L)));
        assertEquals(List.of(1, 5, 5, 5), scroll(policy, List.of(2000L, 2020L, 2040L, 2060L)));
        assertEquals(List.of(1, 6, 6, 6), scroll(policy, List.of(3000L, 3010L, 3020L, 3030L)));
        assertEquals(List.of(1, 1, 1, 1), scroll(policy, List.of(0L, 3L, 6L, 9L)));
    }

    @Test void autoResetsOnDirectionPauseAndSettingChange() {
        var policy = new WheelScrollPolicy(0, true);
        assertEquals(List.of(1, 5, 5), scroll(policy, List.of(0L, 20L, 40L)));
        assertEquals(1, policy.lines(-1, 60L));
        assertEquals(List.of(1, 5), scroll(policy, List.of(500L, 520L)));
        policy.setLines(3);
        policy.setLines(0);
        assertEquals(1, policy.lines(1, 600L), "setting change resets gesture");
    }

    @Test void fractionalCarryMatchesPi() {
        var policy = new WheelScrollPolicy(0, true);
        assertEquals(List.of(1, 2, 3, 2, 3), scroll(policy, List.of(0L, 40L, 80L, 120L, 160L)));
    }

    @Test void altMultiplierAppliesOnceAfterAutoCalculation() {
        var policy = new WheelScrollPolicy(0, true);
        assertEquals(5, policy.lines(1, 0L, true), "first notch 1 * 5");
        assertEquals(25, policy.lines(1, 20L, true), "auto 5 * 5");
        policy.setLines(3);
        assertEquals(15, policy.lines(1, 1000L, true));
        assertEquals(3, policy.lines(1, 1000L, false));
    }

    @Test void settingsCandidatesCycleBackToAutoAndKeepCustomNumbers() {
        assertEquals(List.of(0, 1, 2, 3, 5, 10), WheelScrollPolicy.candidates(0));
        assertEquals(List.of(0, 1, 2, 3, 5, 7, 10), WheelScrollPolicy.candidates(7));
        int value = 0;
        var seen = new java.util.ArrayList<Integer>();
        for (int i = 0; i < 6; i++) { value = WheelScrollPolicy.next(value); seen.add(value); }
        assertEquals(List.of(1, 2, 3, 5, 10, 0), seen);
        value = 7;
        var custom = new java.util.ArrayList<Integer>();
        for (int i = 0; i < 7; i++) { value = WheelScrollPolicy.next(value); custom.add(value); }
        assertEquals(List.of(10, 0, 1, 2, 3, 5, 10), custom);
    }

    private static List<Integer> scroll(WheelScrollPolicy policy, List<Long> times) {
        return times.stream().map(time -> policy.lines(1, time)).toList();
    }
}
