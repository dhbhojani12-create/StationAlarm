package com.dhbhojani.stationalarm;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.app.AlertDialog;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    WebView web;
    String pendingSaved, pendingTicket;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "SA");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                    .setPositiveButton("Yes", (d, w) -> r.confirm())
                    .setNegativeButton("No", (d, w) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
            @Override public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg)
                    .setPositiveButton("OK", (d, w) -> r.confirm())
                    .setOnCancelListener(d -> r.confirm()).show();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                return true;
            }
        });
        pendingSaved = getIntent().getStringExtra("saved");
        pendingTicket = getIntent().getStringExtra("ticket");
        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String sv = i.getStringExtra("saved");
        if (sv != null) web.evaluateJavascript("window.startSaved&&startSaved(" + JSONObject.quote(sv) + ")", null);
        String tk = i.getStringExtra("ticket");
        if (tk != null) web.evaluateJavascript("window.openTicket&&openTicket(" + JSONObject.quote(tk) + ")", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onResumeApp&&onResumeApp()", null);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.goBack?goBack():false", r -> { if (!"true".equals(r)) MainActivity.super.onBackPressed(); });
    }

    /** WhatsApp with the message already written; you pick the group and press Send. */
    static Intent waIntent(Context c, String text) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            try { c.getPackageManager().getPackageInfo(pkg, 0); i.setPackage(pkg); break; } catch (Exception ignored) { }
        }
        if (i.getPackage() == null) i = Intent.createChooser(i, "Send with");
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    class Bridge {
        @JavascriptInterface public String lines() { return Data.asset(MainActivity.this, "lines.json"); }

        @JavascriptInterface public String search(String q) { return Data.search(MainActivity.this, q).toString(); }

        @JavascriptInterface public String train(String num) {
            JSONObject t = Data.train(MainActivity.this, num);
            return t == null ? "" : t.toString();
        }

        @JavascriptInterface public String perms() {
            boolean loc = hasLocation();
            boolean notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean batt = pm.isIgnoringBatteryOptimizations(getPackageName());
            try { return new JSONObject().put("loc", loc).put("notif", notif).put("batt", batt).toString(); } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void askPerms() {
            runOnUiThread(() -> {
                List<String> p = new ArrayList<>();
                p.add(Manifest.permission.ACCESS_FINE_LOCATION);
                p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
                requestPermissions(p.toArray(new String[0]), 7);
            });
        }

        @JavascriptInterface public void batterySettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                }
            });
        }

        @JavascriptInterface public boolean start(String tripJson) {
            if (!hasLocation()) { askPerms(); return false; }
            TripService.prefs(MainActivity.this).edit().putString("trip", tripJson).putString("state", "{\"active\":true,\"fix\":0}").remove("last").apply();
            runOnUiThread(() -> TripService.start(MainActivity.this));
            return true;
        }

        @JavascriptInterface public void stop() {
            runOnUiThread(() -> startService(new Intent(MainActivity.this, TripService.class).setAction(TripService.ACT_STOP)));
        }

        @JavascriptInterface public String trip() { return TripService.prefs(MainActivity.this).getString("trip", ""); }

        @JavascriptInterface public String state() { return TripService.prefs(MainActivity.this).getString("state", "{\"active\":false}"); }

        @JavascriptInterface public String last() { return TripService.prefs(MainActivity.this).getString("last", ""); }

        @JavascriptInterface public void clearLast() { TripService.prefs(MainActivity.this).edit().remove("last").apply(); }

        @JavascriptInterface public String log() { return TripService.prefs(MainActivity.this).getString("log", "[]"); }

        /** Edits the running trip (extra alerts); the service picks it up on its next fix. */
        @JavascriptInterface public void updateTrip(String tripJson) {
            TripService.prefs(MainActivity.this).edit().putString("trip", tripJson).apply();
        }

        @JavascriptInterface public void setSaved(String json) {
            TripService.prefs(MainActivity.this).edit().putString("saved", json).apply();
            TripWidget.refresh(MainActivity.this);
        }

        @JavascriptInterface public String takePendingSaved() { String s = pendingSaved; pendingSaved = null; return s == null ? "" : s; }

        @JavascriptInterface public void share(String text) { runOnUiThread(() -> startActivity(waIntent(MainActivity.this, text))); }

        @JavascriptInterface public void lookup(String mode, String arg) {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, LookupActivity.class).putExtra("mode", mode).putExtra("arg", arg)));
        }

        @JavascriptInterface public String takeLookup() {
            String s = TripService.prefs(MainActivity.this).getString("lookup", "");
            TripService.prefs(MainActivity.this).edit().remove("lookup").apply();
            return s;
        }

        @JavascriptInterface public void remind(String id, double at, String title, String text) {
            ReminderReceiver.schedule(MainActivity.this, id, (long) at, title, text);
        }

        @JavascriptInterface public void unremind(String id) { ReminderReceiver.cancel(MainActivity.this, id); }

        @JavascriptInterface public void call(String num) {
            runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + num))));
        }

        @JavascriptInterface public String takePendingTicket() { String s = pendingTicket; pendingTicket = null; return s == null ? "" : s; }

        @JavascriptInterface public void toast(String t) { runOnUiThread(() -> Toast.makeText(MainActivity.this, t, Toast.LENGTH_SHORT).show()); }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        web.evaluateJavascript("window.onPerms&&onPerms()", null);
    }
}
