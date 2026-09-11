package org.qualet.irl.light.shadow;

public final class ShadowOverlayCacheTest
{
    private static int checks;
    private static final CasterRevision KNOWN = new CasterRevision(true, 1, 2, 3, 4, 5, 6);
    private static final Object BLOCKS = new Object();
    private static final Object A = new Object();
    private static final Object B = new Object();

    private static ShadowOverlayCache.Member member(CasterRevision revision)
    {
        return new ShadowOverlayCache.Member(1, revision, 1, 2, 3, 4, 5, 6, 3);
    }

    private static ShadowOverlayCache.Members members(Object... casters)
    {
        var members = new ShadowOverlayCache.Members();
        for (Object caster : casters) members.add(caster, member(KNOWN));
        return members;
    }

    private static boolean hit(ShadowOverlayCache cache, ShadowOverlayCache.Members members)
    {
        return cache.reusable(10, 1, 42, BLOCKS, 99, members);
    }

    private static void commit(ShadowOverlayCache cache, ShadowOverlayCache.Members members, boolean success)
    {
        cache.completed(10, 1, 42, BLOCKS, 99, members, success);
    }

    private static void check(boolean value, String name)
    {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    public static void main(String[] args)
    {
        var cache = new ShadowOverlayCache();
        var now = members(A, B);
        check(!hit(cache, now), "cold start must draw");
        commit(cache, now, true);
        check(hit(cache, members(A, B)), "fresh equal values may reuse");
        check(hit(cache, members(B, A)), "collection reorder preserves membership");
        now.clear();
        check(hit(cache, members(A, B)), "snapshot survives scratch reset");
        check(!hit(cache, members(A)), "one caster disappears");
        check(!hit(cache, members()), "last caster disappears and must restore base");
        check(!hit(cache, members(A, B, new Object())), "new caster enters");
        for (int domain = 0; domain < 6; domain++)
        {
            long[] v = {1, 2, 3, 4, 5, 6};
            v[domain]++;
            now = members(B);
            now.add(A, member(new CasterRevision(true, v[0], v[1], v[2], v[3], v[4], v[5])));
            check(!hit(cache, now), "silhouette domain " + domain);
        }
        for (CasterRevision unknown : new CasterRevision[]{CasterRevision.UNKNOWN, null})
        {
            now = members(B);
            now.add(A, member(unknown));
            check(!hit(cache, now), "unknown cannot match old known");
            commit(cache, now, true);
            check(!hit(cache, members(A, B)), "unknown draw invalidates previous snapshot");
            commit(cache, members(A, B), true);
        }
        now = members(A, A);
        check(!now.known(), "duplicate handles are ambiguous");

        // Production uses the primitive overload so UNKNOWN/disabled frames
        // reject member collection before allocating an immutable Member.
        for (int unknownAt = 0; unknownAt < 3; unknownAt++)
        {
            now.clear();
            Object[] casters = {A, B, new Object()};
            for (int i = 0; i < casters.length; i++)
            {
                now.add(casters[i], 1, i == unknownAt ? CasterRevision.UNKNOWN : KNOWN,
                    1, 2, 3, 4, 5, 6, 3);
            }
            check(!now.known(), "unknown at position " + unknownAt + " poisons whole overlay");
            commit(cache, members(A, B), true);
            commit(cache, now, true);
            check(!hit(cache, members(A, B)), "unknown gated collection still invalidates old snapshot");
        }
        now.clear();
        now.add(A, 1, KNOWN, 1, 2, 3, 4, 5, 6, 3);
        now.add(A, 1, KNOWN, 1, 2, 3, 4, 5, 6, 3);
        now.add(B, 1, KNOWN, 1, 2, 3, 4, 5, 6, 3);
        check(!now.known(), "primitive duplicate gate remains unknown after further members");
        now.clear(false);
        now.add(A, 1, KNOWN, 1, 2, 3, 4, 5, 6, 3);
        now.add(B, member(KNOWN));
        check(!now.known(), "disabled collection rejects both entry points");
        commit(cache, members(A, B), true);
        commit(cache, now, true);
        check(!hit(cache, members(A, B)), "disabled collection cannot commit an old membership");
        now.clear();
        now.add(A, 1, KNOWN, 1, 2, 3, 4, 5, 6, 3);
        now.add(B, 1, KNOWN, 1, 2, 3, 4, 5, 6, 3);
        commit(cache, now, true);
        check(hit(cache, members(B, A)), "reenabled primitive collection matches record path");
        var zero = new CasterRevision(true, 0, 0, 0, 0, 0, 0);
        now = new ShadowOverlayCache.Members();
        now.add(A, member(zero));
        commit(cache, now, true);
        check(hit(cache, now), "zero revisions are valid");

        now = members(A, B);
        commit(cache, now, true);
        check(!cache.reusable(10, 2, 42, BLOCKS, 99, now), "tile handoff");
        check(!cache.reusable(11, 1, 42, BLOCKS, 99, now), "different lamp");
        check(!cache.reusable(10, 1, 43, BLOCKS, 99, now), "lamp or static content moved");
        check(!cache.reusable(10, 1, 42, new Object(), 99, now), "terrain revision");
        check(!cache.reusable(10, 1, 42, BLOCKS, 100, now), "double anchor or scissor settings");
        var faceChange = members(B);
        faceChange.add(A, new ShadowOverlayCache.Member(1, KNOWN, 1, 2, 3, 4, 5, 6, 7));
        check(!hit(cache, faceChange), "point face coverage changed");
        var boundsChange = members(B);
        boundsChange.add(A, new ShadowOverlayCache.Member(1, KNOWN, 1, 2, 3, 8, 5, 6, 3));
        check(!hit(cache, boundsChange), "caster bounds changed");
        var typeChange = members(B);
        typeChange.add(A, new ShadowOverlayCache.Member(2, KNOWN, 1, 2, 3, 4, 5, 6, 3));
        check(!hit(cache, typeChange), "draw arm changed");
        commit(cache, now, false);
        check(!hit(cache, now), "recovered draw failure or stale base cannot commit");
        commit(cache, now, true);
        cache.forget(10);
        check(!hit(cache, now), "tile steal purges old owner");
        commit(cache, now, true);
        cache.retain(id -> id == 10);
        check(hit(cache, now), "active owner retained");
        cache.retain(id -> false);
        check(!hit(cache, now), "dead owner removed");
        commit(cache, now, true);
        cache.clear();
        check(!hit(cache, now), "world, quality, shaders, failure or disabled-cache reset");

        Object equalA = new String("same"), equalB = new String("same");
        commit(cache, members(equalA), true);
        check(!hit(cache, members(equalB)), "equal objects are distinct casters");
        check(hit(cache, members(equalA)), "identical caster retains snapshot");
        System.out.println("Shadow overlay checks passed: " + checks);
    }
}
