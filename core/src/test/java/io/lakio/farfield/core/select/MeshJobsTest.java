package io.lakio.farfield.core.select;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeshJobsTest {

    @Test
    void countsByCauseClassAndLevel() {
        MeshJobs jobs = new MeshJobs();
        jobs.asked(MeshJobs.FIRST, false, 0);
        jobs.asked(MeshJobs.FIRST, true, 0);
        jobs.asked(MeshJobs.FIRST, true, 7);
        jobs.asked(MeshJobs.TILE_REFINED, true, 2);
        jobs.asked(MeshJobs.TILE_REFINED, true, 4);
        jobs.asked(MeshJobs.REAL_WRITTEN, false, 5);
        jobs.asked(99, false, 1); // an unknown cause counts as other
        jobs.replacedUndrawn();
        jobs.outdatedOnArrival();
        jobs.outdatedOnArrival();
        assertEquals(7, jobs.total());
        assertEquals(3, jobs.count(MeshJobs.FIRST));
        assertEquals(2, jobs.count(MeshJobs.TILE_REFINED));
        assertEquals(1, jobs.count(MeshJobs.OTHER));
        assertEquals("7 asked: first 3 (real 1, preview 2), real written 1, preview refined 2, other 1; by level"
                + " [0, 1-2, 3-5, 6+] real [1, 1, 1, 0], preview [1, 1, 1, 1]; 1 replaced before being drawn,"
                + " 2 outdated on arrival", jobs.text());
    }

    @Test
    void resets() {
        MeshJobs jobs = new MeshJobs();
        jobs.asked(MeshJobs.FIRST, true, 3);
        jobs.replacedUndrawn();
        jobs.reset();
        assertEquals(0, jobs.total());
        assertEquals("0 asked; by level [0, 1-2, 3-5, 6+] real [0, 0, 0, 0], preview [0, 0, 0, 0]; 0 replaced before"
                + " being drawn, 0 outdated on arrival", jobs.text());
    }
}
