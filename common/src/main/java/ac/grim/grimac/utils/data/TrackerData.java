package ac.grim.grimac.utils.data;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import lombok.Data;

@Data
public class TrackerData {
    private static final int MAX_PENDING_POSITIONS = 64;
    private double x, y, z;
    private float xRot, yRot;
    private final EntityType entityType;
    private int lastTransactionHung;
    private int legacyPointEightMountedUpon;
    private long[] pendingPositions;
    private int pendingPositionHead;
    private int pendingPositionSize;

    public TrackerData(double x, double y, double z, float xRot, float yRot, EntityType entityType, int lastTransactionHung) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.xRot = xRot;
        this.yRot = yRot;
        this.entityType = entityType;
        this.lastTransactionHung = lastTransactionHung;
    }

    public void recordPositionUpdate(int transaction, int lastReceived, long sentAtNanos) {
        acknowledgePositionUpdate(lastReceived);
        if (transaction <= lastReceived) return;
        if (pendingPositions == null) pendingPositions = new long[8];
        int capacity = pendingPositions.length / 2;
        if (pendingPositionSize == capacity) {
            if (capacity < MAX_PENDING_POSITIONS) {
                long[] expanded = new long[pendingPositions.length * 2];
                for (int i = 0; i < pendingPositionSize; i++) {
                    int from = ((pendingPositionHead + i) & (capacity - 1)) * 2;
                    expanded[i * 2] = pendingPositions[from];
                    expanded[i * 2 + 1] = pendingPositions[from + 1];
                }
                pendingPositions = expanded;
                pendingPositionHead = 0;
                capacity *= 2;
            } else {
                // Preserve the oldest update; coalesce overflow at the newest send time to avoid early rejection after a partial ACK.
                int newest = ((pendingPositionHead + pendingPositionSize - 1) & (capacity - 1)) * 2;
                pendingPositions[newest] = transaction;
                pendingPositions[newest + 1] = sentAtNanos;
                return;
            }
        }
        int index = ((pendingPositionHead + pendingPositionSize) & (capacity - 1)) * 2;
        pendingPositions[index] = transaction;
        pendingPositions[index + 1] = sentAtNanos;
        pendingPositionSize++;
    }

    public void acknowledgePositionUpdate(int transaction) {
        while (pendingPositionSize > 0 && pendingPositions[pendingPositionHead * 2] <= transaction) {
            pendingPositionHead = (pendingPositionHead + 1) & (pendingPositions.length / 2 - 1);
            pendingPositionSize--;
        }
        if (pendingPositionSize == 0) pendingPositionHead = 0;
    }

    public boolean isPositionUpdateStale(int lastReceived, long nowNanos, long maxAgeNanos) {
        acknowledgePositionUpdate(lastReceived);
        return pendingPositionSize > 0 && nowNanos - pendingPositions[pendingPositionHead * 2 + 1] > maxAgeNanos;
    }
}
