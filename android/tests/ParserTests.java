package com.dhbhojani.stationalarm;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Off-device checks for the page parsers. Run from the android/ folder:
 *   javac -d /tmp/sa-tests app/src/main/java/com/dhbhojani/stationalarm/{NtesParser,PnrParser}.java tests/ParserTests.java
 *   java -cp /tmp/sa-tests com.dhbhojani.stationalarm.ParserTests
 * tests/ntes-22946.txt is a real "Copy for Claude" capture of NTES Spot Your Train (train 22946, not yet started).
 */
public class ParserTests {
    static int fails = 0;
    static void eq(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : "  got=" + got + " want=" + want));
    }

    static String read(String f) throws Exception { return new String(Files.readAllBytes(Paths.get(f)), StandardCharsets.UTF_8); }

    public static void main(String[] a) throws Exception {
        String page = read("tests/ntes-22946.txt");

        // ---- the real page: train not started yet
        NtesParser.Result r = NtesParser.parse(page);
        eq("train number", r.number, "22946");
        eq("train name", r.name, "SAURASHTRA MAIL");
        eq("status line", r.statusLine, "Yet to start from its source");
        eq("all 31 stops found", r.stops.size(), 31);
        eq("first stop", r.stops.get(0).name + "/" + r.stops.get(0).code, "OKHA/OKHA");
        eq("last stop", r.stops.get(30).name + "/" + r.stops.get(30).code, "MUMBAI CENTRAL/MMCT");
        eq("platform OKHA", r.stops.get(0).pf, "2");
        eq("no platform at MITHAPUR", r.stops.get(1).pf, "");
        eq("platform Ahmedabad", r.stops.get(18).code + " PF " + r.stops.get(18).pf, "ADI PF 4");
        eq("platform Borivali", r.stops.get(28).pf, "7");
        eq("km first/second/last", r.stops.get(0).km + "/" + r.stops.get(1).km + "/" + r.stops.get(30).km, "0/10/990");
        eq("OKHA dep 11:05", r.stops.get(0).dep, 665);
        eq("MITHAPUR arr 11:17, dep 11:18", r.stops.get(1).arr + "/" + r.stops.get(1).dep, "677/678");
        eq("DWARKA arr 11:35", r.stops.get(2).arr, 695);
        eq("BHARUCH arr 23:11 dep 23:13 (day 1)", r.stops.get(22).arr + "/" + r.stops.get(22).dep, "1391/1393");
        eq("SURAT arr 00:05 next day", r.stops.get(23).arr, 1445);
        eq("MUMBAI CENTRAL arr 04:55 next day", r.stops.get(30).arr, 1735);
        eq("MMCT has no departure", r.stops.get(30).dep, -1);
        eq("expected (not real) times kept as expected", r.stops.get(2).earr + "/" + r.stops.get(2).edep, "695/700");
        eq("nothing counted as really happened", r.stops.get(2).aarr + "/" + r.stops.get(2).adep, "-1/-1");
        eq("not started: no live position", r.live.has, false);

        // ---- same page, train now running: page clock 14:20. Slots are "this stop's departure, then the next stop's arrival".
        // JAMNAGAR (stop 6): arrived 13:44 -> 13:50, left 13:49 -> 13:55. HAPA (7): arrived 14:03 -> 14:09, left 14:05 -> 14:11.
        // ALIA BADA (8): arrival expected 14:22 (after the page clock, so not happened yet), departure expected 14:23.
        String run = page.replace("17-Nov 07:42    ", "17-Nov 14:20    ")
                .replace("\nStart Date : 17-Nov-2026\nYet to start from its source\n", "\nStart Date : 17-Nov-2026\nDeparted from HAPA\n")
                .replace("13:44 17-Nov\n13:44 17-Nov*", "13:44 17-Nov\n13:50 17-Nov*")
                .replace("13:49 17-Nov\n13:49 17-Nov*", "13:49 17-Nov\n13:55 17-Nov*")
                .replace("14:03 17-Nov\n14:03 17-Nov*", "14:03 17-Nov\n14:09 17-Nov*")
                .replace("14:05 17-Nov\n14:05 17-Nov*", "14:05 17-Nov\n14:11 17-Nov*")
                .replace("14:16 17-Nov\n14:16 17-Nov*", "14:16 17-Nov\n14:22 17-Nov*")
                .replace("14:17 17-Nov\n14:17 17-Nov*", "14:17 17-Nov\n14:23 17-Nov*");
        NtesParser.Result q = NtesParser.parse(run);
        eq("running: status line", q.statusLine, "Departed from HAPA");
        eq("running: live found", q.live.has, true);
        eq("running: last reached stop is HAPA", q.stops.get(q.live.lastIdx).code, "HAPA");
        eq("running: HAPA already left", q.live.atStation, false);
        eq("running: delay 6 min", q.live.delay, 6);
        eq("running: JAMNAGAR real arrival 13:50 and departure 13:55", q.stops.get(6).aarr + "/" + q.stops.get(6).adep, "830/835");
        eq("running: HAPA real arrival 14:09 and departure 14:11", q.stops.get(7).aarr + "/" + q.stops.get(7).adep, "849/851");
        eq("running: ALIA BADA arrival only expected 14:22", q.stops.get(8).aarr + "/" + q.stops.get(8).earr, "-1/862");
        eq("running: later stop expected, not real", q.stops.get(12).aarr + "/" + (q.stops.get(12).earr >= 0), "-1/true");
        NtesParser.Result q2 = NtesParser.parse(run.replace("17-Nov 14:20    ", "17-Nov 14:22    "));
        eq("at ALIA BADA: arrived, not left", q2.stops.get(q2.live.lastIdx).code + " atStation=" + q2.live.atStation, "ALB atStation=true");
        eq("at ALIA BADA: delay 6", q2.live.delay, 6);

        // ---- coach order (real rows from the page)
        List<String> rows = Arrays.asList(read("tests/ntes-22946-rows.txt").split("\n"));
        NtesParser.coaches(rows, r);
        eq("22 coaches in the base order", r.coaches.size(), 23);
        eq("engine first", r.coaches.get(0).label + "@" + r.coaches.get(0).pos, "ENG@0");
        eq("B2 is 10th from the engine", find(r, "B2"), "3A B2 @10");
        eq("S1 position", find(r, "S1"), "SL S1 @19");
        eq("RA coach added at some stations", r.coachesExtra.size() == 1 ? r.coachesExtra.get(0).label + "@" + r.coachesExtra.get(0).pos : "none:" + r.coachesExtra.size(), "RA@23");

        // ---- older page layout still reads
        String old = "Train No./Name 12009 SHATABDI EXP\n"
            + "\nMUMBAI CENTRAL\n\nMMCT PF 2 Coach Position\n0 KMs\n06:25 17-Nov\n06:25 17-Nov*\n"
            + "08:02 17-Nov\n08:11 17-Nov*\n\nBHARUCH JN\n\nBH PF 3* Coach Position\n392 KMs\n08:07 17-Nov\n08:17 17-Nov*\n";
        NtesParser.Result o = NtesParser.parse(old);
        eq("old layout stops", o.stops.size(), 2);
        eq("old layout real times", o.stops.get(0).adep + "/" + o.stops.get(1).aarr, "385/491");
        eq("old layout delay", o.live.delay, 10);

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }

    static String find(NtesParser.Result r, String label) {
        for (NtesParser.Coach c : r.coaches) if (c.label.equals(label)) return c.type + " " + c.label + " @" + c.pos;
        return "none";
    }
}
