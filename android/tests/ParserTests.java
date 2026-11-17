package com.dhbhojani.stationalarm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Off-device checks for the NTES page parser. Run from the android/ folder:
 *   javac -d /tmp/sa-tests app/src/main/java/com/dhbhojani/stationalarm/{NtesParser,PnrParser}.java tests/ParserTests.java
 *   java -cp /tmp/sa-tests com.dhbhojani.stationalarm.ParserTests
 * Fixtures are real "Copy for Claude" captures of NTES Spot Your Train for train 22946 on 17-Nov-2026:
 *   ntes-22946.txt          before the train started (every live time starred = expected)
 *   ntes-22946-running.txt  at 13:59, train left Jamnagar 1 minute late (real times unstarred, later ones starred)
 */
public class ParserTests {
    static int fails = 0;
    static void eq(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : "  got=" + got + " want=" + want));
    }

    static String read(String f) throws Exception { return new String(Files.readAllBytes(Paths.get(f)), StandardCharsets.UTF_8); }

    static int idx(NtesParser.Result r, String code) {
        for (int i = 0; i < r.stops.size(); i++) if (r.stops.get(i).code.equals(code)) return i;
        return -1;
    }

    public static void main(String[] a) throws Exception {
        // ---------- capture 1: train not started, layout A
        NtesParser.Result r = NtesParser.parse(read("tests/ntes-22946.txt"));
        eq("A: train number and name", r.number + " " + r.name, "22946 SAURASHTRA MAIL");
        eq("A: status line", r.statusLine, "Yet to start from its source");
        eq("A: 31 stops", r.stops.size(), 31);
        eq("A: first/last stop", r.stops.get(0).code + "/" + r.stops.get(30).code, "OKHA/MMCT");
        eq("A: platforms", r.stops.get(0).pf + "," + r.stops.get(1).pf + "," + r.stops.get(18).pf + "," + r.stops.get(28).pf, "2,,4,7");
        eq("A: km first/second/last", r.stops.get(0).km + "/" + r.stops.get(1).km + "/" + r.stops.get(30).km, "0/10/990");
        eq("A: OKHA dep 11:05", r.stops.get(0).dep, 665);
        eq("A: MITHAPUR arr/dep", r.stops.get(1).arr + "/" + r.stops.get(1).dep, "677/678");
        eq("A: BHARUCH arr/dep 23:11, 23:13", r.stops.get(22).arr + "/" + r.stops.get(22).dep, "1391/1393");
        eq("A: SURAT arr 00:05 next day", r.stops.get(23).arr, 1445);
        eq("A: MMCT arr 04:55 next day, no dep", r.stops.get(30).arr + "/" + r.stops.get(30).dep, "1735/-1");
        eq("A: starred times are expected, not real", r.stops.get(2).earr + "/" + r.stops.get(2).edep + " real " + r.stops.get(2).aarr + "/" + r.stops.get(2).adep, "695/700 real -1/-1");
        eq("A: not started, no live position", r.live.has, false);

        // ---------- capture 2: train running, layout B (code line ends with "Coach Position"), unstarred = real
        NtesParser.Result q = NtesParser.parse(read("tests/ntes-22946-running.txt"));
        eq("B: train number and name", q.number + " " + q.name, "22946 SAURASHTRA MAIL");
        eq("B: status line", q.statusLine, "Departed from JAMNAGAR(JAM) at 13:50 17-Nov (Delay: 00:01)");
        eq("B: NTES update time", fmt(q.updatedMs), "2026-11-17 13:51");
        eq("B: 31 stops", q.stops.size(), 31);
        eq("B: platform JAM, HAPA (starred), MMCT", q.stops.get(idx(q, "JAM")).pf + "," + q.stops.get(idx(q, "HAPA")).pf + "," + q.stops.get(30).pf, "1,1,5");
        eq("B: live found", q.live.has, true);
        eq("B: last reached stop is JAMNAGAR", q.stops.get(q.live.lastIdx).code, "JAM");
        eq("B: JAMNAGAR already left", q.live.atStation, false);
        eq("B: delay 1 min (matches page)", q.live.delay, 1);
        Stop(q, "JAM");
        NtesParser.Stop jam = q.stops.get(idx(q, "JAM"));
        eq("B: JAMNAGAR scheduled dep 13:49, real dep 13:50", jam.dep + "/" + jam.adep, "829/830");
        eq("B: JAMNAGAR arrival scheduled 13:44, real 13:45", jam.arr + "/" + jam.aarr, "824/825");
        NtesParser.Stop knls = q.stops.get(idx(q, "KNLS"));
        eq("B: KANALAS departed 7 min late", (knls.adep - knls.dep) + "", "7");
        NtesParser.Stop hapa = q.stops.get(idx(q, "HAPA"));
        eq("B: HAPA arrival only expected (14:03)", hapa.aarr + "/" + hapa.earr, "-1/843");
        eq("B: later stops expected, none real", q.stops.get(20).aarr + "/" + q.stops.get(20).adep + " exp " + (q.stops.get(20).earr >= 0), "-1/-1 exp true");
        eq("B: 4 Min late stop BHATIYA", (q.stops.get(idx(q, "BHTA")).aarr - q.stops.get(idx(q, "BHTA")).arr) + "", "4");

        // ---------- coach order (real rows from the page)
        List<String> rows = Arrays.asList(read("tests/ntes-22946-rows.txt").split("\n"));
        NtesParser.coaches(rows, q);
        eq("coaches: 23 in order", q.coaches.size(), 23);
        eq("coaches: engine first", q.coaches.get(0).label + "@" + q.coaches.get(0).pos, "ENG@0");
        eq("coaches: B2 is 10 behind the engine", find(q, "B2"), "3A B2 @10");
        eq("coaches: S1", find(q, "S1"), "SL S1 @19");
        eq("coaches: RA added at some stations", q.coachesExtra.size() == 1 ? q.coachesExtra.get(0).label + "@" + q.coachesExtra.get(0).pos : "none:" + q.coachesExtra.size(), "RA@23");

        // ---------- an odd block (one time missing) still gets a sensible answer instead of nonsense
        NtesParser.Result o = NtesParser.parse(read("tests/ntes-22946-running.txt").replace("14:03 17-Nov\n14:03 17-Nov*\nOn Time\n", "14:03 17-Nov\nOn Time\n"));
        eq("odd block: still 31 stops", o.stops.size(), 31);

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }

    static void Stop(NtesParser.Result r, String c) { }

    static String fmt(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return f.format(new Date(ms));
    }

    static String find(NtesParser.Result r, String label) {
        for (NtesParser.Coach c : r.coaches) if (c.label.equals(label)) return c.type + " " + c.label + " @" + c.pos;
        return "none";
    }
}
