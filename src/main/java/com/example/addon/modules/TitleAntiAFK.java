package com.example.addon.modules;

import com.example.addon.AddonMain;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

public class TitleAntiAFK extends Module {
    private static final float PI_FLOAT = (float) Math.PI;
    private static final Pattern FORMATTING_CODE_PATTERN = Pattern.compile("(?i)\u00A7[0-9A-FK-ORX]");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBruteForce = settings.createGroup("Brute Force");

    private final Setting<Boolean> bruteForce = sgBruteForce.add(new BoolSetting.Builder()
        .name("brute-force")
        .description("Aggressively performs pixel-perfect smooth 180-degree turns and pitch up/down with 1-second cadence jumping, sneaking, and punching.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onAnyTitle = sgBruteForce.add(new BoolSetting.Builder()
        .name("on-any-title")
        .description("Triggers Brute Force mode whenever ANY title display pops up, regardless of text.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> humanWobble = sgBruteForce.add(new BoolSetting.Builder()
        .name("human-micro-wobble")
        .description("Adds tiny 1-2 pixel organic human hand micro-variations to prevent straight-line camera paths.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> bruteForceDuration = sgBruteForce.add(new IntSetting.Builder()
        .name("brute-force-duration-seconds")
        .description("Duration in seconds for brute force anti-AFK execution.")
        .defaultValue(7)
        .min(1)
        .sliderMax(20)
        .visible(() -> bruteForce.get() || onAnyTitle.get())
        .build()
    );

    private final Setting<Integer> actionCadenceSeconds = sgBruteForce.add(new IntSetting.Builder()
        .name("action-cadence-seconds")
        .description("Interval in seconds for jump, punch, and sneak actions during brute force.")
        .defaultValue(1)
        .min(1)
        .sliderMax(5)
        .visible(() -> bruteForce.get() || onAnyTitle.get())
        .build()
    );

    private final Setting<Integer> titleHoldSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("title-active-seconds")
        .description("How long to keep executing actions while title display remains active.")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .build()
    );

    // Sequential Camera Rotation Steps — each step is at most 180° so no degree wrapping needed
    public enum LookStep {
        LOOK_LEFT,          // originalYaw -> originalYaw - 180  (left 180°)
        CENTER_FROM_LEFT,   // originalYaw - 180 -> originalYaw  (right 180° back to center)
        LOOK_RIGHT,         // originalYaw -> originalYaw + 180  (right 180°)
        CENTER_FROM_RIGHT,  // originalYaw + 180 -> originalYaw  (left 180° back to center)
        LOOK_UP,            // originalPitch -> -75               (pitch up)
        LOOK_DOWN,          // -75 -> +75                         (pitch down 150°)
        RESET_PITCH;        // +75 -> originalPitch               (pitch back to origin)

        public boolean isHorizontal() {
            return this == LOOK_LEFT || this == CENTER_FROM_LEFT || this == LOOK_RIGHT || this == CENTER_FROM_RIGHT;
        }

        public LookStep next() {
            return switch (this) {
                case LOOK_LEFT -> CENTER_FROM_LEFT;
                case CENTER_FROM_LEFT -> LOOK_RIGHT;
                case LOOK_RIGHT -> CENTER_FROM_RIGHT;
                case CENTER_FROM_RIGHT -> LOOK_UP;
                case LOOK_UP -> LOOK_DOWN;
                case LOOK_DOWN -> RESET_PITCH;
                case RESET_PITCH -> LOOK_LEFT;
            };
        }
    }

    public record TitleActions(
        boolean sneak,
        boolean jump,
        boolean lookLeft,
        boolean lookRight,
        boolean lookUp,
        boolean lookDown
    ) {
        public boolean hasAnyAction() {
            return sneak || jump || lookLeft || lookRight || lookUp || lookDown;
        }

        public boolean hasLookAction() {
            return lookLeft || lookRight || lookUp || lookDown;
        }
    }

    public record StepTarget(float targetYaw, float targetPitch, float durationSeconds) {}

    public static String normalizeTitle(String raw) {
        if (raw == null) return "";
        String stripped = FORMATTING_CODE_PATTERN.matcher(raw).replaceAll("");
        return stripped.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).trim();
    }

    public static TitleActions parseTitleActions(String raw) {
        String normalized = normalizeTitle(raw);
        if (normalized.isEmpty()) {
            return new TitleActions(false, false, false, false, false, false);
        }
        return new TitleActions(
            normalized.contains("sneak"),
            normalized.contains("jump"),
            normalized.contains("look left"),
            normalized.contains("look right"),
            normalized.contains("look up"),
            normalized.contains("look down")
        );
    }

    public static StepTarget computeStepTarget(LookStep step, float originalYaw, float originalPitch) {
        float clampedOriginPitch = clampPitch(originalPitch);
        return switch (step) {
            case LOOK_LEFT -> new StepTarget(originalYaw - 180f, clampedOriginPitch, 1.2f);
            case CENTER_FROM_LEFT -> new StepTarget(originalYaw, clampedOriginPitch, 1.2f);
            case LOOK_RIGHT -> new StepTarget(originalYaw + 180f, clampedOriginPitch, 1.2f);
            case CENTER_FROM_RIGHT -> new StepTarget(originalYaw, clampedOriginPitch, 1.2f);
            case LOOK_UP -> new StepTarget(originalYaw, -75f, 0.8f);
            case LOOK_DOWN -> new StepTarget(originalYaw, 75f, 1.0f);
            case RESET_PITCH -> new StepTarget(originalYaw, clampedOriginPitch, 0.6f);
        };
    }

    public static float computeSmoothProgress(float elapsedSeconds, float durationSeconds) {
        if (durationSeconds <= 0f) return 1.0f;
        float rawProgress = Math.max(0.0f, Math.min(1.0f, elapsedSeconds / durationSeconds));
        return 0.5f * (1.0f - (float) Math.cos(PI_FLOAT * rawProgress));
    }

    public static float clampPitch(float pitch) {
        return Math.max(-90.0f, Math.min(90.0f, pitch));
    }

    public static float[] computeFrameAngles(
        LookStep step,
        float startYaw,
        float targetYaw,
        float startPitch,
        float targetPitch,
        float elapsedSeconds,
        float durationSeconds,
        boolean applyWobble
    ) {
        float rawProgress = durationSeconds > 0f
            ? Math.max(0.0f, Math.min(1.0f, elapsedSeconds / durationSeconds))
            : 1.0f;
        float smoothProgress = computeSmoothProgress(elapsedSeconds, durationSeconds);

        float currentYaw = startYaw + (targetYaw - startYaw) * smoothProgress;
        float currentPitch = startPitch + (targetPitch - startPitch) * smoothProgress;

        if (applyWobble && rawProgress > 0.05f && rawProgress < 0.95f) {
            float wobble = (float) Math.sin(elapsedSeconds * 22.0) * 0.14f;
            if (step.isHorizontal()) {
                currentPitch += wobble;
            } else {
                currentYaw += wobble;
            }
        }

        currentPitch = clampPitch(currentPitch);
        return new float[]{currentYaw, currentPitch};
    }

    public static class StateMachine {
        private double originalX;
        private double originalY;
        private double originalZ;
        private float originalYaw;
        private float originalPitch;
        private boolean savedState = false;

        private String activeTitleText = null;
        private int titleActiveTicks = 0;
        private TitleActions titleActions = new TitleActions(false, false, false, false, false, false);

        private int bruteForceTotalTicks = 0;
        private LookStep currentLookStep = LookStep.LOOK_LEFT;

        private float stepTimeElapsed = 0f;
        private float stepDurationSeconds = 1.2f;
        private int sneakPulseTicks = 0;

        private float startYaw = 0f;
        private float targetYaw = 0f;
        private float startPitch = 0f;
        private float targetPitch = 0f;

        private boolean shiftDown = false;
        private boolean jumpDown = false;

        public void reset() {
            activeTitleText = null;
            titleActiveTicks = 0;
            titleActions = new TitleActions(false, false, false, false, false, false);
            bruteForceTotalTicks = 0;
            stepTimeElapsed = 0f;
            sneakPulseTicks = 0;
            currentLookStep = LookStep.LOOK_LEFT;
            savedState = false;
            shiftDown = false;
            jumpDown = false;
        }

        public void saveStateIfNeeded(double x, double y, double z, float yaw, float pitch) {
            if (savedState) return;
            originalX = x;
            originalY = y;
            originalZ = z;
            originalYaw = yaw;
            originalPitch = clampPitch(pitch);
            savedState = true;
        }

        public void onClearTitles() {
            if (bruteForceTotalTicks <= 0) {
                titleActiveTicks = 0;
                activeTitleText = null;
            }
        }

        public enum TitleProcessOutcome {
            IGNORED_BLANK,
            IGNORED_BRUTE_FORCE_ACTIVE,
            IGNORED_NO_ACTION,
            STARTED_BRUTE_FORCE,
            STARTED_STANDARD_ACTION
        }

        public TitleProcessOutcome onTitle(
            String rawText,
            double currentX,
            double currentY,
            double currentZ,
            float currentYaw,
            float currentPitch,
            boolean bruteForceSetting,
            boolean onAnyTitleSetting,
            int bruteForceDurationSeconds,
            int titleHoldSeconds
        ) {
            String normalized = normalizeTitle(rawText);
            if (normalized.isEmpty()) {
                return TitleProcessOutcome.IGNORED_BLANK;
            }

            if (bruteForceTotalTicks > 0) {
                return TitleProcessOutcome.IGNORED_BRUTE_FORCE_ACTIVE;
            }

            TitleActions parsed = parseTitleActions(normalized);
            boolean triggerBruteForce = bruteForceSetting || onAnyTitleSetting;

            if (!triggerBruteForce && !parsed.hasAnyAction()) {
                return TitleProcessOutcome.IGNORED_NO_ACTION;
            }

            saveStateIfNeeded(currentX, currentY, currentZ, currentYaw, currentPitch);
            activeTitleText = normalized;
            titleActions = parsed;

            if (triggerBruteForce) {
                titleActiveTicks = 0;
                bruteForceTotalTicks = Math.max(1, bruteForceDurationSeconds) * 20;
                prepareLookStep(LookStep.LOOK_LEFT, currentYaw, currentPitch);
                return TitleProcessOutcome.STARTED_BRUTE_FORCE;
            }

            titleActiveTicks = Math.max(1, titleHoldSeconds) * 20;
            initStandardAction(currentYaw, currentPitch);
            return TitleProcessOutcome.STARTED_STANDARD_ACTION;
        }

        public void prepareLookStep(LookStep step, float currentYaw, float currentPitch) {
            currentLookStep = step;
            stepTimeElapsed = 0f;
            startYaw = currentYaw;
            startPitch = clampPitch(currentPitch);

            StepTarget target = computeStepTarget(step, originalYaw, originalPitch);
            stepDurationSeconds = target.durationSeconds();
            targetYaw = target.targetYaw();
            targetPitch = target.targetPitch();
        }

        private void initStandardAction(float currentYaw, float currentPitch) {
            shiftDown = titleActions.sneak();
            jumpDown = titleActions.jump();

            startYaw = currentYaw;
            startPitch = clampPitch(currentPitch);
            targetYaw = originalYaw;
            targetPitch = originalPitch;

            if (titleActions.lookLeft()) {
                targetYaw = originalYaw - 180f;
                stepTimeElapsed = 0f;
                stepDurationSeconds = 0.8f;
            } else if (titleActions.lookRight()) {
                targetYaw = originalYaw + 180f;
                stepTimeElapsed = 0f;
                stepDurationSeconds = 0.8f;
            }

            if (titleActions.lookUp()) {
                targetPitch = -75f;
                stepTimeElapsed = 0f;
                stepDurationSeconds = 0.8f;
            } else if (titleActions.lookDown()) {
                targetPitch = 75f;
                stepTimeElapsed = 0f;
                stepDurationSeconds = 0.8f;
            }
        }

        public float[] onRenderFrame(float frameTimeSeconds, float currentYaw, float currentPitch, boolean applyWobble) {
            if (bruteForceTotalTicks <= 0) {
                return new float[]{currentYaw, clampPitch(currentPitch)};
            }
            if (frameTimeSeconds > 0f) {
                stepTimeElapsed += frameTimeSeconds;
            }

            float[] angles = computeFrameAngles(
                currentLookStep,
                startYaw,
                targetYaw,
                startPitch,
                targetPitch,
                stepTimeElapsed,
                stepDurationSeconds,
                applyWobble
            );

            if (stepTimeElapsed >= stepDurationSeconds) {
                prepareLookStep(currentLookStep.next(), angles[0], angles[1]);
            }

            return angles;
        }

        public record TickResult(
            boolean triggerJumpAndPunch,
            boolean updateStandardLook,
            float newYaw,
            float newPitch,
            boolean restoreState,
            boolean restoredFromBruteForce
        ) {}

        public TickResult onTick(int actionCadenceSeconds, float currentYaw, float currentPitch) {
            if (titleActiveTicks > 0) {
                titleActiveTicks--;
            }

            // 1. Brute Force Mode Logic (Tick Cadences)
            if (bruteForceTotalTicks > 0) {
                int cadenceTicks = Math.max(1, actionCadenceSeconds) * 20;
                boolean triggerCadence = (bruteForceTotalTicks % cadenceTicks == 0);

                bruteForceTotalTicks--;

                if (triggerCadence) {
                    sneakPulseTicks = 8;
                    shiftDown = true;
                } else if (sneakPulseTicks > 0) {
                    sneakPulseTicks--;
                    if (sneakPulseTicks == 0) {
                        shiftDown = false;
                    }
                }

                if (bruteForceTotalTicks == 0) {
                    sneakPulseTicks = 0;
                    shiftDown = false;
                    jumpDown = false;
                    titleActiveTicks = 0;
                    activeTitleText = null;
                    return new TickResult(triggerCadence, false, originalYaw, originalPitch, savedState, true);
                }

                return new TickResult(triggerCadence, false, currentYaw, currentPitch, false, false);
            }

            // 2. Standard Title Actions
            if (titleActiveTicks > 0 && activeTitleText != null) {
                shiftDown = titleActions.sneak();
                jumpDown = titleActions.jump();

                if (titleActions.hasLookAction()) {
                    stepTimeElapsed += 0.05f;
                    float smoothProgress = computeSmoothProgress(stepTimeElapsed, stepDurationSeconds);
                    float nextYaw = startYaw + (targetYaw - startYaw) * smoothProgress;
                    float nextPitch = clampPitch(startPitch + (targetPitch - startPitch) * smoothProgress);
                    return new TickResult(false, true, nextYaw, nextPitch, false, false);
                }
                return new TickResult(false, false, currentYaw, currentPitch, false, false);
            }

            // 3. Title expired -> restore state
            if (savedState && titleActiveTicks == 0) {
                shiftDown = false;
                jumpDown = false;
                activeTitleText = null;
                return new TickResult(false, false, originalYaw, originalPitch, true, false);
            }

            return new TickResult(false, false, currentYaw, currentPitch, false, false);
        }

        public void markStateRestored() {
            savedState = false;
            shiftDown = false;
            jumpDown = false;
        }

        public double getOriginalX() { return originalX; }
        public double getOriginalY() { return originalY; }
        public double getOriginalZ() { return originalZ; }
        public float getOriginalYaw() { return originalYaw; }
        public float getOriginalPitch() { return originalPitch; }
        public boolean isSavedState() { return savedState; }
        public String getActiveTitleText() { return activeTitleText; }
        public int getTitleActiveTicks() { return titleActiveTicks; }
        public TitleActions getTitleActions() { return titleActions; }
        public int getBruteForceTotalTicks() { return bruteForceTotalTicks; }
        public LookStep getCurrentLookStep() { return currentLookStep; }
        public float getStepTimeElapsed() { return stepTimeElapsed; }
        public float getStepDurationSeconds() { return stepDurationSeconds; }
        public int getSneakPulseTicks() { return sneakPulseTicks; }
        public float getStartYaw() { return startYaw; }
        public float getTargetYaw() { return targetYaw; }
        public float getStartPitch() { return startPitch; }
        public float getTargetPitch() { return targetPitch; }
        public boolean isShiftDown() { return shiftDown; }
        public boolean isJumpDown() { return jumpDown; }
    }

    private final StateMachine state = new StateMachine();
    private final Random random = new Random();

    public TitleAntiAFK() {
        super(AddonMain.ADDON_CATEGORY, "title-anti-afk", "Reads on-screen title displays, performing 144+ FPS humanized camera motion, 1-second action cadences, and state restoration.");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        restoreStateIfSaved();
        resetState();
    }

    private void restoreStateIfSaved() {
        if (!Utils.canUpdate() || !state.isSavedState()) return;
        mc.player.teleportTo(state.getOriginalX(), state.getOriginalY(), state.getOriginalZ());
        mc.player.setYRot(state.getOriginalYaw());
        mc.player.setXRot(state.getOriginalPitch());
        mc.options.keyShift.setDown(false);
        mc.options.keyJump.setDown(false);
        state.markStateRestored();
    }

    private void resetState() {
        state.reset();
        if (mc != null && mc.options != null) {
            mc.options.keyShift.setDown(false);
            mc.options.keyJump.setDown(false);
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!Utils.canUpdate()) return;

        Component comp = null;
        if (event.packet instanceof ClientboundSetTitleTextPacket p) {
            comp = p.text();
        } else if (event.packet instanceof ClientboundSetSubtitleTextPacket p) {
            comp = p.text();
        } else if (event.packet instanceof ClientboundClearTitlesPacket) {
            state.onClearTitles();
            return;
        }

        if (comp != null) {
            String text = normalizeTitle(comp.getString());
            if (!text.isEmpty()) {
                processTitleText(text);
            }
        }
    }

    public void processTitleText(String text) {
        if (text == null || text.isBlank()) return;
        if (!Utils.canUpdate()) return;

        Vec3 pos = mc.player.position();
        StateMachine.TitleProcessOutcome outcome = state.onTitle(
            text,
            pos.x,
            pos.y,
            pos.z,
            mc.player.getYRot(),
            mc.player.getXRot(),
            bruteForce.get(),
            onAnyTitle.get(),
            bruteForceDuration.get(),
            titleHoldSeconds.get()
        );

        String normalized = normalizeTitle(text);
        switch (outcome) {
            case IGNORED_BRUTE_FORCE_ACTIVE ->
                info("Brute Force active: Ignored secondary title \"" + normalized + "\"");
            case STARTED_BRUTE_FORCE ->
                info("Brute force Anti-AFK triggered for title: \"" + normalized + "\"");
            case STARTED_STANDARD_ACTION -> {
                info("Title detected: \"" + normalized + "\" (Executing while active for " + titleHoldSeconds.get() + "s)");
                mc.options.keyShift.setDown(state.isShiftDown());
                mc.options.keyJump.setDown(state.isJumpDown());
                if (state.getTitleActions().jump() && mc.player.onGround()) {
                    mc.player.jumpFromGround();
                }
            }
            default -> {}
        }
    }

    private void performPunch() {
        if (mc.player == null) return;
        mc.player.swing(InteractionHand.MAIN_HAND);
        if (mc.gameMode != null && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            EntityHitResult hit = (EntityHitResult) mc.hitResult;
            mc.gameMode.attack(mc.player, hit.getEntity());
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (state.getBruteForceTotalTicks() <= 0 || !Utils.canUpdate()) return;

        float[] angles = state.onRenderFrame(
            (float) event.frameTime,
            mc.player.getYRot(),
            mc.player.getXRot(),
            humanWobble.get()
        );

        mc.player.setYRot(angles[0]);
        mc.player.setXRot(angles[1]);
        mc.player.yRotO = angles[0];
        mc.player.xRotO = angles[1];
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        boolean wasBruteForce = state.getBruteForceTotalTicks() > 0;
        StateMachine.TickResult result = state.onTick(
            actionCadenceSeconds.get(),
            mc.player.getYRot(),
            mc.player.getXRot()
        );

        if (wasBruteForce) {
            if (result.triggerJumpAndPunch()) {
                if (mc.player.onGround()) {
                    mc.player.jumpFromGround();
                }
                performPunch();
            }
            mc.options.keyShift.setDown(state.isShiftDown());

            if (result.restoreState()) {
                restoreStateIfSaved();
                info("Brute force sequence finished. Restored exact position and pitch/yaw.");
            }
            return;
        }

        if (state.getTitleActiveTicks() > 0 && state.getActiveTitleText() != null) {
            mc.options.keyShift.setDown(state.isShiftDown());
            if (state.getTitleActions().jump() && mc.player.onGround() && random.nextInt(5) == 0) {
                mc.player.jumpFromGround();
            }
            if (result.updateStandardLook()) {
                mc.player.setYRot(result.newYaw());
                mc.player.setXRot(result.newPitch());
                mc.player.yRotO = result.newYaw();
                mc.player.xRotO = result.newPitch();
            }
            return;
        }

        if (result.restoreState()) {
            restoreStateIfSaved();
            info("Title display ended. Restored position & rotation.");
        }
    }
}
