package com.dhbhojani.stationalarm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the indianrail.gov.in PNR result from its table rows ("a | b | c"). Plain Java so it can be tested off-device. */
public class PnrParser {
    public static class Result {
        public Map<String, String> f = new LinkedHashMap<>();
        public List<String[]> pax = new ArrayList<>(); // {booked, now}
    }

    static String[] cells(String row) {
        String[] c = row.split("\\|", -1);
        for (int i = 0; i < c.length; i++) c[i] = c[i].trim();
        return c;
    }

    static int col(String[] head, String start) {
        for (int i = 0; i < head.length; i++) if (head[i].toLowerCase().startsWith(start)) return i;
        return -1;
    }

    static String at(String[] v, int i) { return i >= 0 && i < v.length && !v[i].isEmpty() ? v[i] : null; }

    public static Result parse(List<String> rows) {
        Result r = new Result();
        for (int i = 0; i < rows.size(); i++) {
            String[] h = cells(rows.get(i));
            String first = h[0].toLowerCase();
            if (first.startsWith("train number") && i + 1 < rows.size()) {
                String[] v = cells(rows.get(i + 1));
                if (!v[0].matches("\\d{5}")) continue;
                put(r, "train", at(v, col(h, "train number")));
                put(r, "trainName", at(v, col(h, "train name")));
                put(r, "date", at(v, col(h, "boarding date")));
                put(r, "origin", at(v, col(h, "from")));
                put(r, "to", at(v, col(h, "reserved upto")));
                if (!r.f.containsKey("to")) put(r, "to", at(v, col(h, "to")));
                put(r, "from", at(v, col(h, "boarding point")));
                if (!r.f.containsKey("from")) put(r, "from", at(v, col(h, "from")));
                put(r, "cls", at(v, col(h, "class")));
            } else if (first.startsWith("total fare") && i + 1 < rows.size()) {
                String[] v = cells(rows.get(i + 1));
                put(r, "fare", at(v, col(h, "total fare")));
                put(r, "chart", at(v, col(h, "charting")));
                put(r, "remarks", at(v, col(h, "remarks")));
                put(r, "trainStatus", at(v, col(h, "train status")));
            } else if (first.matches("passenger\\s*\\d+") && h.length >= 2) {
                String booked = h[1];
                String now = h.length >= 3 && !h[h.length - 1].isEmpty() ? h[h.length - 1] : booked;
                r.pax.add(new String[]{booked, now});
            }
        }
        if (!r.pax.isEmpty()) {
            // e.g. "CNF/B2/34/LB" -> coach B2, berth 34
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:CNF|RAC)?/?([A-Z]{1,2}\\d{1,2})/(\\d{1,3})").matcher(r.pax.get(0)[1].toUpperCase());
            if (m.find()) { r.f.put("coach", m.group(1)); r.f.put("berth", m.group(2)); }
        }
        return r;
    }

    static void put(Result r, String k, String v) { if (v != null) r.f.put(k, v); }

}
