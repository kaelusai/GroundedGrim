package ac.grim.grimac.utils.latency;

import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.protocol.player.GameMode;

import java.util.function.LongSupplier;

public class FreezeDetector {

    private static final int RTT_BASELINE_SAMPLES = 20;
    private static final long JOIN_GRACE_MS = 5000;

    private final GrimPlayer player;
    private final LongSupplier clock;

    private volatile long lastFlyingPacketMs;
    private volatile long gapStartMs = 0;
    private volatile boolean inGap = false;
    private volatile int attackPacketsDuringGap = 0;
    private volatile int recoveryFlyingsInGap = 0;
    private volatile long lastUnfreezeMs = 0;
    private volatile int consecutiveNormalFlyings = 0;
    private volatile boolean sawInboundDuringGap = false;
    private volatile int transSentAtGapStart = 0;

    private final long[] rttSamples = new long[RTT_BASELINE_SAMPLES];
    private volatile int rttIndex = 0;
    private volatile int rttFilled = 0;

    public int gapThresholdMs = 1500;
    public int gapStrongThresholdMs = 2000;
    public int rttSpikeThresholdMs = 300;
    public int recoveryThreshold = 6;
    public int scoreCancel = 3;
    public int scoreFlag = 5;
    public int recoveryFlyingsRequired = 5;
    public int postUnfreezeCooldownMs = 1500;

    public FreezeDetector(GrimPlayer player) {
        this(player, System::currentTimeMillis);
    }

    FreezeDetector(GrimPlayer player, LongSupplier clock) {
        this.player = player;
        this.clock = clock;
        lastFlyingPacketMs = clock.getAsLong();
    }

    public synchronized void onFlyingPacket() {
        if (isExempt()) {
            reset();
            return;
        }

        long now = clock.getAsLong();
        long gap = now - lastFlyingPacketMs;

        if (gap > gapThresholdMs && !inGap) {
            inGap = true;
            gapStartMs = lastFlyingPacketMs;
            recoveryFlyingsInGap = 0;
            onGapEntered();
        }

        if (inGap) recoveryFlyingsInGap++;

        // Stationary clients can send their position heartbeat only once per second.
        if (gap <= gapThresholdMs) {
            consecutiveNormalFlyings++;
            if (consecutiveNormalFlyings >= recoveryFlyingsRequired && inGap) {
                lastUnfreezeMs = now;
                inGap = false;
                attackPacketsDuringGap = 0;
                recoveryFlyingsInGap = 0;
                sawInboundDuringGap = false;
            }
        } else {
            consecutiveNormalFlyings = 0;
        }

        sawInboundDuringGap = false;
        transSentAtGapStart = player.lastTransactionSent.get();
        lastFlyingPacketMs = now;
    }

    public synchronized void onTransactionReceived(long rttMs) {
        rttSamples[rttIndex] = rttMs;
        rttIndex = (rttIndex + 1) % RTT_BASELINE_SAMPLES;
        if (rttFilled < RTT_BASELINE_SAMPLES) rttFilled++;

        // Only a reply to a transaction sent after gap detection can establish ongoing traffic.
        if (inGap
                && player.lastTransactionReceived.get() > transSentAtGapStart
                && clock.getAsLong() - lastFlyingPacketMs > gapThresholdMs) {
            sawInboundDuringGap = true;
        }
    }

    public synchronized void onAttackPacket() {
        if (isExempt()) {
            reset();
            return;
        }
        long now = clock.getAsLong();
        long gap = now - lastFlyingPacketMs;
        if (!inGap && gap > gapThresholdMs) {
            inGap = true;
            gapStartMs = lastFlyingPacketMs;
            onGapEntered();
        }
        if (inGap) attackPacketsDuringGap++;
    }

    private void onGapEntered() {
        sawInboundDuringGap = false;
        transSentAtGapStart = player.lastTransactionSent.get();
    }

    public boolean isFrozen() {
        return getScore() >= scoreCancel;
    }

    public synchronized boolean shouldFlag() {
        // Liveness proof counts only while movement is still withheld.
        return getScore() >= scoreFlag && sawInboundDuringGap
                && clock.getAsLong() - lastFlyingPacketMs > gapThresholdMs;
    }

    public synchronized int getScore() {
        if (isExempt()) return 0;

        int score = 0;
        long now = clock.getAsLong();
        long currentGap = inGap ? now - gapStartMs : Math.max(0, now - lastFlyingPacketMs);

        if (currentGap > gapStrongThresholdMs) score += 2;
        else if (currentGap > gapThresholdMs) score += 1;

        if (rttSpike()) score += 1;
        if (inGap && recoveryFlyingsInGap >= recoveryThreshold) score += 1;
        if (attackPacketsDuringGap > 0) score += 2;

        if (lastUnfreezeMs > 0 && now - lastUnfreezeMs < postUnfreezeCooldownMs) {
            score = Math.max(score, 1);
        }

        return score;
    }

    public boolean recentlyUnfroze(int ms) {
        return lastUnfreezeMs > 0 && clock.getAsLong() - lastUnfreezeMs < ms;
    }

    public long getCurrentGapMs() {
        long now = clock.getAsLong();
        return inGap ? now - gapStartMs : Math.max(0, now - lastFlyingPacketMs);
    }

    public int getAttacksDuringGap() {
        return attackPacketsDuringGap;
    }

    public synchronized void reset() {
        inGap = false;
        gapStartMs = 0;
        attackPacketsDuringGap = 0;
        recoveryFlyingsInGap = 0;
        consecutiveNormalFlyings = 0;
        sawInboundDuringGap = false;
        lastFlyingPacketMs = clock.getAsLong();
    }

    public long getLastFlyingMs() {
        return lastFlyingPacketMs;
    }

    private boolean rttSpike() {
        if (rttFilled < 5) return false;
        long sum = 0;
        long latest = rttSamples[(rttIndex - 1 + RTT_BASELINE_SAMPLES) % RTT_BASELINE_SAMPLES];
        for (int i = 0; i < rttFilled; i++) sum += rttSamples[i];
        if (rttFilled > 1) sum -= latest;
        long avg = rttFilled > 1 ? sum / (rttFilled - 1) : sum;
        return latest > avg + rttSpikeThresholdMs;
    }

    private boolean isExempt() {
        if (player.gamemode == GameMode.SPECTATOR) return true;
        if (clock.getAsLong() - player.joinTime < JOIN_GRACE_MS) return true;
        if (player.packetStateData.lastPacketWasTeleport) return true;
        if (player.disableGrim) return true;
        if (player.inVehicle()) return true;
        if (player.isInBed) return true;
        if (player.compensatedEntities.self.isDead) return true;
        if (!player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport) return true;
        return false;
    }
}
