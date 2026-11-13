package com.dhbhojani.stationalarm;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Indian Railways' own page (PNR status or NTES train schedule) inside the app. You use it as usual,
 * including the captcha; "Use this result" reads what the page shows. Nothing runs in the background.
 */
public class LookupActivity extends Activity {
    static final String PNR_URL = "https://www.indianrail.gov.in/enquiry/PNR/PnrEnquiry.html?locale=en";
    static final String NTES_URL = "https://enquiry.indianrail.gov.in/mntes/";

    WebView web;
    String mode, arg;

    /** What the page shows: every table row (cells joined by |) and the visible text. */
    static final String SNAP = "(function(){var rows=[];document.querySelectorAll('tr').forEach(function(tr){var c=[];tr.querySelectorAll('th,td').forEach(function(td){c.push((td.innerText||'').replace(/\\s+/g,' ').trim())});if(c.join('').length)rows.push(c.join(' | '))});" +
            "return JSON.stringify({url:location.href,title:document.title,rows:rows.slice(0,400),text:(document.body?document.body.innerText:'').slice(0,20000)})})()";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mode = getIntent().getStringExtra("mode");
        arg = getIntent().getStringExtra("arg");
        if (arg == null) arg = "";
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView hint = new TextView(this);
        hint.setPadding(dp(16), dp(12), dp(16), dp(12));
        hint.setBackgroundColor(Color.parseColor("#0F172A"));
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setText("pnr".equals(mode)
                ? "Enter PNR " + arg + " and the captcha, then tap \"Use this result\" once the status shows."
                : "Open Spot Your Train, enter train " + arg + ", then tap \"Use this result\" once the station list shows.");
        root.addView(hint);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { prefill(); }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(12), dp(10), dp(12), dp(14));
        bar.setBackgroundColor(Color.parseColor("#0F172A"));
        Button use = btn("Use this result", "#FACC15", "#0F172A");
        Button copy = btn("Copy for Claude", "#1E293B", "#FFFFFF");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.setMargins(0, 0, dp(8), 0);
        bar.addView(use, lp);
        bar.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(bar);
        setContentView(root);
        use.setOnClickListener(v -> web.evaluateJavascript(SNAP, this::useResult));
        copy.setOnClickListener(v -> web.evaluateJavascript(SNAP, this::copyForClaude));
        web.loadUrl("pnr".equals(mode) ? PNR_URL : NTES_URL);
    }

    void prefill() {
        if (arg.isEmpty()) return;
        String sel = "pnr".equals(mode) ? "input[maxlength='10'],input[name*='pnr' i],input[id*='pnr' i]" : "input[name*='train' i],input[id*='train' i]";
        web.evaluateJavascript("(function(){var e=document.querySelector(\"" + sel + "\");if(!e||e.value)return;" +
                "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e," + JSONObject.quote(arg) + ");" +
                "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}))})()", null);
    }

    static String unquote(String js) {
        try { return (String) new org.json.JSONTokener(js).nextValue(); } catch (Exception e) { return "{}"; }
    }

    void useResult(String raw) {
        try {
            JSONObject snap = new JSONObject(unquote(raw));
            JSONObject res = "pnr".equals(mode) ? parsePnr(snap) : parseSchedule(snap);
            if (res == null) {
                Toast.makeText(this, "Couldn't read the result here yet. Tap \"Copy for Claude\" and send it, so the app can learn this page.", Toast.LENGTH_LONG).show();
                return;
            }
            res.put("mode", mode);
            TripService.prefs(this).edit().putString("lookup", res.toString()).apply();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't read the page", Toast.LENGTH_SHORT).show();
        }
    }

    void copyForClaude(String raw) {
        String s = unquote(raw).replaceAll("\\b(\\d{6})(\\d{4})\\b", "XXXXXX$2"); // PNR and phone numbers: keep last 4
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Station Alarm page", s));
        Toast.makeText(this, "Copied – paste it to Claude", Toast.LENGTH_SHORT).show();
    }

    /* ---------------- PNR ---------------- */
    static final Pattern ST_STATUS = Pattern.compile("\\b(CNF|RAC|WL|GNWL|RLWL|PQWL|TQWL|RSWL|NOSB|CAN|CANCELLED|CONFIRMED)\\b[\\s/,-]*([A-Z]{1,2}\\d{1,2})?[\\s/,-]*(\\d{1,3})?(?:[\\s/,-]*(LB|MB|UB|SL|SU|WS|AS|NC))?");

    JSONObject parsePnr(JSONObject snap) throws Exception {
        String text = snap.optString("text");
        JSONArray rows = snap.optJSONArray("rows");
        JSONObject r = new JSONObject();
        Matcher m = Pattern.compile("\\b(\\d{10})\\b").matcher(text);
        if (m.find()) r.put("pnr", m.group(1)); else if (arg.matches("\\d{10}")) r.put("pnr", arg);
        m = Pattern.compile("\\b(\\d{5})\\b\\s*[-/]?\\s*([A-Z][A-Za-z .&-]{3,40}(?:EXP|EXPRESS|MAIL|SF|RAJDHANI|SHATABDI|DURONTO|SPL|SPECIAL|VANDE BHARAT|TEJAS|GARIB RATH)?)").matcher(text);
        if (m.find()) { r.put("train", m.group(1)); r.put("trainName", m.group(2).trim()); }
        m = Pattern.compile("\\b(\\d{1,2})[-/ ](\\d{1,2}|[A-Za-z]{3})[-/ ](\\d{4})\\b").matcher(text);
        if (m.find()) r.put("date", m.group(0));
        m = Pattern.compile("\\b(1A|2A|3A|3E|SL|CC|EC|2S|FC|EV|EA)\\b").matcher(text);
        if (m.find()) r.put("cls", m.group(1));
        if (Pattern.compile("chart\\s+not\\s+prepared", Pattern.CASE_INSENSITIVE).matcher(text).find()) r.put("chart", "Chart not prepared");
        else if (Pattern.compile("chart\\s+prepared", Pattern.CASE_INSENSITIVE).matcher(text).find()) r.put("chart", "Chart prepared");
        // from / to: station codes in brackets, in page order
        Data.load(this);
        List<String> codes = new ArrayList<>();
        m = Pattern.compile("\\(([A-Z]{2,5})\\)|-\\s*([A-Z]{2,5})\\b").matcher(text);
        while (m.find()) {
            String c = m.group(1) != null ? m.group(1) : m.group(2);
            if (Data.byCode(c) != null && !codes.contains(c)) codes.add(c);
        }
        if (codes.size() >= 2) { r.put("from", codes.get(0)); r.put("to", codes.get(codes.size() - 1)); }
        JSONArray pax = new JSONArray();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            String row = rows.getString(i);
            if (!row.matches("(?is).*passenger.*|^\\s*\\d+\\s*\\|.*")) continue;
            List<String> st = new ArrayList<>();
            for (String cell : row.split("\\|")) {
                Matcher sm = ST_STATUS.matcher(cell.trim().toUpperCase(Locale.ROOT));
                if (sm.find()) st.add(sm.group(0).replaceAll("\\s+", " ").trim());
            }
            if (st.isEmpty()) continue;
            pax.put(new JSONObject().put("booked", st.get(0)).put("now", st.get(st.size() - 1)));
        }
        r.put("pax", pax);
        if (pax.length() > 0) {
            Matcher cm = Pattern.compile("([A-Z]{1,2}\\d{1,2})[\\s/,-]*(\\d{1,3})").matcher(pax.getJSONObject(0).getString("now"));
            if (cm.find()) { r.put("coach", cm.group(1)); r.put("berth", cm.group(2)); }
        }
        r.put("checked", System.currentTimeMillis());
        return pax.length() == 0 && !r.has("train") ? null : r;
    }

    /* ---------------- NTES train schedule ---------------- */
    JSONObject parseSchedule(JSONObject snap) throws Exception {
        Data.load(this);
        // NTES "Spot Your Train" lists the stopping stations in its page text
        NtesParser.Result nr = NtesParser.parse(snap.optString("text"));
        if (nr.stops.size() >= 2) {
            JSONArray arr = new JSONArray();
            for (NtesParser.Stop x : nr.stops) {
                Data.Station st = Data.byCode(x.code);
                if (st == null) continue; // a station we have no location for
                int t = x.arr >= 0 ? x.arr : x.dep;
                arr.put(new JSONObject().put("c", st.code).put("n", st.name).put("la", st.lat).put("lo", st.lon).put("t", t).put("h", 1));
            }
            if (arr.length() >= 2) {
                String num = nr.number.isEmpty() ? arg : nr.number;
                String name = nr.name.isEmpty() ? "Train " + num : num + " " + titleCase(nr.name);
                return new JSONObject().put("num", num).put("name", name + " (NTES)").put("stops", arr);
            }
        }
        JSONArray rows = snap.optJSONArray("rows");
        if (rows == null) return null;
        Map<String, JSONObject> stops = new LinkedHashMap<>();
        Pattern code = Pattern.compile("\\b([A-Z]{2,5})\\b");
        Pattern time = Pattern.compile("\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b");
        int day = 1, prevMin = -1;
        for (int i = 0; i < rows.length(); i++) {
            String row = rows.getString(i);
            Data.Station st = null;
            Matcher cm = code.matcher(row);
            while (cm.find()) { st = Data.byCode(cm.group(1)); if (st != null) break; }
            if (st == null || stops.containsKey(st.code)) continue;
            List<Integer> ts = new ArrayList<>();
            Matcher tm = time.matcher(row);
            while (tm.find()) ts.add(Integer.parseInt(tm.group(1)) * 60 + Integer.parseInt(tm.group(2)));
            Matcher dm = Pattern.compile("(?i)day\\s*(\\d)|\\|\\s*(\\d)\\s*\\|").matcher(row);
            int t = ts.isEmpty() ? -1 : ts.get(0);
            if (dm.find()) day = Integer.parseInt(dm.group(1) != null ? dm.group(1) : dm.group(2));
            else if (t >= 0 && prevMin >= 0 && t < prevMin - 60) day++;
            if (t >= 0) prevMin = ts.get(ts.size() - 1);
            boolean halt = ts.size() >= 2 && !ts.get(0).equals(ts.get(1)) || stops.isEmpty();
            stops.put(st.code, new JSONObject().put("c", st.code).put("n", st.name).put("la", st.lat).put("lo", st.lon)
                    .put("t", t < 0 ? -1 : (day - 1) * 1440 + t).put("h", halt ? 1 : 0));
        }
        if (stops.size() < 2) return null;
        JSONArray arr = new JSONArray(stops.values());
        arr.getJSONObject(arr.length() - 1).put("h", 1);
        return new JSONObject().put("num", arg).put("name", "Train " + arg + " (from NTES)").put("stops", arr);
    }

    static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w.length() <= 4 && w.equals(w.replaceAll("[aeiou]", "")) ? w.toUpperCase(Locale.ROOT) : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }

    Button btn(String t, String bg, String fg) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor(fg));
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(bg));
        d.setCornerRadius(dp(14));
        b.setBackground(d);
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
