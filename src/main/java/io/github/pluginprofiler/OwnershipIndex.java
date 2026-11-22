package io.github.pluginprofiler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One immutable roster of eligible owners. Its observation caches belong to the sampler. */
final class OwnershipIndex
{
    enum Evidence { UNKNOWN, GUESS, LEARNED, ALIAS }
    static final int AMBIGUOUS = -1;

    static final class Owner
    {
        final int id;
        final String name;
        final String group;
        final boolean active;

        Owner(int id, String name, String group, boolean active)
        {
            this.id = id;
            this.name = name;
            this.group = group;
            this.active = active;
        }
    }

    static final class Match
    {
        final int id;
        final Evidence evidence;
        Match(int id, Evidence evidence) { this.id = id; this.evidence = evidence; }
    }

    static final Match UNKNOWN = new Match(0, Evidence.UNKNOWN);

    static final class Learned
    {
        private final Map<String, Integer> callers = new HashMap<>();
        private final Map<String, Integer> helpers = new HashMap<>();
        private long revision;

        private void witness(String helper, int owner, boolean local)
        {
            Integer previousCaller = callers.putIfAbsent(helper, owner);
            if (previousCaller != null && previousCaller != owner)
            {
                callers.put(helper, AMBIGUOUS);
                owner = AMBIGUOUS;
            }
            // A foreign caller can invalidate a learned helper, but cannot claim one.
            if (!local && owner != AMBIGUOUS) { return; }
            Integer old = helpers.put(helper, owner);
            if (old == null || old != owner) { revision++; }
        }
    }

    private static final class Candidate
    {
        final String name;
        final String pkg;
        final Match direct;
        final boolean entry;

        Candidate(String name, String pkg, Match direct, boolean entry)
        {
            this.name = name;
            this.pkg = pkg;
            this.direct = direct;
            this.entry = entry;
        }

        @Override public boolean equals(Object other)
        {
            if (this == other) { return true; }
            if (!(other instanceof Candidate)) { return false; }
            Candidate c = (Candidate) other;
            return name.equals(c.name) && direct.id == c.direct.id && direct.evidence == c.direct.evidence;
        }

        @Override public int hashCode() { return name.hashCode(); }
    }

    /** Recent observations and one session counter share the same revisable ownership decision. */
    static final class Signature
    {
        private final List<Candidate> classes;
        private final Set<Integer> active;
        private final int hash;
        private long revision = -1;
        private Match match = UNKNOWN;

        Signature(List<Candidate> classes, Set<Integer> active)
        {
            this.classes = classes;
            this.active = classes.isEmpty() ? Set.of() : active;
            this.hash = 31 * classes.hashCode() + this.active.hashCode();
        }

        // Rebuilding a roster must not create another session counter for equivalent evidence.
        // The cached resolution can change as we learn; only the facts at capture time identify a key.
        @Override public boolean equals(Object other)
        {
            if (this == other) { return true; }
            if (!(other instanceof Signature)) { return false; }
            Signature s = (Signature) other;
            return hash == s.hash && classes.equals(s.classes) && active.equals(s.active);
        }

        @Override public int hashCode() { return hash; }

        Match resolve(Learned learned)
        {
            if (revision == learned.revision) { return match; }
            Match best = UNKNOWN;
            for (Candidate c : classes)
            {
                best = combine(best, c.direct);
                int owner = learned.helpers.getOrDefault(c.name, 0);
                if (owner == AMBIGUOUS || active.contains(owner))
                {
                    best = combine(best, new Match(owner, Evidence.LEARNED));
                }
            }
            match = best.id > 0 ? best : UNKNOWN;
            revision = learned.revision;
            return match;
        }

        private static Match combine(Match a, Match b)
        {
            if (a.evidence == b.evidence && a.id != b.id) { return new Match(AMBIGUOUS, a.evidence); }
            return b.evidence.ordinal() > a.evidence.ordinal() ? b : a;
        }

        void learn(Learned learned)
        {
            Candidate anchor = null;
            for (Candidate c : classes)
            {
                if (c.entry && c.direct.evidence == Evidence.ALIAS && c.direct.id > 0) { anchor = c; }
            }
            if (anchor == null) { return; }
            for (Candidate c : classes)
            {
                if (c.direct.evidence == Evidence.ALIAS && c.direct.id != anchor.direct.id) { return; }
            }
            for (Candidate c : classes)
            {
                if (c == anchor)
                {
                    learned.witness(c.name, anchor.direct.id, true);
                    break;
                }
                if (c.direct.evidence != Evidence.ALIAS && !c.entry)
                {
                    learned.witness(c.name, anchor.direct.id, c.pkg.equals(anchor.pkg));
                }
            }
        }
    }

    static final class Observation
    {
        final Signature signature;
        final String path;
        Observation(Signature signature, String path) { this.signature = signature; this.path = path; }
    }

