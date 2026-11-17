package com.dhbhojani.stationalarm;

/**
 * Off-device checks for the page parsers. Run from the android/ folder:
 *   javac -d /tmp/sa-tests app/src/main/java/com/dhbhojani/stationalarm/{NtesParser,PnrParser}.java tests/ParserTests.java
 *   java -cp /tmp/sa-tests com.dhbhojani.stationalarm.ParserTests
 * The page text below follows the layout described in NtesParser; check it against a real "Copy for Claude" capture.
 */
public class ParserTests {
    static int fails = 0;
    static void eq(String what, Object got, Object want) {
        boolean ok = String.valueOf(got).equals(String.valueOf(want));
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : "  got=" + got + " want=" + want));
    }

    static String stop(String arrBlock, String name, String code, String pf, int km, String depBlock) {
        return arrBlock + "\n" + name + "\n\n" + code + " PF " + pf + " Coach Position\n" + km + " KMs\n" + depBlock + "\n";
    }

    public static void main(String[] a) {
        String text = "Train No./Name 12009 SHATABDI EXP\n"
            + stop("", "MUMBAI CENTRAL", "MMCT", "2", 0, "06:25 17-Nov\n06:25 17-Nov*\nDeparted")
            + stop("08:02 17-Nov\n08:11 17-Nov*\nArrived", "BHARUCH JN", "BH", "3*", 392, "08:07 17-Nov\n08:17 17-Nov*\nDeparted")
            + stop("09:40 17-Nov\n09:52 17-Nov*", "VADODARA JN", "BRC", "1", 392, "09:45 17-Nov")
            + stop("12:00 17-Nov", "AHMEDABAD JN", "ADI", "4", 491, "");
        NtesParser.Result r = NtesParser.parse(text);
        eq("train number", r.number, "12009");
        eq("train name", r.name, "SHATABDI EXP");
        eq("stop count", r.stops.size(), 4);
        eq("platform plain", r.stops.get(0).pf, "2");
        eq("platform live star dropped", r.stops.get(1).pf, "3");
        eq("sched dep MMCT (06:25)", r.stops.get(0).dep, 385);
        eq("actual dep MMCT", r.stops.get(0).adep, 385);
        eq("sched arr BH (08:02)", r.stops.get(1).arr, 482);
        eq("actual arr BH (08:11)", r.stops.get(1).aarr, 491);
        eq("actual dep BH (08:17)", r.stops.get(1).adep, 497);
        eq("sched arr BRC", r.stops.get(2).arr, 580);
        eq("actual arr BRC (09:52)", r.stops.get(2).aarr, 592);
        eq("BRC not departed yet", r.stops.get(2).adep, -1);
        eq("ADI has no live time", r.stops.get(3).aarr, -1);
        eq("live found", r.live.has, true);
        eq("last reached stop = BRC", r.live.lastIdx, 2);
        eq("at station", r.live.atStation, true);
        eq("delay at BRC (592-580)", r.live.delay, 12);

        // before the train starts: no live times, no delay
        NtesParser.Result q = NtesParser.parse(text.replace("\n06:25 17-Nov*", "").replace("\n08:11 17-Nov*", "").replace("\n08:17 17-Nov*", "").replace("\n09:52 17-Nov*", ""));
        eq("no live before start", q.live.has, false);
        eq("schedule still read", q.stops.get(3).arr, 720);

        // overnight: day counter carries on
        String night = "Train No./Name 12951 MUMBAI RAJDHANI\n"
            + stop("", "MUMBAI CENTRAL", "MMCT", "1", 0, "17:00 17-Nov\n17:05 17-Nov*")
            + stop("00:30 18-Nov\n00:50 18-Nov*", "KOTA JN", "KOTA", "2", 900, "00:35 18-Nov");
        NtesParser.Result n = NtesParser.parse(night);
        eq("overnight sched arr (day 2, 00:30)", n.stops.get(1).arr, 1470);
        eq("overnight delay", n.live.delay, 20);

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
