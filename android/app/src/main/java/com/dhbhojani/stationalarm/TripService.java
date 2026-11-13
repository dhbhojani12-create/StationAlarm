package com.dhbhojani.stationalarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Follows one trip with the phone's own location (works with no network) and posts:
 * an ongoing "stops left / time left" notification, the alert before your stop,
 * small alerts for extra spots, and the arrival notification with "Tell family".
 * The trip ends by itself once you have left the destination.
 */
public class TripService extends Service {
    static final String PREFS = "sa";
    static final String CH_TRIP = "trip", CH_ALERT = "alerts";
    static final int ID_ONGOING = 1, ID_ALERT = 2, ID_ARRIVE = 3, ID_EXTRA = 10;
    static final String ACT_STOP = "stop";

    LocationManager lm;
    final Handler h = new Handler(Looper.getMainLooper());
    JSONObject trip;
    JSONArray st;
    double[] cum;
    int dest;
    boolean local, noRoute;
    long band = -1;

    // live values
    double progress = -1, remaining = -1, travelled = 0, speedMs = 0;
    Integer delayMin = null;
    Double etaMin = null;
    int nextIdx = -1, stopsAway = -1;
    String near = "";
    Location last;
    long lastFix = 0, base = -1, arrivedAt = 0, startedAt;
    boolean alerted, arrived, reversedChecked;
    final Set<Integer> extraDone = new HashSet<>();
    final ArrayDeque<double[]> hist = new ArrayDeque<>(); // [time ms, progress m]
    String fromName = null;

