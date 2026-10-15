package com.example.addon.modules;

import com.example.addon.modules.TitleAntiAFK.LookStep;
import com.example.addon.modules.TitleAntiAFK.StateMachine;
import com.example.addon.modules.TitleAntiAFK.StateMachine.TickResult;
import com.example.addon.modules.TitleAntiAFK.StateMachine.TitleProcessOutcome;
import com.example.addon.modules.TitleAntiAFK.StepTarget;
import com.example.addon.modules.TitleAntiAFK.TitleActions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TitleAntiAFKTest {

    // ================= 1. TITLE PARSING & NORMALIZATION =================

    @Test
    void testNormalizeTitleStripsFormattingAndCollapsesWhitespace() {
        assertEquals("", TitleAntiAFK.normalizeTitle(null));
        assertEquals("", TitleAntiAFK.normalizeTitle(""));
        assertEquals("", TitleAntiAFK.normalizeTitle("   \t\n  "));
        assertEquals("", TitleAntiAFK.normalizeTitle("\u00A7r\u00A77   \u00A7l"));
        assertEquals("look left", TitleAntiAFK.normalizeTitle("\u00A7c\u00A7lLOOK \u00A7e\u00A7lLEFT"));
        assertEquals("look right now", TitleAntiAFK.normalizeTitle("  Look   Right\n\tNow  "));
        assertEquals("sneak", TitleAntiAFK.normalizeTitle("\u00A7aSneak"));
    }

    @Test
    void testParseTitleActionsDetectsAllKeywords() {
        TitleActions sneak = TitleAntiAFK.parseTitleActions("\u00A7ePlease SNEAK now!");
        assertTrue(sneak.sneak());
        assertFalse(sneak.jump());
        assertFalse(sneak.hasLookAction());
        assertTrue(sneak.hasAnyAction());

        TitleActions jump = TitleAntiAFK.parseTitleActions("JUMP to verify");
        assertTrue(jump.jump());
        assertTrue(jump.hasAnyAction());

        TitleActions lookLeft = TitleAntiAFK.parseTitleActions("\u00A7cLook   \u00A7fLeft");
        assertTrue(lookLeft.lookLeft());
        assertTrue(lookLeft.hasLookAction());

        TitleActions lookRight = TitleAntiAFK.parseTitleActions("Look Right");
        assertTrue(lookRight.lookRight());
        assertTrue(lookRight.hasLookAction());

        TitleActions lookUp = TitleAntiAFK.parseTitleActions("Look Up");
        assertTrue(lookUp.lookUp());
        assertTrue(lookUp.hasLookAction());

        TitleActions lookDown = TitleAntiAFK.parseTitleActions("Look Down");
        assertTrue(lookDown.lookDown());
        assertTrue(lookDown.hasLookAction());

        TitleActions nonAction = TitleAntiAFK.parseTitleActions("Welcome to ComplexMC");
        assertFalse(nonAction.hasAnyAction());

        TitleActions blankFormatting = TitleAntiAFK.parseTitleActions("\u00A7r   ");
        assertFalse(blankFormatting.hasAnyAction());
    }

    @Test
    void testDemoTitleEnumValuesParseAsValidActions() {
        for (TitleDemoer.DemoTitle demo : TitleDemoer.DemoTitle.values()) {
            TitleActions actions = TitleAntiAFK.parseTitleActions(demo.getText());
            assertTrue(actions.hasAnyAction(), "DemoTitle " + demo + " should parse as an active action");
        }
    }

    // ================= 2. YAW / PITCH STEP MATH =================

    @Test
    void testLookStepSequenceCyclesThroughAllSevenSteps() {
        LookStep step = LookStep.LOOK_LEFT;
        assertTrue(step.isHorizontal());

        step = step.next();
        assertEquals(LookStep.CENTER_FROM_LEFT, step);
        assertTrue(step.isHorizontal());

        step = step.next();
        assertEquals(LookStep.LOOK_RIGHT, step);
        assertTrue(step.isHorizontal());

        step = step.next();
        assertEquals(LookStep.CENTER_FROM_RIGHT, step);
        assertTrue(step.isHorizontal());

        step = step.next();
        assertEquals(LookStep.LOOK_UP, step);
        assertFalse(step.isHorizontal());

        step = step.next();
        assertEquals(LookStep.LOOK_DOWN, step);
        assertFalse(step.isHorizontal());

        step = step.next();
        assertEquals(LookStep.RESET_PITCH, step);
        assertFalse(step.isHorizontal());

        step = step.next();
        assertEquals(LookStep.LOOK_LEFT, step);
    }

    @Test
    void testComputeStepTargetAnchorsToOriginAndClampsPitch() {
        float originYaw = 45f;
        float originPitch = 15f;

        StepTarget left = TitleAntiAFK.computeStepTarget(LookStep.LOOK_LEFT, originYaw, originPitch);
        assertEquals(-135f, left.targetYaw(), 1e-4f);
        assertEquals(15f, left.targetPitch(), 1e-4f);
        assertEquals(1.2f, left.durationSeconds(), 1e-4f);

        StepTarget centerLeft = TitleAntiAFK.computeStepTarget(LookStep.CENTER_FROM_LEFT, originYaw, originPitch);
        assertEquals(45f, centerLeft.targetYaw(), 1e-4f);
        assertEquals(15f, centerLeft.targetPitch(), 1e-4f);

        StepTarget right = TitleAntiAFK.computeStepTarget(LookStep.LOOK_RIGHT, originYaw, originPitch);
        assertEquals(225f, right.targetYaw(), 1e-4f);
        assertEquals(15f, right.targetPitch(), 1e-4f);

        StepTarget up = TitleAntiAFK.computeStepTarget(LookStep.LOOK_UP, originYaw, originPitch);
        assertEquals(45f, up.targetYaw(), 1e-4f);
        assertEquals(-75f, up.targetPitch(), 1e-4f);
        assertEquals(0.8f, up.durationSeconds(), 1e-4f);

        StepTarget down = TitleAntiAFK.computeStepTarget(LookStep.LOOK_DOWN, originYaw, originPitch);
        assertEquals(45f, down.targetYaw(), 1e-4f);
        assertEquals(75f, down.targetPitch(), 1e-4f);
        assertEquals(1.0f, down.durationSeconds(), 1e-4f);

        StepTarget reset = TitleAntiAFK.computeStepTarget(LookStep.RESET_PITCH, originYaw, originPitch);
        assertEquals(45f, reset.targetYaw(), 1e-4f);
        assertEquals(15f, reset.targetPitch(), 1e-4f);
        assertEquals(0.6f, reset.durationSeconds(), 1e-4f);
    }

    @Test
    void testSmoothProgressBoundariesAndMonotonicity() {
        assertEquals(0.0f, TitleAntiAFK.computeSmoothProgress(0.0f, 1.2f), 1e-5f);
        assertEquals(0.0f, TitleAntiAFK.computeSmoothProgress(-0.5f, 1.2f), 1e-5f);
        assertEquals(0.5f, TitleAntiAFK.computeSmoothProgress(0.6f, 1.2f), 1e-5f);
        assertEquals(1.0f, TitleAntiAFK.computeSmoothProgress(1.2f, 1.2f), 1e-5f);
        assertEquals(1.0f, TitleAntiAFK.computeSmoothProgress(2.5f, 1.2f), 1e-5f);
        assertEquals(1.0f, TitleAntiAFK.computeSmoothProgress(0.1f, 0.0f), 1e-5f);

        float prev = 0f;
        for (int i = 1; i <= 12; i++) {
            float cur = TitleAntiAFK.computeSmoothProgress(i * 0.1f, 1.2f);
            assertTrue(cur >= prev, "Smooth progress should be monotonically non-decreasing");
            prev = cur;
        }
    }

    @Test
    void testPitchClampingPreventsIllegalPitchWithWobble() {
        assertEquals(90.0f, TitleAntiAFK.clampPitch(95.0f), 1e-5f);
        assertEquals(-90.0f, TitleAntiAFK.clampPitch(-95.0f), 1e-5f);

        // Near +90 pitch during horizontal turn with wobble enabled must never exceed +90
        float[] angles = TitleAntiAFK.computeFrameAngles(
            LookStep.LOOK_LEFT,
            0f,
            -180f,
            89.95f,
            89.95f,
            0.5f,
            1.2f,
            true
        );
        assertTrue(angles[1] <= 90.0f && angles[1] >= -90.0f, "Pitch must stay clamped within [-90, 90]");
    }

    // ================= 3. STATE MACHINE TRANSITIONS =================

    @Test
    void testStandardModeIgnoresNonActionAndBlankTitles() {
        StateMachine sm = new StateMachine();

        TitleProcessOutcome out1 = sm.onTitle("Welcome to Skyblock!", 10, 64, 10, 90f, 0f, false, false, 7, 3);
        assertEquals(TitleProcessOutcome.IGNORED_NO_ACTION, out1);
        assertFalse(sm.isSavedState());
        assertEquals(0, sm.getTitleActiveTicks());

        TitleProcessOutcome out2 = sm.onTitle("\u00A7r   ", 10, 64, 10, 90f, 0f, true, true, 7, 3);
        assertEquals(TitleProcessOutcome.IGNORED_BLANK, out2);
        assertFalse(sm.isSavedState());
        assertEquals(0, sm.getBruteForceTotalTicks());
    }

    @Test
    void testStandardModeConsecutiveTitlesAnchorYawAndRestoreStateOnExpiry() {
        StateMachine sm = new StateMachine();

        // 1. Trigger "Look Left" at yaw=90, pitch=10
        TitleProcessOutcome out1 = sm.onTitle("\u00A7cLook \u00A7fLeft", 100, 65, -200, 90f, 10f, false, false, 7, 2);
        assertEquals(TitleProcessOutcome.STARTED_STANDARD_ACTION, out1);
        assertTrue(sm.isSavedState());
        assertEquals(40, sm.getTitleActiveTicks());
        assertEquals(-90f, sm.getTargetYaw(), 1e-4f);
        assertFalse(sm.isShiftDown());

        // Advance 8 ticks (0.4s -> halfway through 0.8s look turn, yaw becomes 0)
        float currentYaw = 90f;
        float currentPitch = 10f;
        for (int i = 0; i < 8; i++) {
            TickResult tr = sm.onTick(1, currentYaw, currentPitch);
            assertTrue(tr.updateStandardLook());
            currentYaw = tr.newYaw();
            currentPitch = tr.newPitch();
        }
        assertEquals(0f, currentYaw, 1e-2f);

        // 2. Consecutive title "Look Right" arrives mid-turn (currentYaw=0, but originalYaw=90)
        TitleProcessOutcome out2 = sm.onTitle("Look Right", 105, 65, -200, currentYaw, currentPitch, false, false, 7, 1);
        assertEquals(TitleProcessOutcome.STARTED_STANDARD_ACTION, out2);
        // Original position & yaw must stay anchored to initial (100, 65, -200, yaw=90)
        assertEquals(100.0, sm.getOriginalX(), 1e-4);
        assertEquals(90f, sm.getOriginalYaw(), 1e-4f);
        // Target yaw must be originalYaw + 180 = 270, NOT currentYaw + 180 = 180
        assertEquals(270f, sm.getTargetYaw(), 1e-4f);

        // 3. Consecutive title "Sneak" arrives -> shiftDown becomes true
        sm.onTitle("Sneak", 105, 65, -200, currentYaw, currentPitch, false, false, 7, 1);
        assertTrue(sm.isShiftDown());
        assertEquals(20, sm.getTitleActiveTicks());

        // Advance 19 ticks (active), then on the 20th tick titleActiveTicks reaches 0 and restores state
        for (int i = 0; i < 19; i++) {
            TickResult tr = sm.onTick(1, currentYaw, currentPitch);
            assertFalse(tr.restoreState());
        }
        TickResult finalTick = sm.onTick(1, currentYaw, currentPitch);
        assertTrue(finalTick.restoreState());
        assertFalse(finalTick.restoredFromBruteForce());
        assertEquals(90f, finalTick.newYaw(), 1e-4f);
        assertEquals(10f, finalTick.newPitch(), 1e-4f);
        assertFalse(sm.isShiftDown());
    }

    @Test
    void testStandardModeClearTitlesPacketImmediatelyExpiresActiveTitle() {
        StateMachine sm = new StateMachine();
        sm.onTitle("Jump", 0, 64, 0, 180f, -20f, false, false, 7, 5);
        assertTrue(sm.isJumpDown());
        assertEquals(100, sm.getTitleActiveTicks());

        // Receive ClearTitles packet while standard title is active
        sm.onClearTitles();
        assertEquals(0, sm.getTitleActiveTicks());
        assertNull(sm.getActiveTitleText());

        // Next tick restores state and releases jump key
        TickResult tr = sm.onTick(1, 180f, -20f);
        assertTrue(tr.restoreState());
        assertFalse(sm.isJumpDown());
    }

    @Test
    void testStandardModeLookUpAndLookDownPitchInterpolation() {
        StateMachine sm = new StateMachine();
        sm.onTitle("Look Up", 0, 64, 0, 0f, 0f, false, false, 7, 3);
        assertEquals(-75f, sm.getTargetPitch(), 1e-4f);

        float pitch = 0f;
        for (int i = 0; i < 16; i++) {
            TickResult tr = sm.onTick(1, 0f, pitch);
            assertTrue(tr.updateStandardLook());
            pitch = tr.newPitch();
        }
        assertEquals(-75f, pitch, 1e-2f);

        sm.onTitle("Look Down", 0, 64, 0, 0f, pitch, false, false, 7, 3);
        assertEquals(75f, sm.getTargetPitch(), 1e-4f);
    }

    @Test
    void testBruteForceRenderLoopCompletesFullSevenStepCycle() {
        StateMachine sm = new StateMachine();
        sm.onTitle("Verify AFK", 0, 64, 0, 10f, 5f, true, false, 10, 3);
        assertEquals(LookStep.LOOK_LEFT, sm.getCurrentLookStep());

        LookStep[] expectedOrder = {
            LookStep.CENTER_FROM_LEFT,
            LookStep.LOOK_RIGHT,
            LookStep.CENTER_FROM_RIGHT,
            LookStep.LOOK_UP,
            LookStep.LOOK_DOWN,
            LookStep.RESET_PITCH,
            LookStep.LOOK_LEFT
        };

        float yaw = 10f;
        float pitch = 5f;
        for (LookStep expectedNext : expectedOrder) {
            float duration = sm.getStepDurationSeconds();
            float[] angles = sm.onRenderFrame(duration, yaw, pitch, false);
            yaw = angles[0];
            pitch = angles[1];
            assertEquals(expectedNext, sm.getCurrentLookStep());
        }
        assertEquals(10f, yaw, 1e-3f);
        assertEquals(5f, pitch, 1e-3f);
    }

    @Test
    void testBruteForceModeLifecycleCadenceAndNoFallthroughToStandard() {
        StateMachine sm = new StateMachine();

        // Trigger brute force with duration=2s (40 ticks), cadence=1s (20 ticks), titleHold=5s (100 ticks)
        TitleProcessOutcome outcome = sm.onTitle("Any Title!", 1.0, 2.0, 3.0, 0f, 0f, false, true, 2, 5);
        assertEquals(TitleProcessOutcome.STARTED_BRUTE_FORCE, outcome);
        assertEquals(40, sm.getBruteForceTotalTicks());
        assertEquals(0, sm.getTitleActiveTicks());
        assertEquals(LookStep.LOOK_LEFT, sm.getCurrentLookStep());

        // Secondary title during active brute force is ignored
        assertEquals(
            TitleProcessOutcome.IGNORED_BRUTE_FORCE_ACTIVE,
            sm.onTitle("Sneak", 5.0, 5.0, 5.0, 45f, 10f, false, true, 2, 5)
        );

        // ClearTitles packet during active brute force does not abort brute force
        sm.onClearTitles();
        assertEquals(40, sm.getBruteForceTotalTicks());

        // Frame rendering advances LookStep when frameTime >= stepDurationSeconds
        float[] angles = sm.onRenderFrame(1.2f, 0f, 0f, false);
        assertEquals(-180f, angles[0], 1e-3f);
        assertEquals(LookStep.CENTER_FROM_LEFT, sm.getCurrentLookStep());

        // Tick 1 (remaining=40 -> 40 % 20 == 0): triggers cadence jump/punch and 8-tick sneak pulse
        TickResult tick1 = sm.onTick(1, angles[0], angles[1]);
        assertTrue(tick1.triggerJumpAndPunch());
        assertTrue(sm.isShiftDown());
        assertEquals(8, sm.getSneakPulseTicks());

        // Ticks 2..9: sneak pulse counts down from 8 to 0 and releases shift
        for (int i = 0; i < 8; i++) {
            TickResult tr = sm.onTick(1, angles[0], angles[1]);
            assertFalse(tr.triggerJumpAndPunch());
        }
        assertEquals(0, sm.getSneakPulseTicks());
        assertFalse(sm.isShiftDown());

        // Run remaining ticks up to tick 40
        for (int i = 9; i < 39; i++) {
            TickResult tr = sm.onTick(1, angles[0], angles[1]);
            assertFalse(tr.restoreState());
        }

        // Tick 40 (final tick, bruteForceTotalTicks reaches 0): restores state without jumping on final tick
        TickResult lastTick = sm.onTick(1, angles[0], angles[1]);
        assertFalse(lastTick.triggerJumpAndPunch(), "Should not jump on the final restoration tick");
        assertTrue(lastTick.restoreState());
        assertTrue(lastTick.restoredFromBruteForce());
        assertFalse(sm.isShiftDown());
        sm.markStateRestored();

        // Subsequent tick after brute force finished must NOT fall through into standard title actions
        TickResult afterTick = sm.onTick(1, 0f, 0f);
        assertFalse(afterTick.restoreState());
        assertFalse(afterTick.updateStandardLook());
        assertFalse(sm.isShiftDown());
    }

    @Test
    void testHoldAutoLogPredicateIgnoresNullAndAir() {
        assertFalse(HoldAutoLog.shouldAutoLog(null, null, null));
    }
}

