package io.github.pluginprofiler;

import java.util.concurrent.TimeUnit;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SampleHistoryTest
{
    static OwnershipIndex roster()
    {
        return new OwnershipIndex(List.of(OwnershipIndexTest.owner(1, "Alpha", null), OwnershipIndexTest.owner(2, "Beta", null)));
    }

    static StackTraceElement[] hit(int id)
    {
        return OwnershipIndexTest.stack(id == 0 ? "aq" : id == 1 ? "demo.AlphaPlugin" : "demo.BetaPlugin");
    }

    private static long ms(long milliseconds) { return TimeUnit.MILLISECONDS.toNanos(milliseconds); }

    @Test public void elapsedWindowDropsOldPathsAndKeepsSessionCounts()
    {
        SampleHistory history = new SampleHistory();
        OwnershipIndex roster = roster();
        history.record(10, roster, hit(1));
        history.record(20, roster, hit(0));
        history.record(30, roster, hit(1));
        SampleHistory.Snapshot recent = history.snapshot(40);
        assertEquals(3, recent.liveSamples);
        assertEquals(2, history.recentPathCounts(40, 1).get("demo.AlphaPlugin.work").intValue());
        assertFalse(recent.stats.containsKey(0));
        SampleHistory.Snapshot later = history.snapshot(40 + SampleHistory.FRAME_WINDOW);
        assertEquals(0, later.liveSamples);
        assertEquals(3, later.total);
        assertEquals(Long.valueOf(2), later.session.get(1));
        assertTrue(history.recentPathCounts(40 + SampleHistory.FRAME_WINDOW, 1).isEmpty());
    }

    @Test public void fastSlowAndPausedFramesAreSeparatedAndStallsAreCountedOnce()
    {
        SampleHistory h = new SampleHistory();
        OwnershipIndex roster = roster();
        h.frames.finish(ms(0));
        h.record(ms(5), roster, hit(1));
        h.frames.finish(ms(16));
        h.record(ms(30), roster, hit(1));
        h.record(ms(80), roster, hit(1));
        h.frames.finish(ms(100));
        h.record(ms(200), roster, hit(1));
        h.frames.finish(ms(2400));
        SampleHistory.Snapshot snap = h.snapshot(ms(2401));
        assertEquals(1, snap.normal);
        assertEquals(2, snap.slow);
        assertEquals(4, snap.liveSamples);
        assertEquals(1, snap.stats.get(1).slowFrames);
        assertNull(snap.slowDifference(snap.stats.get(1)));
    }

    @Test public void comparesWithinFrameGroupsAndRequiresRepeatedDistinctSlowFrames()
    {
        SampleHistory h = new SampleHistory();
        OwnershipIndex roster = roster();
        long now = 0;
        h.frames.finish(now);
        for (int i = 0; i < 10; i++)
        {
            h.record(now + ms(4), roster, hit(i < 2 ? 1 : 0));
            now += ms(16);
            h.frames.finish(now);
            h.record(now + ms(4), roster, hit(i < 7 ? 1 : 0));
            now += ms(75);
            h.frames.finish(now);
        }
        SampleHistory.Snapshot snap = h.snapshot(now);
        assertEquals(50.0, snap.slowDifference(snap.stats.get(1)), 0.001);
        assertEquals(7, snap.stats.get(1).slowFrames);

        // Once the earlier activity leaves the live window it still informs the five-minute comparison.
        long switched = now + TimeUnit.SECONDS.toNanos(61);
        h.record(switched, roster, hit(2));
        h.record(switched + 1, roster, hit(0));
        SampleHistory.Snapshot live = h.snapshot(switched + 1);
        assertEquals(2, live.liveSamples);
        assertEquals(0, live.stats.get(1).liveSamples);
        assertEquals(1, live.stats.get(2).liveSamples);
        assertEquals(50.0, live.slowDifference(live.stats.get(1)), 0.001);
        assertEquals(9L, live.session.get(1).longValue());
        SampleHistory.Snapshot expired = h.snapshot(now + TimeUnit.MINUTES.toNanos(5) + 1);
        assertEquals(0, expired.slow);
        assertEquals(0, expired.normal);
        assertEquals(22, expired.total);
    }
}