    void reset() {
        trip = null; st = null; cum = null; progress = -1; remaining = -1; travelled = 0; speedMs = 0;
        delayMin = null; etaMin = null; nextIdx = -1; stopsAway = -1; near = ""; last = null; lastFix = 0;
        base = -1; arrivedAt = 0; alerted = false; arrived = false; reversedChecked = false; fromName = null;
        extraDone.clear(); hist.clear(); band = -1;
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, MODE_PRIVATE); }

    static void start(Context c) {
        Intent i = new Intent(c, TripService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_TRIP, "Trip in progress", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel a = new NotificationChannel(CH_ALERT, "Station alerts", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Before your stop and on arrival. Uses your normal notification sound.");
        nm.createNotificationChannel(a);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_STOP.equals(intent.getAction())) { finish(true); return START_NOT_STICKY; }
        Notification n = ongoing("Starting trip…", "Waiting for your location");
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(ID_ONGOING, n);
        if (intent != null || trip == null) { // a new trip (or a restart): start fresh from what the app saved
            reset();
            if (!loadTrip()) { stopSelf(); return START_NOT_STICKY; }
        }
        listen(20000);
        return START_STICKY;
    }

    boolean loadTrip() {
        try {
            String s = prefs(this).getString("trip", null);
            if (s == null) return false;
            trip = new JSONObject(s);
            st = trip.getJSONArray("st");
            dest = trip.getInt("dest");
            local = "local".equals(trip.optString("kind"));
            noRoute = trip.optBoolean("noRoute", false) || st.length() < 2;
            startedAt = trip.optLong("started", System.currentTimeMillis());
            alerted = trip.optBoolean("alerted", false);
            reversedChecked = trip.optBoolean("dirOk", false) || noRoute || !local;
            JSONArray ex = trip.optJSONArray("done");
            if (ex != null) for (int i = 0; i < ex.length(); i++) extraDone.add(ex.getInt(i));
            buildCum();
            return true;
        } catch (Exception e) { return false; }
    }

    void buildCum() throws Exception {
        cum = new double[st.length()];
        for (int i = 1; i < st.length(); i++) {
            JSONObject a = st.getJSONObject(i - 1), b = st.getJSONObject(i);
            cum[i] = cum[i - 1] + Data.dist(a.getDouble("la"), a.getDouble("lo"), b.getDouble("la"), b.getDouble("lo"));
        }
    }

    void saveTrip() {
        try {
            trip.put("st", st).put("dest", dest).put("alerted", alerted).put("dirOk", reversedChecked);
            JSONArray d = new JSONArray();
            for (int i : extraDone) d.put(i);
            trip.put("done", d);
            prefs(this).edit().putString("trip", trip.toString()).apply();
        } catch (Exception ignored) { }
    }

    /* ---------------- location ---------------- */
    final LocationListener ll = new LocationListener() {
        @Override public void onLocationChanged(Location l) { onFix(l); }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };

    void listen(long every) {
        if (every == band) return;
        band = every;
        try {
            lm.removeUpdates(ll);
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, every, 0, ll, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, Math.max(every, 15000), 0, ll, Looper.getMainLooper());
        } catch (SecurityException e) {
            post(ID_ALERT, "Location is off for Station Alarm", "Allow location in the app so it can follow your trip.", null, null);
        }
    }

    void onFix(Location l) {
        try {
            float acc = l.hasAccuracy() ? l.getAccuracy() : 9999;
            if (acc > (local ? 600 : 3000)) return;
            long now = System.currentTimeMillis();
            if (last != null) {
                double d = Data.dist(last.getLatitude(), last.getLongitude(), l.getLatitude(), l.getLongitude());
                if (d > acc) travelled += d;
            }
            last = l;
            lastFix = now;
            if (fromName == null) {
                Data.Station s = Data.nearest(this, l.getLatitude(), l.getLongitude());
                fromName = s == null ? "" : s.name;
                if (local && !noRoute) fromName = st.getJSONObject(nearestIdx(l)).getString("n");
            }
            try { // extra alerts can be added from the app while the trip runs
                JSONObject cur = new JSONObject(prefs(this).getString("trip", "{}"));
                if (cur.has("extra")) trip.put("extra", cur.getJSONArray("extra"));
                if (cur.has("share")) trip.put("share", cur.getString("share")); else trip.remove("share");
            } catch (Exception ignored) { }
            compute(l, acc, now);
            write(now);
            // check less often when far away, to save battery
            listen(remaining > 30000 ? 60000 : remaining > 5000 ? 20000 : 5000);
        } catch (Exception ignored) { }
    }

    int nearestIdx(Location l) throws Exception {
        int bi = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < st.length(); i++) {
            JSONObject s = st.getJSONObject(i);
            double d = Data.dist(l.getLatitude(), l.getLongitude(), s.getDouble("la"), s.getDouble("lo"));
            if (d < bd) { bd = d; bi = i; }
        }
        return bi;
    }

    void compute(Location l, float acc, long now) throws Exception {
        JSONObject d = st.getJSONObject(dest);
        double toDest = Data.dist(l.getLatitude(), l.getLongitude(), d.getDouble("la"), d.getDouble("lo"));

        if (noRoute) {
            remaining = toDest * 1.15; // rail is rarely straight
            progress = -remaining;
            Data.Station s = Data.nearest(this, l.getLatitude(), l.getLongitude());
            near = s == null ? "" : (Data.dist(l.getLatitude(), l.getLongitude(), s.lat, s.lon) < 8000 ? "Near " + s.name : "Between stations");
        } else {
            project(l);
            if (!reversedChecked) {
                reversedChecked = true;
                if (progress > cum[dest]) reverse(l);
                saveTrip();
            }
            remaining = Math.max(0, cum[dest] - progress);
            double ahead = local ? 150 : 400;
            nextIdx = st.length();
            for (int i = 0; i < st.length(); i++) if (cum[i] > progress + ahead) { nextIdx = i; break; }
            stopsAway = dest - nextIdx;
            int ni = nearestIdx(l);
            JSONObject ns = st.getJSONObject(ni);
            double nd = Data.dist(l.getLatitude(), l.getLongitude(), ns.getDouble("la"), ns.getDouble("lo"));
            if (nd < (local ? 400 : 3000)) near = (local && nd < 250 ? "At " : "Near ") + ns.getString("n");
            else if (nextIdx > 0 && nextIdx < st.length())
                near = "Between " + st.getJSONObject(nextIdx - 1).getString("n") + " and " + st.getJSONObject(nextIdx).getString("n");
            else near = "Near " + ns.getString("n");
        }

        // speed from progress over the last few minutes
        hist.addLast(new double[]{now, progress});
        while (!hist.isEmpty() && now - hist.peekFirst()[0] > 15 * 60000) hist.pollFirst();
        double[] old = null;
        for (double[] p : hist) if (now - p[0] >= 3 * 60000) old = p; else break;
        if (old != null) speedMs = Math.max(0, (progress - old[1]) / ((now - old[0]) / 1000.0));
        else if (l.hasSpeed()) speedMs = l.getSpeed();

        // running late / on time, from the timetable
        delayMin = null;
        Double schedHere = sched(progress);
        if (schedHere != null) {
            if (base < 0) base = pickBase(now, schedHere);
            delayMin = (int) Math.round((now - base) / 60000.0 - schedHere);
        }
        etaMin = null;
        Double schedDest = noRoute ? null : sched(cum[dest]);
        if (schedHere != null && schedDest != null) etaMin = Math.max(0, schedDest - schedHere);
        if (speedMs > 2.5 && (etaMin == null || local)) etaMin = remaining / speedMs / 60.0;
        else if (etaMin == null && speedMs > 2.5) etaMin = remaining / speedMs / 60.0;

        // the alert before your stop
        if (!alerted && !arrived) {
            JSONObject a = trip.optJSONObject("alert");
            String type = a == null ? (local ? "stops" : "min") : a.optString("type", "stops");
            double v = a == null ? (local ? 1 : 30) : a.optDouble("v", 1);
            boolean fire;
            if ("km".equals(type)) fire = remaining <= v * 1000;
            else if ("min".equals(type)) fire = (etaMin != null && etaMin <= v) || (etaMin == null && remaining <= v * 600);
            else fire = !noRoute ? stopsAway <= v - 1 : remaining <= 2500;
            if (fire && remaining > (local ? 300 : 800)) {
                alerted = true;
                saveTrip();
                alertBeforeStop();
            }
        }

        // extra spots you added on the route
        JSONArray extra = trip.optJSONArray("extra");
        if (extra != null && !noRoute) for (int k = 0; k < extra.length(); k++) {
            int e = extra.getInt(k);
            if (e < 0 || e >= st.length() || extraDone.contains(e) || e == dest) continue;
            double left = cum[e] - progress;
            if (left > 0 && (local ? e - nextIdx <= 0 : left <= 3000)) {
                extraDone.add(e);
                saveTrip();
                post(ID_EXTRA + e, "Coming up: " + st.getJSONObject(e).getString("n"), "The spot you asked to be told about.", null, null);
            }
        }

        // arrival, or a missed stop
        if (!arrived) {
            boolean there = toDest < (local ? 350 : 900) && acc < (local ? 250 : 1500);
            boolean passed = !noRoute && progress > cum[dest] + (local ? 900 : 3000);
            if (there || passed) {
                arrived = true;
                arrivedAt = now;
                onArrive(passed);
            }
        } else if (toDest > (local ? 1000 : 3000) || now - arrivedAt > 15 * 60000) {
            finish(false); // trip ends by itself once you have left the station
        }
    }

    /** Finds where you are along the route (metres from its first station). */
    void project(Location l) throws Exception {
        double plat = l.getLatitude(), plon = l.getLongitude();
        double kx = Math.cos(Math.toRadians(plat)) * 111320, ky = 110540;
        double best = Double.MAX_VALUE, bp = 0;
        for (int i = 0; i + 1 < st.length(); i++) {
            JSONObject a = st.getJSONObject(i), b = st.getJSONObject(i + 1);
            double ax = (a.getDouble("lo") - plon) * kx, ay = (a.getDouble("la") - plat) * ky;
            double bx = (b.getDouble("lo") - plon) * kx, by = (b.getDouble("la") - plat) * ky;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double cx = ax + t * dx, cy = ay + t * dy, dd = Math.hypot(cx, cy);
            if (dd < best) { best = dd; bp = cum[i] + t * (cum[i + 1] - cum[i]); }
        }
        progress = bp;
    }

    /** Local lines run both ways: turn the list round if you are heading the other way. */
    void reverse(Location l) throws Exception {
        JSONArray r = new JSONArray();
        for (int i = st.length() - 1; i >= 0; i--) r.put(st.get(i));
        int n = st.length();
        st = r;
        dest = n - 1 - dest;
        JSONArray ex = trip.optJSONArray("extra");
        if (ex != null) {
            JSONArray e2 = new JSONArray();
            for (int i = 0; i < ex.length(); i++) e2.put(n - 1 - ex.getInt(i));
            trip.put("extra", e2);
        }
        buildCum();
        project(l);
    }

    /** Scheduled minutes (from day 1 midnight) at a point on the route, if the timetable has them. */
    Double sched(double at) throws Exception {
        if (noRoute || !st.getJSONObject(0).has("t")) return null;
        int i = 0;
        while (i + 1 < st.length() && cum[i + 1] < at) i++;
        int a = i, b = Math.min(i + 1, st.length() - 1);
        while (a > 0 && st.getJSONObject(a).optInt("t", -1) < 0) a--;
        while (b < st.length() - 1 && st.getJSONObject(b).optInt("t", -1) < 0) b++;
        int ta = st.getJSONObject(a).optInt("t", -1), tb = st.getJSONObject(b).optInt("t", -1);
        if (ta < 0 || tb < 0) return null;
        if (b == a || cum[b] == cum[a]) return (double) ta;
        double f = Math.max(0, Math.min(1, (at - cum[a]) / (cum[b] - cum[a])));
        return ta + f * (tb - ta);
    }

    /** The midnight of the train's first day that makes today's delay make sense. */
    long pickBase(long now, double schedMin) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long best = c.getTimeInMillis();
        double bestScore = Double.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            long b = c.getTimeInMillis() - k * 86400000L;
            double delay = (now - b) / 60000.0 - schedMin;
            double score = delay < -60 ? 1e9 : Math.abs(delay);
            if (score < bestScore) { bestScore = score; best = b; }
        }
        return best;
    }

    /* ---------------- notifications ---------------- */
    String destName() { try { return st.getJSONObject(dest).getString("n"); } catch (Exception e) { return "your stop"; } }

    void alertBeforeStop() {
        String d = destName();
        String title, text;
        if (local && !noRoute && stopsAway <= 0) {
            title = d + " is next";
            String prev = "";
            try { if (dest > 0) prev = st.getJSONObject(dest - 1).getString("n"); } catch (Exception ignored) { }
            text = (prev.isEmpty() ? "" : "Leaving " + prev + ". ") + "Get ready.";
        } else if (etaMin != null) {
            title = d + " in about " + fmtMin(etaMin);
            text = Math.round(remaining / 1000.0) + " km to go. Get ready.";
        } else {
            title = d + " is coming up";
            text = Math.round(remaining / 1000.0) + " km to go.";
        }
        Intent fam = null;
        if (!local) fam = MainActivity.waIntent(this, "About " + (etaMin != null ? fmtMin(etaMin) : "30 minutes") + " from " + d + ".");
        post(ID_ALERT, title, text, fam, fam == null ? null : "Tell family");
    }

    void onArrive(boolean missed) {
        String d = destName();
        String time = new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date()).toLowerCase(Locale.ENGLISH);
        Intent fam = MainActivity.waIntent(this, "Reached " + d + " safely, " + time + ".");
        if (missed) post(ID_ARRIVE, "You passed " + d, nextStopHint(), fam, "Tell family");
        else post(ID_ARRIVE, "You've reached " + d, "Let your family know? Check you have everything.", fam, "Tell family");
        try { trip.put("arrived", now()).put("missed", missed); saveTrip(); } catch (Exception ignored) { }
    }

    String nextStopHint() {
        try {
            for (int i = dest + 1; i < st.length(); i++)
                if (cum[i] > progress && (local || st.getJSONObject(i).optInt("h", 1) == 1))
                    return "Get off at " + st.getJSONObject(i).getString("n") + " and come back.";
        } catch (Exception ignored) { }
        return "Get off at the next stop.";
    }

    static long now() { return System.currentTimeMillis(); }

    void post(int id, String title, String text, Intent action, String actionLabel) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (action != null)
            b.addAction(new Notification.Action.Builder(null, actionLabel,
                    PendingIntent.getActivity(this, 100 + id, action, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        getSystemService(NotificationManager.class).notify(id, b.build());
    }

    Notification ongoing(String title, String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent stop = new Intent(this, TripService.class).setAction(ACT_STOP);
        return new Notification.Builder(this, CH_TRIP)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title).setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null, "Stop trip",
                        PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
    }

    static String fmtMin(double m) {
        long r = Math.round(m);
        if (r < 60) return r + " min";
        return (r / 60) + " h " + (r % 60) + " min";
    }

    /** Writes the live state for the app screen and the widget. */
    void write(long now) {
        String d = destName();
        String title, text;
        if (arrived) { title = "Reached " + d; text = "The trip ends by itself when you leave the station."; }
        else if (local && !noRoute) {
            title = d + " · " + (stopsAway <= 0 ? "next stop" : (stopsAway + 1) + " stops to go");
            text = near;
        } else {
            title = d + " · " + Math.round(remaining / 1000.0) + " km" + (etaMin != null ? " · about " + fmtMin(etaMin) : "");
            text = near + (delayMin != null ? (delayMin > 5 ? " · about " + delayMin + " min late" : delayMin < -5 ? " · running early" : " · on time") : "");
        }
        getSystemService(NotificationManager.class).notify(ID_ONGOING, ongoing(title, text));
        try {
            JSONObject s = new JSONObject()
                    .put("active", true).put("arrived", arrived).put("dest", d)
                    .put("remaining", remaining).put("progress", progress).put("travelled", travelled)
                    .put("speed", speedMs).put("near", near).put("stopsAway", stopsAway).put("next", nextIdx)
                    .put("alerted", alerted).put("fix", lastFix).put("title", title).put("text", text);
            if (etaMin != null) s.put("eta", etaMin);
            if (delayMin != null) s.put("delay", delayMin);
            if (cum != null) { JSONArray c = new JSONArray(); for (double v : cum) c.put(Math.round(v)); s.put("cum", c); }
            prefs(this).edit().putString("state", s.toString()).apply();
        } catch (Exception ignored) { }
        TripWidget.refresh(this);
        publish(now, false);
    }

    long lastPub = 0;

    /** Sends a small update for the family page (station-level only), when sharing is on. */
    void publish(long now, boolean force) {
        final String topic = trip == null ? "" : trip.optString("share", "");
        if (topic.isEmpty() || (!force && now - lastPub < 90000 && !arrived)) return;
        lastPub = now;
        try {
            JSONArray route = new JSONArray();
            if (!noRoute && cum != null) for (int i = 0; i < st.length() && route.length() < 70; i++) {
                JSONObject s = st.getJSONObject(i);
                if (local || s.optInt("h", 1) == 1 || i == dest)
                    route.put(new JSONArray().put(s.getString("n")).put(Math.round(cum[i])).put(s.optInt("t", -1)));
            }
            JSONObject m = new JSONObject().put("v", 1).put("ts", now).put("dest", destName()).put("destM", cum == null || noRoute ? -1 : Math.round(cum[dest]))
                    .put("train", trip.optString("train", "")).put("trainName", trip.optString("trainName", trip.optString("lineName", "")))
                    .put("near", near).put("progress", Math.round(progress)).put("remaining", Math.round(remaining))
                    .put("speed", Math.round(speedMs * 3.6)).put("arrived", arrived).put("route", route);
            if (etaMin != null) m.put("eta", Math.round(etaMin));
            if (delayMin != null) m.put("delay", delayMin);
            final byte[] body = m.toString().getBytes("UTF-8");
            new Thread(() -> {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://ntfy.sh/" + topic).openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(15000);
                    c.setRequestProperty("Cache", "yes");
                    c.getOutputStream().write(body);
                    c.getResponseCode();
                    c.disconnect();
                } catch (Exception ignored) { } // no network: the page shows "last seen"
            }).start();
        } catch (Exception ignored) { }
    }

    /** Ends the trip: saves it to the travel log and stops following your location. */
    void finish(boolean byUser) {
        try { lm.removeUpdates(ll); } catch (Exception ignored) { }
        if (arrived) publish(now(), true);
        SharedPreferences p = prefs(this);
        try {
            if (trip == null) loadTrip();
            if (trip != null && (travelled > 1500 || arrived)) {
                JSONArray log = new JSONArray(p.getString("log", "[]"));
                JSONObject e = new JSONObject()
                        .put("to", destName()).put("from", fromName == null ? "" : fromName)
                        .put("start", startedAt).put("end", now())
                        .put("km", Math.round(travelled / 100.0) / 10.0)
                        .put("kind", trip.optString("kind")).put("train", trip.optString("train", ""))
                        .put("reached", arrived && !trip.optBoolean("missed", false));
                if (delayMin != null) e.put("delay", delayMin);
                log.put(e);
                while (log.length() > 500) log.remove(0);
                p.edit().putString("log", log.toString()).apply();
            }
        } catch (Exception ignored) { }
        SharedPreferences.Editor ed = p.edit().remove("trip").putString("state", "{\"active\":false}");
        if (trip != null) ed.putString("last", trip.toString());
        ed.apply();
        TripWidget.refresh(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try { lm.removeUpdates(ll); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
