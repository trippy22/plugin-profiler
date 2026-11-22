package io.github.pluginprofiler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class SampleHistory
{
    static final long LIVE_WINDOW = TimeUnit.SECONDS.toNanos(60);
    static final long FRAME_WINDOW = TimeUnit.MINUTES.toNanos(5);
    static final long SLOW = TimeUnit.MILLISECONDS.toNanos(50);
    static final long MAX_FRAME = TimeUnit.SECONDS.toNanos(2);

    // Only the tiny timestamp ring is shared with the render thread.
    static final class Frames
    {
        private final long[] times = new long[512];
        private int next;
        private int size;

        synchronized void finish(long time)
        {
            times[next] = time;
            next = (next + 1) % times.length;
            size = Math.min(size + 1, times.length);
        }

        synchronized boolean classify(Shot shot)
        {
            if (size < 2) { return false; }
            int cur = (next + times.length - 1) % times.length;
            if (times[cur] < shot.time) { return false; }
            for (int n = 1; n < size; n++)
            {
                int prev = (cur + times.length - 1) % times.length;
                if (times[prev] < shot.time)
                {
                    long interval = times[cur] - times[prev];
                    if (interval <= MAX_FRAME)
                    {
                        shot.frame = times[cur];
                        shot.frameType = interval >= SLOW ? FrameType.SLOW : FrameType.NORMAL;
                    }
                    break;
                }
                cur = prev;
            }
            return true;
        }
    }

    enum FrameType { UNMEASURED, NORMAL, SLOW }

    static final class Shot
    {
        final long time;
        final OwnershipIndex.Observation observation;
        long frame;
        FrameType frameType = FrameType.UNMEASURED;

        Shot(long time, OwnershipIndex.Observation observation)
        {
            this.time = time;
            this.observation = observation;
        }
    }

    static final class Stats
    {
        int liveSamples;
        int slow;
        int normal;
        int slowFrames;
        long previousSlow = Long.MIN_VALUE;
        boolean onlyGuesses = true;

        void count(Shot shot, OwnershipIndex.Match match, boolean inLiveWindow)
        {
            if (inLiveWindow) { liveSamples++; }
            if (match.evidence != OwnershipIndex.Evidence.GUESS) { onlyGuesses = false; }
            if (shot.frameType == FrameType.NORMAL) { normal++; }
            if (shot.frameType == FrameType.SLOW)
            {
                slow++;
                if (previousSlow != shot.frame)
                {
                    slowFrames++;
                    previousSlow = shot.frame;
                }
            }
        }
    }

    static final class Snapshot
    {
        final Map<Integer, Stats> stats = new HashMap<>();
        final Map<Integer, Long> session;
        final long total;
        int liveSamples;
        int slow;
        int normal;

        Snapshot(Map<Integer, Long> session, long total)
        {
            this.session = session;
            this.total = total;
        }

        Double slowDifference(Stats row)
        {
            if (slow < 10 || normal < 10 || row.slowFrames < 3) { return null; }
            return 100.0 * row.slow / slow - 100.0 * row.normal / normal;
        }
    }

    final Frames frames = new Frames();
    private final Deque<Shot> recent = new ArrayDeque<>();
    private final Deque<Shot> awaitingFrame = new ArrayDeque<>();
    private final OwnershipIndex.Learned learned = new OwnershipIndex.Learned();
    private final Map<OwnershipIndex.Signature, Long> session = new HashMap<>();
    private long total;

    // Owned by the sampler worker. Snapshots are handed to Swing after construction.
    void record(long time, OwnershipIndex roster, StackTraceElement[] stack)
    {
        OwnershipIndex.Observation observation = roster.observe(stack, learned);
        Shot shot = new Shot(time, observation);
        recent.add(shot);
        awaitingFrame.add(shot);
        session.merge(observation.signature, 1L, Long::sum);
        total++;
        advance(time);
    }

    void advance(long now)
    {
        while (!awaitingFrame.isEmpty())
        {
            Shot shot = awaitingFrame.peek();
            if (!frames.classify(shot) && now - shot.time <= MAX_FRAME) { break; }
            awaitingFrame.remove();
        }
        while (!recent.isEmpty() && now - recent.peek().time > FRAME_WINDOW) { recent.remove(); }
    }

    void clearWindow()
    {
        recent.clear();
        awaitingFrame.clear();
    }

    Snapshot snapshot(long now)
    {
        advance(now);
        Map<Integer, Long> totals = new HashMap<>();
        session.forEach((signature, count) -> totals.merge(signature.resolve(learned).id, count, Long::sum));
        Snapshot result = new Snapshot(totals, total);
        for (Shot shot : recent)
        {
            boolean inLiveWindow = now - shot.time <= LIVE_WINDOW;
            if (inLiveWindow) { result.liveSamples++; }
            OwnershipIndex.Match owner = shot.observation.signature.resolve(learned);
            if (owner.id != 0) { result.stats.computeIfAbsent(owner.id, ignored -> new Stats()).count(shot, owner, inLiveWindow); }
            if (shot.frameType == FrameType.SLOW) { result.slow++; }
            if (shot.frameType == FrameType.NORMAL) { result.normal++; }
        }
        return result;
    }

    // Build a method-level histogram on demand. Swing snapshots carry numeric aggregates only.
    Map<String, Integer> recentPathCounts(long now, int pluginId)
    {
        advance(now);
        Map<String, Integer> counts = new HashMap<>();
        if (pluginId == 0) { return counts; }
        for (Shot shot : recent)
        {
            OwnershipIndex.Observation observation = shot.observation;
            if (observation.signature.resolve(learned).id == pluginId)
            {
                counts.merge(observation.path, 1, Integer::sum);
            }
        }
        return counts;
    }
}
