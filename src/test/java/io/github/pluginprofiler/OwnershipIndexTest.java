package io.github.pluginprofiler;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnershipIndexTest
{
    static OwnershipIndex.Owner owner(int id, String name, String group)
    {
        return new OwnershipIndex.Owner(id, name, group, true);
    }

    static ClientThreadStack stack(String... names)
    {
        StackTraceElement[] frames = Arrays.stream(names)
            .map(name -> new StackTraceElement(name, "work", null, -1))
            .toArray(StackTraceElement[]::new);
        return new ClientThreadStack(frames);
    }

    @Test public void equivalentRostersShareCounterKeysWithoutMergingDifferentAttributionEvidence()
    {
        OwnershipIndex.Learned learned = new OwnershipIndex.Learned();
        Set<OwnershipIndex.Signature> keys = new HashSet<>();
        Set<OwnershipIndex.Signature> baseClient = new HashSet<>();
        for (int i = 0; i < 500; i++)
        {
            OwnershipIndex index = new OwnershipIndex(List.of(owner(1, "First", null),
                new OwnershipIndex.Owner(2, "Second", null, i % 2 == 0)));
            keys.add(index.observe(stack("demo.Helper", "demo.FirstPlugin"), learned).signature);
            baseClient.add(index.observe(stack("aa"), learned).signature);
        }
        assertEquals(2, keys.size());
        assertEquals(1, baseClient.size());
        OwnershipIndex renamed = new OwnershipIndex(List.of(owner(1, "Renamed", null), owner(2, "Second", null)));
        keys.add(renamed.observe(stack("demo.Helper", "demo.FirstPlugin"), learned).signature);
        assertEquals(3, keys.size());
    }

    private static int resolve(OwnershipIndex index, OwnershipIndex.Learned learned, String... names)
    {
        return index.observe(stack(names), learned).signature.resolve(learned).id;
    }

    @Test public void usesOnlyLiveNamesAndConfigAliasesIncludingCorePackages()
    {
        OwnershipIndex index = new OwnershipIndex(List.of(owner(1, "Raid Helper", "tombraider"), owner(2, "FPS Control", "fps")));
        OwnershipIndex.Learned learned = new OwnershipIndex.Learned();
        assertEquals(1, resolve(index, learned, "com.tools.TombraiderOverlay"));
        assertEquals(1, resolve(index, learned, "com.tools.RaidHelprPlugin"));
        assertEquals(2, resolve(index, learned, "net.runelite.client.plugins.fps.FpsPlugin$Listener"));
        assertEquals(0, resolve(index, learned, "java.util.RaidHelperPanel", "aa"));
        assertEquals(0, resolve(index, learned, "com.tools.RaidHelperPlugin", "org.tools.FpsPlugin"));
        assertEquals(1, resolve(index, learned, "aa", "com.tools.RaidHelperPlugin"));
    }

    @Test public void ambiguousAndDisabledAliasesCannotTeachOwnership()
    {
        OwnershipIndex index = new OwnershipIndex(List.of(owner(1, "First", "shared"), owner(2, "Second", "shared"),
            new OwnershipIndex.Owner(3, "Third", null, false)));
        OwnershipIndex.Learned learned = new OwnershipIndex.Learned();
        assertEquals(0, resolve(index, learned, "com.tools.Helper", "com.tools.SharedPlugin"));
        assertEquals(0, resolve(index, learned, "com.tools.Helper", "com.tools.ThirdPlugin"));
        assertEquals(0, resolve(index, learned, "com.tools.Helper"));
        assertEquals(0, resolve(index, learned, "com.other.Helper", "com.tools.FirstPlugin", "com.tools.SecondPlugin"));
        assertEquals(0, resolve(index, learned, "com.other.Helper"));
    }

    @Test public void backfillsRecentPathsAndExpiredSessionHitsAndRetractsConflictingEvidence()
    {
        OwnershipIndex index = new OwnershipIndex(List.of(owner(1, "First", null), owner(2, "Second", null)));
        SampleHistory h = new SampleHistory();
        String helper = "com.tools.Helper";
        h.record(1, index, stack(helper));
        assertTrue(h.snapshot(2).stats.isEmpty());
        long later = SampleHistory.FRAME_WINDOW + 5;
        h.record(later, index, stack(helper));
        h.record(later + 1, index, stack(helper));
        h.record(later + 2, index, stack(helper, "com.tools.FirstPlugin"));
        SampleHistory.Snapshot credited = h.snapshot(later + 2);
        assertEquals(4L, credited.session.get(1).longValue());
        assertEquals(3, credited.stats.get(1).liveSamples);
        assertEquals(2, h.recentPathCounts(later + 2, 1).get("com.tools.Helper.work").intValue());
        h.record(later + 3, index, stack(helper, "org.another.SecondPlugin"));
        SampleHistory.Snapshot uncertain = h.snapshot(later + 4);
        assertEquals(3L, uncertain.session.get(0).longValue());
        assertEquals(1L, uncertain.session.get(1).longValue());
        assertEquals(1L, uncertain.session.get(2).longValue());
        assertFalse(uncertain.stats.containsKey(0));
        assertEquals(4, uncertain.liveSamples);
        assertEquals(5, uncertain.total);
    }

    @Test public void laterEvidenceOverridesFuzzyMatchesButCannotAssignTimeBeforeActivation()
    {
        OwnershipIndex old = new OwnershipIndex(List.of(owner(1, "Raid Helper", null)));
        OwnershipIndex live = new OwnershipIndex(List.of(owner(1, "Raid Helper", null), owner(2, "Another", null)));
        SampleHistory h = new SampleHistory();
        String helper = "com.tools.RaidHelprOverlay";
        h.record(1, old, stack("com.tools.UniqueHelper"));
        h.record(2, live, stack(helper));
        assertEquals(1L, h.snapshot(2).session.get(1).longValue());
        h.record(3, live, stack(helper, "com.tools.UniqueHelper", "com.tools.AnotherPlugin"));
        SampleHistory.Snapshot s = h.snapshot(4);
        assertEquals(2L, s.session.get(2).longValue());
        assertEquals(1L, s.session.get(0).longValue());
        assertFalse(s.session.containsKey(1));
    }

    @Test public void missingAndUnstartedThreadsYieldEmptySamples()
    {
        assertEquals(0, ClientThreadStack.read(null).size());
        assertEquals(0, ClientThreadStack.read(new Thread()).size());
    }

    @Test public void runtimeSampleRecognizesANamedCaller() throws Exception
    {
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        Thread worker = new Thread(() -> DemoSamplingPlugin.hold(entered, release));
        worker.start();
        try
        {
            entered.get(5, TimeUnit.SECONDS);
            OwnershipIndex index = new OwnershipIndex(List.of(owner(7, "Demo Sampling", null)));
            OwnershipIndex.Learned learned = new OwnershipIndex.Learned();
            OwnershipIndex.Observation observation = index.observe(ClientThreadStack.read(worker), learned);
            assertEquals(7, observation.signature.resolve(learned).id);
            assertTrue(observation.path.contains("DemoSamplingPlugin.hold"));
        }
        finally { release.complete(null); worker.join(5000); }
    }
}

final class DemoSamplingPlugin
{
    static void hold(CompletableFuture<Void> entered, CompletableFuture<Void> release)
    {
        entered.complete(null);
        release.orTimeout(10, TimeUnit.SECONDS).join();
    }
}
