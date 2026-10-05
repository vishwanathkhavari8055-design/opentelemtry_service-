package com.trinity.observability.util;

import java.time.Instant;

public final class TimeFormatter {

    private TimeFormatter() {}

    public static String microsToIso(long micros) {
        long secs  = micros / 1_000_000L;
        long nanos = (micros % 1_000_000L) * 1_000L;
        if (nanos < 0) {
            secs  -= 1;
            nanos += 1_000_000_000L;
        }
        return Instant.ofEpochSecond(secs, nanos).toString();
    }
}
