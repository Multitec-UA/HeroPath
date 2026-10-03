package com.multitec.heropath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SampleTest {

    @Test
    void roundTrips() {
        Sample s = new Sample(1_791_000_000_000L, Sample.Kind.DEATH, "multitecWorld", -1734.26, 63.0, 12.04);
        Sample back = Sample.parse(s.serialize());
        assertNotNull(back);
        assertEquals(s.time(), back.time());
        assertEquals(s.kind(), back.kind());
        assertEquals("multitecWorld", back.world());
        assertEquals(-1734.3, back.x(), 1e-9, "one decimal is kept");
        assertEquals(12.0, back.z(), 1e-9);
    }

    @Test
    void aTornLineIsSkippedNotFatal() {
        assertNull(Sample.parse("1791000000000\tP\tmultitecWorld\t-17"));
        assertNull(Sample.parse(""));
        assertNull(Sample.parse("x\tP\tw\t1\t2\t3"));
        assertNull(Sample.parse("1\tZ\tw\t1\t2\t3"), "unknown kind");
    }
}
