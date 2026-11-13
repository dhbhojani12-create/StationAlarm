package com.dhbhojani.stationalarm;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Station and train data bundled in the app (works with no network). */
public class Data {

    public static class Station {
        public final String code, name, state;
        public final double lat, lon;
        Station(String c, String n, double la, double lo, String s) { code = c; name = n; lat = la; lon = lo; state = s; }
    }

    private static List<Station> stations;
    private static Map<String, Station> byCode;

    static synchronized void load(Context ctx) {
        if (stations != null) return;
        List<Station> list = new ArrayList<>();
        Map<String, Station> map = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("stations.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                Station s = new Station(p[0], p[1], Double.parseDouble(p[2]), Double.parseDouble(p[3]), p.length > 4 ? p[4] : "");
                list.add(s);
                map.put(s.code, s);
            }
        } catch (Exception ignored) { }
        stations = list;
        byCode = map;
    }

    static Station byCode(String c) { return byCode == null ? null : byCode.get(c); }

    static String asset(Context ctx, String name) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception ignored) { }
        return sb.toString();
    }

    /** Stations whose name or code matches, best matches first. */
    static JSONArray search(Context ctx, String q) {
        load(ctx);
        JSONArray out = new JSONArray();
        String s = q.trim().toLowerCase(Locale.ROOT);
        if (s.length() < 2) return out;
        List<Station> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (Station st : stations) {
            String n = st.name.toLowerCase(Locale.ROOT);
            if (st.code.equalsIgnoreCase(s) || n.startsWith(s) || n.startsWith("mumbai " + s)) starts.add(st);
            else if (n.contains(s)) contains.add(st);
        }
        starts.addAll(contains);
        for (int i = 0; i < starts.size() && i < 40; i++) {
            Station st = starts.get(i);
            try {
                out.put(new JSONObject().put("c", st.code).put("n", st.name).put("la", st.lat).put("lo", st.lon).put("s", st.state));
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** A train's route from the bundled timetable, or null if not found. */
    static JSONObject train(Context ctx, String number) {
        load(ctx);
        String num = number.trim();
        if (num.isEmpty()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("trains.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith(num + "\t")) continue;
                String[] p = line.split("\t", -1);
                JSONArray stops = new JSONArray();
                for (String part : p[2].split(";")) {
                    String[] f = part.split(",", -1);
                    Station st = byCode.get(f[0]);
                    if (st == null) continue;
                    int t = -1;
                    if (f[1].length() == 4) t = Integer.parseInt(f[1].substring(0, 2)) * 60 + Integer.parseInt(f[1].substring(2));
                    int day = f[2].isEmpty() ? 1 : Integer.parseInt(f[2]);
                    stops.put(new JSONObject()
                            .put("c", st.code).put("n", st.name).put("la", st.lat).put("lo", st.lon)
                            .put("t", t < 0 ? -1 : (day - 1) * 1440 + t)
                            .put("h", "1".equals(f[3]) ? 1 : 0));
                }
                return new JSONObject().put("num", num).put("name", p[1]).put("stops", stops);
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Nearest station anywhere, for "Near X" on trips without a route. */
    static Station nearest(Context ctx, double lat, double lon) {
        load(ctx);
        Station best = null;
        double bd = Double.MAX_VALUE;
        for (Station s : stations) {
            double d = dist(lat, lon, s.lat, s.lon);
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    /** Metres between two points (equirectangular; accurate at these distances). */
    static double dist(double la1, double lo1, double la2, double lo2) {
        double x = Math.toRadians(lo2 - lo1) * Math.cos(Math.toRadians((la1 + la2) / 2));
        double y = Math.toRadians(la2 - la1);
        return 6371000 * Math.hypot(x, y);
    }
}
