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

    @Test
    void anEventCarriesItsDetailAndOldLinesStillParse() {
        Sample d = new Sample(1L, Sample.Kind.DEATH, "w", 1, 2, 3, "ana was slain by\tZombie\n");
        Sample back = Sample.parse(d.serialize());
        assertNotNull(back);
        assertEquals("ana was slain by Zombie", back.detail(), "tabs and newlines cannot break the file");
        Sample old = Sample.parse("1791000000000\tD\tw\t1.0\t2.0\t3.0");
        assertNotNull(old, "a line written by 2.0.0 (six columns) still reads");
        assertNull(old.detail());
        assertEquals(6, new Sample(1L, Sample.Kind.POINT, "w", 0, 0, 0).serialize().split("\t").length,
                "positions do not grow a seventh column");
        assertEquals(200, Sample.clean("x".repeat(500)).length());
    }
}
