package com.dhbhojani.stationalarm;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the NTES "Spot Your Train" page text. Each stopping station looks like:
 *   NAME / CODE PF n[*] [Coach Position] / N KMs (or SRC for the first), then two time blocks:
 *   this station's departure, then the next station's arrival.
 * A time block is two times and a status: "HH:MM D-Mon" (scheduled), "HH:MM D-Mon" (what the train really did)
 * and "On Time" or "N Min". A time with a star after it is NTES's estimate for something that has NOT happened yet.
 * So: unstarred live time = real, starred = expected. Checked against real captures of a train that had not started
 * (every time starred) and one that was running (real up to Jamnagar, starred after).
 * Plain Java so it can be tested off the phone.
 */
public class NtesParser {

    public static class Stop {
        public String code, name;
        public int arr = -1, dep = -1, km = -1;     // scheduled, minutes from day-1 midnight
        public int aarr = -1, adep = -1;            // real: the train has actually got there / left
        public int earr = -1, edep = -1;            // expected by NTES (not happened yet)
        public String pf = "";                      // platform, "" if the page shows none
    }

    /** Where the train is right now: the last stop with a real time. */
    public static class Live {
        public boolean has;                         // false when the train has not started or the page shows no real times
        public int lastIdx = -1;                    // last stop the train has reached
        public boolean atStation;                   // arrived there but not left yet
        public int delay;                           // minutes late (negative: early) at that stop
        public boolean hasDelay;
    }

    public static class Coach {
        public String type, label;                  // e.g. 3A / B2
        public int pos;                             // position from the engine (engine is 0)
    }

    public static class Result {
        public String number = "", name = "";
        public String statusLine = "";              // the page's own line, e.g. "Departed from JAMNAGAR(JAM) at 13:50 17-Nov (Delay: 00:01)"
        public long updatedMs;                      // "Last Updates On" time as epoch millis, 0 if the page has none
        public List<Stop> stops = new ArrayList<>();
        public Live live = new Live();
        public List<Coach> coaches = new ArrayList<>();       // coach order of the train
        public List<Coach> coachesExtra = new ArrayList<>();  // coaches that only some stations list (added on the way)
    }

    // layout A: NAME \n\n CODE [PF n*] \n\n (N KMs | SRC)
    static final Pattern STATION = Pattern.compile("\\n([A-Z][A-Z0-9 .()'/&-]{1,45})\\n\\s*\\n\\s*([A-Z]{1,5})(?:[ \\t]+PF[ \\t]*(\\w{1,3})\\*?)?[ \\t]*\\n\\s*\\n\\s*(?:(\\d+) KMs|(SRC))");
    // layout B: the code line ends with "Coach Position"
    static final Pattern STATION_B = Pattern.compile("\\n([A-Z][A-Z0-9 .()'/&-]{1,45})\\n\\s*\\n\\s*([A-Z]{1,5})\\b[^\\n]*Coach Position");
    static final Pattern TIME = Pattern.compile("(\\d{2}):(\\d{2}) (\\d{1,2})-([A-Za-z]{3})(\\*?)");
    static final Pattern KMS = Pattern.compile("(\\d+) KMs");
    static final Pattern TRAIN = Pattern.compile("Train No\\./Name\\s*(\\d{5})\\s+([^\\n]+)");
    static final Pattern POSITION = Pattern.compile("Current Position[ \\t]*\\n([^\\n]+)");
    static final Pattern STATUS = Pattern.compile("Start Date :[ \\t]*\\d{1,2}-[A-Za-z]{3}-\\d{4}[ \\t]*\\n([^\\n]+)");
    static final Pattern UPDATED = Pattern.compile("Last Updates On\\s*\\n\\s*(\\d{1,2})-([A-Za-z]{3})-(\\d{4}) (\\d{2}):(\\d{2})");
    static final Pattern PF = Pattern.compile("PF\\s*(\\w{1,3})");
    static final Pattern COACH = Pattern.compile("\\b([A-Z0-9]{1,4}) ([A-Z0-9]{1,4}) (\\d{1,2})\\b");
    static final String MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec";

    /** One time block: the scheduled time and the live one (real, or expected when starred). */
    static class Pair { long sched; long live = Long.MIN_VALUE; boolean liveStar; }

