package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.CompletePredictionEvent;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.UncertaintyHandler;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicInteger;

@CheckData(name = "Simulation", stableKey = "grim.prediction.simulation", description = "Moved differently than predicted movement simulation", decay = 0.02)
public class OffsetHandler extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("{offset}");

    private static final AtomicInteger flags = new AtomicInteger(0);

    public static int getLastFlagId() {
        return flags.get() & 255;
    }

    // Config
    private double setbackDecayMultiplier;
    private double threshold;
    private double immediateSetbackThreshold;
    private double maxAdvantage;
    private double maxCeiling;
    private double setbackViolationThreshold;
    // Current advantage gained
    private double advantageGained = 0;
    private static final CompletePredictionEvent.Channel COMPLETE_CHANNEL = GrimAPI.INSTANCE.getEventBus().get(CompletePredictionEvent.class);

    public OffsetHandler(GrimPlayer player) {
        super(player);
    }

    @Override
    public String getAlertReason() {
        UncertaintyHandler u = player.uncertaintyHandler;
        StringBuilder reason = new StringBuilder();

        String branch = describeBranch(player.predictedVelocity);
        if (!branch.isEmpty()) reason.append(branch).append("; ");

        reason.append(String.format(java.util.Locale.ROOT, "missed x%+.4f y%+.4f z%+.4f", u.offsetX, u.offsetY, u.offsetZ));

        String reduction = u.describeReduction();
        if (!reduction.isEmpty()) reason.append(", offset cut ").append(reduction);

        String box = u.describeBox();
        reason.append("; slack ").append(box.isEmpty() ? "none, base size" : box);

        String sources = u.describeSources(4);
        if (!sources.isEmpty()) reason.append(" from ").append(sources);

        return reason.toString();
    }

    // Climbable and StuckMultiplier can mark unchanged vectors; use the stored multiplier for stuck speed.
    public static String describeBranch(@Nullable VectorData data) {
        StringBuilder branch = new StringBuilder();
        if (data != null && data.stuckSpeedMultiplier.getIndex() != StuckSpeed.NONE.getIndex()) {
            branch.append(data.stuckSpeedMultiplier.getName());
        }
        for (VectorData step = data; step != null; step = step.lastVector) {
            switch (step.vectorType) {
                // Only a root Climbable records the lastWasClimbing candidate.
                case Climbable -> {
                    if (step.lastVector != null) continue;
                    if (branch.length() > 0) branch.insert(0, " + ");
                    branch.insert(0, step.vectorType);
                }
                case Knockback, FirstBreadKnockback, Explosion, FirstBreadExplosion, Trident, Jump,
                     Swimhop, SwimmingSpace, SlimePistonBounce, AttackSlow, ZeroPointZeroThree,
                     Flip_Use_Item, BlockPush -> {
                    if (branch.length() > 0) branch.insert(0, " + ");
                    branch.insert(0, step.vectorType);
                }
                default -> {
                }
            }
        }
        return branch.toString();
    }

    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!predictionComplete.isChecked()) return;

        double offset = predictionComplete.getOffset();

        if (COMPLETE_CHANNEL.fire(player, this, offset)) return;

        if ((offset >= threshold || offset >= immediateSetbackThreshold)) {
            advantageGained += offset;
            giveOffsetLenienceNextTick(offset);

            synchronized (flags) {
                int flagId = (flags.get() & 255) + 1; // 1-256 as possible values

                if (flag(V.write(verbose()).f64(offset), () -> humanFormattedOffset(offset) + " /gl " + flagId)) {
                    flags.incrementAndGet();
                    predictionComplete.setIdentifier(flagId);

                    if ((advantageGained >= maxAdvantage || offset >= immediateSetbackThreshold)
                            && !isNoSetbackPermission()
                            && violations >= setbackViolationThreshold) {
                        player.getSetbackTeleportUtil().executeViolationSetback();
                    }
                }
            }

            advantageGained = Math.min(advantageGained, maxCeiling);
        } else {
            advantageGained *= setbackDecayMultiplier;
        }

        removeOffsetLenience();
    }

    public static String humanFormattedOffset(double offset) {
        String humanFormattedOffset;
        if (offset < 0.001) { // 1.129E-3
            humanFormattedOffset = String.format("%.4E", offset);
            // Squeeze out an extra digit here by E-03 to E-3
            humanFormattedOffset = humanFormattedOffset.replace("E-0", "E-");
        } else {
            // 0.00112945678 -> .001129
            humanFormattedOffset = String.format("%6f", offset);
            // I like the leading zero, but removing it lets us add another digit to the end
            humanFormattedOffset = humanFormattedOffset.replace("0.", ".");
        }
        return humanFormattedOffset;
    }

    private void giveOffsetLenienceNextTick(double offset) {
        // Don't let players carry more than 1 offset into the next tick
        // (I was seeing cheats try to carry 1,000,000,000 offset into the next tick!)
        //
        // This value so that setting back with high ping doesn't allow players to gather high client velocity
        double minimizedOffset = Math.min(offset, 1);

        // Normalize offsets
        player.uncertaintyHandler.lastHorizontalOffset = minimizedOffset;
        player.uncertaintyHandler.lastVerticalOffset = minimizedOffset;
    }

    private void removeOffsetLenience() {
        player.uncertaintyHandler.lastHorizontalOffset = 0;
        player.uncertaintyHandler.lastVerticalOffset = 0;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        setbackDecayMultiplier = config.getDoubleElse("Simulation.setback-decay-multiplier", 0.999);
        threshold = config.getDoubleElse("Simulation.threshold", 0.001);
        immediateSetbackThreshold = config.getDoubleElse("Simulation.immediate-setback-threshold", 0.1);
        maxAdvantage = config.getDoubleElse("Simulation.max-advantage", 1);
        maxCeiling = config.getDoubleElse("Simulation.max-ceiling", 4);
        setbackViolationThreshold = config.getDoubleElse("Simulation.setback-violation-threshold", 1);
        if (maxAdvantage == -1) maxAdvantage = Double.MAX_VALUE;
        if (immediateSetbackThreshold == -1) immediateSetbackThreshold = Double.MAX_VALUE;
    }

    public boolean doesOffsetFlag(double offset) {
        return offset >= threshold;
    }
}
