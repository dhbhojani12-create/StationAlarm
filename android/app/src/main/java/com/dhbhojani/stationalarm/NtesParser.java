package com.dhbhojani.stationalarm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the station list from NTES "Spot Your Train" page text. Each stop looks like:
 *   [arrival: sched / actual* / status]  NAME  /  CODE PF n* Coach Position  /  N KMs  [departure: sched / actual* / status]
 * Only stopping stations are listed. The starred times are the live ones (what the train really did),
 * so the delay and where the train is now come from comparing them with the scheduled times.
 * Plain Java so it can be tested off the phone.
 */
public class NtesParser {

    public static class Stop {
        public String code, name;
        public int arr = -1, dep = -1, km = -1;     // scheduled, minutes from day-1 midnight
        public int aarr = -1, adep = -1;            // actual (live), same scale; -1 if the train has not got there yet
        public String pf = "";                      // platform, "" if the page shows none
    }

    /** Where the train is right now, worked out from the live times. */
    public static class Live {
        public boolean has;                         // false when the page shows no live times at all
        public int lastIdx = -1;                    // last stop the train has reached
        public boolean atStation;                   // arrived there but not left yet
        public int delay;                           // minutes late (negative: early) at that stop
        public boolean hasDelay;
    }

    public static class Result {
        public String number = "", name = "";
        public List<Stop> stops = new ArrayList<>();
        public Live live = new Live();
    }

    static final Pattern STATION = Pattern.compile("\\n([A-Z][A-Z0-9 .()'/&-]{1,45})\\n\\s*\\n\\s*([A-Z]{1,5})\\b[^\\n]*Coach Position");
    static final Pattern TIME = Pattern.compile("(\\d{2}):(\\d{2}) (\\d{1,2})-([A-Za-z]{3})(\\*?)");
    static final Pattern KMS = Pattern.compile("(\\d+) KMs");
    static final Pattern TRAIN = Pattern.compile("Train No\\./Name[\\s\\u00a0]*(\\d{5})[\\s\\u00a0]+([^\\n]+)");
    static final Pattern PF = Pattern.compile("PF\\s*(\\w{1,3})");
    static final String MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec";

    /** One time block: the scheduled time and, if the train has got there, the real one. */
    static class Pair { long sched; long actual = Long.MIN_VALUE; }

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
            Matcher pm = PF.matcher(m.group(0));
            if (pm.find()) s.pf = pm.group(1);
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
            List<Pair> ps = pairs(after);
            // the segment after a station holds its departure, then the next station's arrival
            if (!ps.isEmpty()) {
                if (firstDay == Long.MIN_VALUE) firstDay = (ps.get(0).sched / 100000L);
                Pair d = ps.get(0);
                s.dep = mins(d.sched, firstDay);
                if (d.actual != Long.MIN_VALUE) s.adep = mins(d.actual, firstDay);
                if (ps.size() >= 2 && i + 1 < r.stops.size()) {
                    Pair a = ps.get(ps.size() - 1);
                    Stop nx = r.stops.get(i + 1);
                    nx.arr = mins(a.sched, firstDay);
                    if (a.actual != Long.MIN_VALUE) nx.aarr = mins(a.actual, firstDay);
                }
            }
        }
        // the first station's km is 0
        if (!r.stops.isEmpty() && r.stops.get(0).km < 0) r.stops.get(0).km = 0;
        live(r);
        return r;
    }

    /** day index and minute packed in one number: day * 100000 + minute of day. */
    static int mins(long packed, long firstDay) {
        long day = packed / 100000L, min = packed % 100000L;
        return (int) ((day - firstDay) * 1440 + min);
    }

    /** Where the train is now: the last stop with a live time, and how late it was there. */
    static void live(Result r) {
        Live l = r.live;
        for (int i = r.stops.size() - 1; i >= 0; i--) {
            Stop s = r.stops.get(i);
            if (s.aarr >= 0 || s.adep >= 0) {
                l.has = true;
                l.lastIdx = i;
                l.atStation = s.adep < 0;
                if (s.adep >= 0 && s.dep >= 0) { l.delay = s.adep - s.dep; l.hasDelay = true; }
                else if (s.aarr >= 0 && s.arr >= 0) { l.delay = s.aarr - s.arr; l.hasDelay = true; }
                else if (s.aarr >= 0 && s.dep >= 0) { l.delay = s.aarr - s.dep; l.hasDelay = true; }
                return;
            }
        }
    }

    /** Time blocks in page order: a scheduled time, followed by its starred live time if there is one. */
    static List<Pair> pairs(String seg) {
        List<Pair> out = new ArrayList<>();
        Matcher m = TIME.matcher(seg);
        Pair cur = null;
        while (m.find()) {
            int mon = MONTHS.indexOf(m.group(4).toLowerCase()) / 3;
            long day = mon * 31L + Integer.parseInt(m.group(3)); // good enough to count days within a trip
            long packed = day * 100000L + Integer.parseInt(m.group(1)) * 60L + Integer.parseInt(m.group(2));
            if (m.group(5).isEmpty()) { cur = new Pair(); cur.sched = packed; out.add(cur); }
            else if (cur != null && cur.actual == Long.MIN_VALUE) cur.actual = packed;
        }
        return out;
    }
}
