package ac.grim.grimac.utils.data;

import ac.grim.grimac.testutil.GrimPlayerFixture;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TrackerDataTest {
    @BeforeAll
    static void initialize() {
        GrimPlayerFixture.initializePacketEvents();
    }

    private TrackerData tracker() {
        return new TrackerData(0, 0, 0, 0, 0, EntityTypes.PLAYER, 10);
    }

    @Test
    void stationaryEntityDoesNotBecomeStale() {
        assertFalse(tracker().isPositionUpdateStale(10, 10000, 1000));
    }

    @Test
    void unconfirmedMovementHasAnAgeBudget() {
        TrackerData tracker = tracker();
        tracker.recordPositionUpdate(11, 10, 100);
        assertFalse(tracker.isPositionUpdateStale(10, 1100, 1000));
        assertTrue(tracker.isPositionUpdateStale(10, 1101, 1000));
    }

    @Test
    void ageBudgetSurvivesNanoTimeWrapping() {
        TrackerData tracker = tracker();
        long sent = Long.MAX_VALUE - 500;
        tracker.recordPositionUpdate(11, 10, sent);
        assertFalse(tracker.isPositionUpdateStale(10, sent + 1000, 1000));
        assertTrue(tracker.isPositionUpdateStale(10, sent + 1001, 1000));
    }

    @Test
    void continuousMovementDoesNotRenewTheBudget() {
        TrackerData tracker = tracker();
        tracker.recordPositionUpdate(11, 10, 100);
        tracker.recordPositionUpdate(12, 10, 900);
        assertTrue(tracker.isPositionUpdateStale(10, 1200, 1000));
    }

    @Test
    void partialReplayUsesSendTimeRatherThanAcknowledgementTime() {
        TrackerData tracker = tracker();
        tracker.recordPositionUpdate(11, 10, 100);
        tracker.recordPositionUpdate(12, 10, 200);
        tracker.recordPositionUpdate(13, 10, 300);
        tracker.acknowledgePositionUpdate(11);
        tracker.acknowledgePositionUpdate(12);
        assertTrue(tracker.isPositionUpdateStale(12, 1400, 1000));
        assertFalse(tracker.isPositionUpdateStale(12, 1200, 1000));
    }

    @Test
    void acknowledgingAnOldUpdateDoesNotAgeARecentPendingUpdate() {
        TrackerData tracker = tracker();
        tracker.recordPositionUpdate(11, 10, 100);
        tracker.recordPositionUpdate(12, 10, 10000);
        tracker.acknowledgePositionUpdate(11);
        assertFalse(tracker.isPositionUpdateStale(11, 10100, 1000));
    }

    @Test
    void acknowledgementOfLatestMovementEndsThePendingWindow() {
        TrackerData tracker = tracker();
        tracker.recordPositionUpdate(11, 10, 100);
        tracker.acknowledgePositionUpdate(11);
        assertFalse(tracker.isPositionUpdateStale(10, 10000, 1000));
    }

    @Test
    void receivedTransactionAlreadyCoveringMovementIsNotStale() {
        TrackerData tracker = tracker();
        tracker.recordPositionUpdate(11, 10, 100);
        assertFalse(tracker.isPositionUpdateStale(11, 10000, 1000));
    }

    @Test
    void newMovementAfterAcknowledgementGetsANewBudget() {
        TrackerData tracker = tracker();
        tracker.recordPositionUpdate(11, 10, 100);
        tracker.acknowledgePositionUpdate(11);
        tracker.recordPositionUpdate(12, 11, 5000);
        assertFalse(tracker.isPositionUpdateStale(11, 5500, 1000));
        assertTrue(tracker.isPositionUpdateStale(11, 6100, 1000));
    }

    @Test
    void newMovementAlsoResetsWhenReceivedCounterAlreadyAdvanced() {
        TrackerData tracker = tracker();
        tracker.recordPositionUpdate(11, 10, 100);
        tracker.recordPositionUpdate(12, 11, 5000);
        assertFalse(tracker.isPositionUpdateStale(11, 5500, 1000));
    }

    @Test
    void wrappedBufferTracksTheActualOldestPendingUpdate() {
        TrackerData tracker = tracker();
        for (int transaction = 11; transaction < 10000; transaction++) {
            long sent = transaction * 100L;
            tracker.recordPositionUpdate(transaction, transaction - 3, sent);
            if (transaction < 13) continue;
            assertFalse(tracker.isPositionUpdateStale(transaction - 3, sent + 50, 250));
            assertTrue(tracker.isPositionUpdateStale(transaction - 3, sent + 51, 250));
        }
    }

    @Test
    void overflowKeepsMemoryBoundedAndDoesNotHideACompleteHold() {
        TrackerData tracker = tracker();
        for (int transaction = 11; transaction <= 10000; transaction++) {
            tracker.recordPositionUpdate(transaction, 10, transaction * 100L);
        }
        assertTrue(tracker.getPendingPositionSize() <= 64);
        assertTrue(tracker.getPendingPositions().length <= 128);
        assertTrue(tracker.isPositionUpdateStale(10, 1000100, 1000));
        tracker.acknowledgePositionUpdate(9999);
        assertFalse(tracker.isPositionUpdateStale(9999, 1000100, 1000));
        tracker.acknowledgePositionUpdate(10000);
        assertEquals(0, tracker.getPendingPositionSize());
    }

    @Test
    void randomizedFifoNeverRejectsEarlierThanTheExactHistory() {
        record Update(int transaction, long time) {}
        ArrayDeque<Update> reference = new ArrayDeque<>();
        TrackerData tracker = tracker();
        Random random = new Random(0xBAC7);
        int transaction = 10;
        int received = 10;
        long now = 0;
        for (int i = 0; i < 100000; i++) {
            now += random.nextInt(100);
            if (reference.isEmpty() || random.nextInt(4) != 0) {
                reference.add(new Update(++transaction, now));
                tracker.recordPositionUpdate(transaction, received, now);
            } else {
                int count = 1 + random.nextInt(reference.size());
                while (count-- > 0) received = reference.remove().transaction();
                tracker.acknowledgePositionUpdate(received);
            }
            boolean exact = !reference.isEmpty() && now - reference.peek().time() > 1000;
            boolean limited = tracker.isPositionUpdateStale(received, now, 1000);
            if (limited && !exact) fail("Early rejection at event " + i);
            assertTrue(tracker.getPendingPositionSize() <= 64);
        }
    }
}