    private final Set<Integer> active = new HashSet<>();
    private final Map<String, Integer> aliases = new HashMap<>();
    private final Map<String, Candidate> candidates = new HashMap<>();
    private final Map<List<Candidate>, Signature> signatures = new HashMap<>();

    OwnershipIndex(Collection<Owner> roster)
    {
        for (Owner owner : roster)
        {
            if (owner.active) { active.add(owner.id); }
            alias(owner.name, owner.id);
            if (owner.group != null && !owner.group.equalsIgnoreCase("runelite")) { alias(owner.group, owner.id); }
        }
    }

    private void alias(String raw, int id)
    {
        String name = normalize(raw);
        if (!name.isEmpty()) { aliases.merge(name, id, (a, b) -> a.equals(b) ? a : AMBIGUOUS); }
    }

    Observation observe(StackTraceElement[] stack, Learned learned)
    {
        List<Candidate> seen = new ArrayList<>();
        int lastCandidate = -1;
        int lastAlias = -1;
        for (int i = 0; i < stack.length; i++)
        {
            String name = stack[i].getClassName();
            int nested = name.indexOf('$');
            if (nested >= 0) { name = name.substring(0, nested); }
            if (!eligibleClass(name)) { continue; }
            Candidate c = candidates.computeIfAbsent(name, this::candidate);
            if (!seen.contains(c)) { seen.add(c); }
            lastCandidate = i;
            if (c.direct.id > 0) { lastAlias = i; }
        }
        Signature signature = signatures.get(seen);
        if (signature == null)
        {
            signature = new Signature(seen, active);
            signatures.put(seen, signature);
            signature.learn(learned);
        }
        int caller = lastAlias >= 0 ? lastAlias : lastCandidate;
        return new Observation(signature, caller < 0 ? null : path(stack, caller));
    }

    private static boolean eligibleClass(String name)
    {
        return name.indexOf('.') > 0 && (!name.startsWith("net.runelite.") || name.startsWith("net.runelite.client.plugins."))
            && !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jdk.")
            && !name.startsWith("sun.") && !name.startsWith("com.sun.") && !name.startsWith("com.google.")
            && !name.startsWith("org.lwjgl.") && !name.startsWith("org.slf4j.");
    }

    private Candidate candidate(String name)
    {
        int dot = name.lastIndexOf('.');
        String simple = name.substring(dot + 1);
        for (String suffix : SUFFIXES)
        {
            if (simple.endsWith(suffix))
            {
                String stem = normalize(simple.substring(0, simple.length() - suffix.length()));
                return new Candidate(name, name.substring(0, dot), guess(stem), suffix.equals("Plugin"));
            }
        }
        return new Candidate(name, name.substring(0, dot), UNKNOWN, false);
    }

    private static final String[] SUFFIXES = {"Plugin", "Overlay", "Panel"};

    private Match guess(String stem)
    {
        if (stem.length() < 3) { return UNKNOWN; }
        Integer exact = aliases.get(stem);
        if (exact != null) { return new Match(active.contains(exact) ? exact : AMBIGUOUS, Evidence.ALIAS); }
        if (stem.length() < 7 || stem.length() > 80) { return UNKNOWN; }
        int limit = stem.length() < 12 ? 1 : 2;
        int first = limit + 2;
        int second = first;
        int winner = 0;
        for (Map.Entry<String, Integer> alias : aliases.entrySet())
        {
            if (Math.abs(stem.length() - alias.getKey().length()) > limit + 1) { continue; }
            int distance = editDistance(stem, alias.getKey());
            if (distance < first)
            {
                if (alias.getValue() != winner) { second = first; }
                first = distance;
                winner = alias.getValue();
            }
            else if (alias.getValue() != winner && distance < second) { second = distance; }
        }
        if (first > limit) { return UNKNOWN; }
        return new Match(active.contains(winner) && second - first >= 2 ? winner : AMBIGUOUS, Evidence.GUESS);
    }

    private static int editDistance(String a, String b)
    {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j < row.length; j++) { row[j] = j; }
        for (int i = 1; i <= a.length(); i++)
        {
            int diagonal = row[0];
            row[0] = i;
            for (int j = 1; j < row.length; j++)
            {
                int previous = row[j];
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                row[j] = Math.min(diagonal + cost, Math.min(previous, row[j - 1]) + 1);
                diagonal = previous;
            }
        }
        return row[b.length()];
    }

    private static String normalize(String name) { return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    private static String path(StackTraceElement[] stack, int caller)
    {
        List<String> parts = new ArrayList<>();
        for (int i = caller; i >= 0; i--)
        {
            if (caller > 5 && i == caller - 2) { parts.add("…"); i = 2; }
            parts.add(stack[i].getClassName() + "." + stack[i].getMethodName());
        }
        return String.join(" → ", parts);
    }
}
