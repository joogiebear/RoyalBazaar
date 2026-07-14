package com.mystipixel.royalbazaar.market;

/**
 * A rolling 24-hour volume tracker: 24 hourly buckets in a ring. Feeds the {@code %rbazaar_volume_24h%}
 * placeholder and gives EconGuard a cheap read of recent trade pressure. Main-thread only.
 */
public final class VolumeWindow {

    private static final int HOURS = 24;
    private static final long HOUR_MS = 3_600_000L;

    private final long[] bought = new long[HOURS];
    private final long[] sold = new long[HOURS];
    private long currentHour = -1; // epoch-hour index of bought[head]
    private int head = 0;

    private void advanceTo(long epochHour) {
        if (currentHour < 0) {
            currentHour = epochHour;
            return;
        }
        long steps = epochHour - currentHour;
        if (steps <= 0) {
            return;
        }
        for (long s = 0; s < Math.min(steps, HOURS); s++) {
            head = (head + 1) % HOURS;
            bought[head] = 0;
            sold[head] = 0;
        }
        currentHour = epochHour;
    }

    public void recordBuy(long qty) {
        advanceTo(System.currentTimeMillis() / HOUR_MS);
        bought[head] += qty;
    }

    public void recordSell(long qty) {
        advanceTo(System.currentTimeMillis() / HOUR_MS);
        sold[head] += qty;
    }

    /** Advance the ring without recording — call on the tick so stale hours drop off. */
    public void tick() {
        advanceTo(System.currentTimeMillis() / HOUR_MS);
    }

    public long bought24h() {
        long t = 0;
        for (long b : bought) {
            t += b;
        }
        return t;
    }

    public long sold24h() {
        long t = 0;
        for (long s : sold) {
            t += s;
        }
        return t;
    }

    public long net24h() {
        return bought24h() - sold24h();
    }
}
