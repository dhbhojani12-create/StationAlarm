package com.dhbhojani.stationalarm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the station list from NTES "Spot Your Train" page text. Each stop looks like:
 *   [arrival: sched / actual* / status]  NAME  /  CODE PF n* Coach Position  /  N KMs  [departure: sched / actual* / status]
 * Only stopping stations are listed. Plain Java so it can be tested off the phone.
 */
public class NtesParser {

    public static class Stop {
        public String code, name;
        public int arr = -1, dep = -1, km = -1; // minutes from day-1 midnight
    }

    public static class Result {
        public String number = "", name = "";
        public List<Stop> stops = new ArrayList<>();
    }

    static final Pattern STATION = Pattern.compile("\\n([A-Z][A-Z0-9 .()'/&-]{1,45})\\n\\s*\\n\\s*([A-Z]{1,5})\\b[^\\n]*Coach Position");
    static final Pattern SCHED = Pattern.compile("(\\d{2}):(\\d{2}) (\\d{1,2})-([A-Za-z]{3})(?!\\*)");
    static final Pattern KMS = Pattern.compile("(\\d+) KMs");
    static final Pattern TRAIN = Pattern.compile("Train No\\./Name[\\s\\u00a0]*(\\d{5})[\\s\\u00a0]+([^\\n]+)");
    static final String MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec";

    public static Result parse(String text) {
        Result r = new Result();
        Matcher tm = TRAIN.matcher(text);
        if (tm.find()) { r.number = tm.group(1); r.name = tm.group(2).trim(); }
        List<int[]> spans = new ArrayList<>();
        Matcher m = STATION.matcher(text);
        while (m.find()) {
            Stop s = new Stop();
            s.name = m.group(1).trim();
            s.code = m.group(2);
            r.stops.add(s);
            spans.add(new int[]{m.start(), m.end()});
        }
        long firstDay = Long.MIN_VALUE;
        for (int i = 0; i < r.stops.size(); i++) {
            Stop s = r.stops.get(i);
            int segEnd = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
            String after = text.substring(spans.get(i)[1], segEnd);
            Matcher km = KMS.matcher(after);
            if (km.find()) s.km = Integer.parseInt(km.group(1));
            List<long[]> ts = times(after);
            // the segment after a station holds its departure, then the next station's arrival
            if (!ts.isEmpty()) {
                if (firstDay == Long.MIN_VALUE) firstDay = ts.get(0)[0];
                s.dep = (int) ((ts.get(0)[0] - firstDay) * 1440 + ts.get(0)[1]);
                if (ts.size() >= 2 && i + 1 < r.stops.size()) {
                    long[] a = ts.get(ts.size() - 1);
                    r.stops.get(i + 1).arr = (int) ((a[0] - firstDay) * 1440 + a[1]);
                }
            }
        }
        // the first station's km is 0
        if (!r.stops.isEmpty() && r.stops.get(0).km < 0) r.stops.get(0).km = 0;
        return r;
    }

    /** Scheduled times (not the starred live ones) as {day index, minute of day}. */
    static List<long[]> times(String seg) {
        List<long[]> out = new ArrayList<>();
        Matcher m = SCHED.matcher(seg);
        while (m.find()) {
            int mon = MONTHS.indexOf(m.group(4).toLowerCase()) / 3;
            long day = mon * 31L + Integer.parseInt(m.group(3)); // good enough to count days within a trip
            out.add(new long[]{day, Integer.parseInt(m.group(1)) * 60L + Integer.parseInt(m.group(2))});
        }
        return out;
    }
}