    public static Result parse(String text) {
        text = text.replace(' ', ' ');
        Result r = new Result();
        Matcher tm = TRAIN.matcher(text);
        if (tm.find()) { r.number = tm.group(1); r.name = tm.group(2).trim(); }
        Matcher pm0 = POSITION.matcher(text);
        if (pm0.find()) r.statusLine = pm0.group(1).trim();
        else { Matcher sm = STATUS.matcher(text); if (sm.find()) r.statusLine = sm.group(1).trim(); }
        Matcher um = UPDATED.matcher(text);
        if (um.find()) r.updatedMs = istMillis(um.group(3), um.group(2), um.group(1), um.group(4), um.group(5));

        List<int[]> spans = new ArrayList<>();
        Matcher m = STATION.matcher(text);
        while (m.find()) {
            Stop s = new Stop();
            s.name = m.group(1).trim();
            s.code = m.group(2);
            if (m.group(3) != null) s.pf = m.group(3);
            if (m.group(4) != null) s.km = Integer.parseInt(m.group(4));
            else if (m.group(5) != null) s.km = 0;
            r.stops.add(s);
            spans.add(new int[]{m.start(), m.end()});
        }
        if (r.stops.isEmpty()) { // layout B
            m = STATION_B.matcher(text);
            while (m.find()) {
                Stop s = new Stop();
                s.name = m.group(1).trim();
                s.code = m.group(2);
                Matcher pm = PF.matcher(m.group(0));
                if (pm.find()) s.pf = pm.group(1);
                r.stops.add(s);
                spans.add(new int[]{m.start(), m.end()});
            }
        }

        long firstDay = Long.MIN_VALUE;
        for (int i = 0; i < r.stops.size(); i++) {
            Stop s = r.stops.get(i);
            int segEnd = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
            String after = text.substring(spans.get(i)[1], segEnd);
            if (s.km < 0) {
                Matcher km = KMS.matcher(after);
                if (km.find()) s.km = Integer.parseInt(km.group(1));
            }
            List<Pair> ps = pairs(after);
            // the segment after a station holds its departure block, then the next station's arrival block
            if (!ps.isEmpty()) {
                if (firstDay == Long.MIN_VALUE) firstDay = ps.get(0).sched / 100000L;
                Pair d = ps.get(0);
                s.dep = mins(d.sched, firstDay);
                put(s, d, firstDay, false);
                if (ps.size() >= 2 && i + 1 < r.stops.size()) {
                    Pair a = ps.get(ps.size() - 1);
                    Stop nx = r.stops.get(i + 1);
                    nx.arr = mins(a.sched, firstDay);
                    put(nx, a, firstDay, true);
                }
            }
        }
        // the first station's km is 0
        if (!r.stops.isEmpty() && r.stops.get(0).km < 0) r.stops.get(0).km = 0;
        live(r);
        return r;
    }

    /** Files the live time as real (no star) or expected (starred). */
    static void put(Stop s, Pair p, long firstDay, boolean arrival) {
        if (p.live == Long.MIN_VALUE) return;
        int v = mins(p.live, firstDay);
        if (!p.liveStar) { if (arrival) s.aarr = v; else s.adep = v; }
        else { if (arrival) s.earr = v; else s.edep = v; }
    }

    static long pack(String mon, String day, String h, String mi) {
        int mo = MONTHS.indexOf(mon.toLowerCase()) / 3;
        return (mo * 31L + Integer.parseInt(day)) * 100000L + Integer.parseInt(h) * 60L + Integer.parseInt(mi);
    }

    /** Minutes since day-1 midnight of the first station. */
    static int mins(long packed, long firstDay) {
        long day = packed / 100000L, min = packed % 100000L, dd = day - firstDay;
        if (dd < -180) dd += 372; // the trip runs over New Year
        return (int) (dd * 1440 + min);
    }

    static long istMillis(String year, String mon, String day, String h, String mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.clear();
        c.set(Integer.parseInt(year), MONTHS.indexOf(mon.toLowerCase()) / 3, Integer.parseInt(day), Integer.parseInt(h), Integer.parseInt(mi));
        return c.getTimeInMillis();
    }

    /** Where the train is now: the last stop with a real time, and how late it was there. */
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

    /**
     * Time blocks of one segment. Each block is two times (scheduled, live). When the count is even they pair up in order;
     * otherwise (a block missing a time) a starred time is taken as the live one of the block before it.
     */
    static List<Pair> pairs(String seg) {
        List<long[]> tok = new ArrayList<>(); // {packed, star}
        Matcher m = TIME.matcher(seg);
        while (m.find()) tok.add(new long[]{pack(m.group(4), m.group(3), m.group(1), m.group(2)), m.group(5).isEmpty() ? 0 : 1});
        List<Pair> out = new ArrayList<>();
        if (tok.size() % 2 == 0) {
            for (int i = 0; i + 1 < tok.size(); i += 2) {
                Pair p = new Pair();
                p.sched = tok.get(i)[0];
                p.live = tok.get(i + 1)[0];
                p.liveStar = tok.get(i + 1)[1] == 1;
                out.add(p);
            }
        } else {
            Pair cur = null;
            for (long[] t : tok) {
                if (t[1] == 0) { cur = new Pair(); cur.sched = t[0]; out.add(cur); }
                else if (cur != null && cur.live == Long.MIN_VALUE) { cur.live = t[0]; cur.liveStar = true; }
            }
        }
        return out;
    }

    /**
     * Coach order from the page's table rows, e.g. "ENG ENG 0 LPR LPR 1 ... 3A B2 10 ...": type, label, position.
     * The first list gives the train's coaches; any coach that appears only in longer lists (a coach added on the way)
     * goes in coachesExtra.
     */
    public static void coaches(List<String> rows, Result r) {
        List<Coach> base = null, longest = null;
        for (String row : rows) {
            for (List<Coach> c : coachLists(row)) {
                if (base == null) base = c;
                if (longest == null || c.size() > longest.size()) longest = c;
            }
        }
        if (base == null) return;
        r.coaches = base;
        for (Coach c : longest) {
            boolean in = false;
            for (Coach b : base) if (b.label.equals(c.label) && b.pos == c.pos) in = true;
            if (!in) r.coachesExtra.add(c);
        }
    }

    /** The coach lists in one row (a row can hold an arrival list and a departure list). Each starts at the engine, position 0. */
    static List<List<Coach>> coachLists(String row) {
        Matcher m = COACH.matcher(row);
        List<List<Coach>> out = new ArrayList<>();
        List<Coach> cur = null;
        while (m.find()) {
            Coach c = new Coach();
            c.type = m.group(1); c.label = m.group(2); c.pos = Integer.parseInt(m.group(3));
            if (c.pos == 0) { cur = new ArrayList<>(); out.add(cur); }
            if (cur != null) cur.add(c);
        }
        List<List<Coach>> keep = new ArrayList<>();
        for (List<Coach> l : out) if (l.size() >= 3) keep.add(l);
        return keep;
    }
}
