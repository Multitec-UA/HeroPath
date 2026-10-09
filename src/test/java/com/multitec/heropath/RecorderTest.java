package com.multitec.heropath;

import org.junit.jupiter.api.Test;

import static com.multitec.heropath.Sample.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class RecorderTest {

    private static Sample p(long t, String w, double x, double z) {
        return new Sample(t, POINT, w, x, 64, z);
    }

    @Test
    void firstPositionStartsALine() {
        Recorder r = new Recorder(2, 64, 60_000);
        assertEquals(JUMP, r.offer(p(0, "w", 0, 0)).kind());
    }

    @Test
    void standingStillWritesNothingUntilKeepAlive() {
        Recorder r = new Recorder(2, 64, 60_000);
        r.offer(p(0, "w", 0, 0));
        assertNull(r.offer(p(5_000, "w", 0.5, 0)));
        assertNull(r.offer(p(55_000, "w", 1, 0)));
        Sample kept = r.offer(p(60_000, "w", 1, 0));
        assertNotNull(kept, "a minute in the same place is still written once");
        assertEquals(POINT, kept.kind());
    }

    @Test
    void walkingIsWritten() {
        Recorder r = new Recorder(2, 64, 60_000);
        r.offer(p(0, "w", 0, 0));
        assertEquals(POINT, r.offer(p(5_000, "w", 5, 0)).kind());
        assertNull(r.offer(p(10_000, "w", 6, 0)), "1 block from the last kept point is not enough");
        assertEquals(POINT, r.offer(p(15_000, "w", 7.5, 0)).kind());
    }

    @Test
    void aMoveTooLongToWalkIsAJump() {
        Recorder r = new Recorder(2, 64, 60_000);
        r.offer(p(0, "w", 0, 0));
        assertEquals(JUMP, r.offer(p(5_000, "w", 500, 0)).kind());
        assertEquals(POINT, r.offer(p(10_000, "w", 505, 0)).kind(), "and walking resumes from there");
    }

    @Test
    void anotherWorldIsAlwaysAJump() {
        Recorder r = new Recorder(2, 64, 60_000);
        r.offer(p(0, "w", 0, 0));
        assertEquals(JUMP, r.offer(p(5_000, "w_nether", 1, 0)).kind());
    }

    @Test
    void eventsAreAlwaysWritten() {
        Recorder r = new Recorder(2, 64, 60_000);
        r.offer(p(0, "w", 0, 0));
        Sample death = new Sample(1_000, DEATH, "w", 0, 64, 0);
        assertSame(death, r.offer(death));
    }

    @Test
    void anAdvancementDoesNotMoveTheReferencePoint() {
        Recorder r = new Recorder(2, 64, 60_000);
        r.offer(p(0, "w", 0, 0));
        Sample adv = new Sample(1_000, ADVANCEMENT, "w", 1, 64, 0, "task|Stone Age");
        assertSame(adv, r.offer(adv));
        assertNull(r.offer(p(2_000, "w", 1, 0)), "1 block from the last POSITION, not from the toast");
    }
}
