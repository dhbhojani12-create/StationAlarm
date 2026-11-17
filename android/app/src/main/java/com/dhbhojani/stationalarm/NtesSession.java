package com.dhbhojani.stationalarm;

import android.content.Context;
import android.content.MutableContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.WebView;

import org.json.JSONObject;

/**
 * The NTES page the person opened and read once. It is kept alive after the lookup screen closes, so the app can press
 * the page's own "Refresh" button and read the new result, instead of the person going back to the NTES site each time.
 * Lives only as long as the app's process: if Android closes the app, the person opens the NTES page once more.
 * All calls are on the main thread.
 */
final class NtesSession {
    interface Done { void run(JSONObject res, String why); }

    static WebView web;
    static String train = "";
    static long lastUpd;                  // "Last Updates On" of the data last read
    static boolean busy;
    static final Handler H = new Handler(Looper.getMainLooper());

    /** Press the visible "Refresh" control next to "Last Updates On" (the innermost element with that label). */
    static final String CLICK = "(function(){var best=null,l=document.querySelectorAll('a,button,input,span,div,img,i,label,td,p');" +
            "for(var i=0;i<l.length;i++){var e=l[i],t=((e.innerText||e.value||e.alt||e.title||'')+'').replace(/\\s+/g,' ').trim();" +
            "if(/^refresh$/i.test(t)&&e.offsetParent!==null)best=e}" +
            "if(!best){l=document.querySelectorAll('[onclick*=efresh],[id*=efresh],[class*=efresh],[title*=efresh]');" +
            "for(i=0;i<l.length;i++){if(l[i].offsetParent!==null)best=l[i]}}" +
            "if(!best)return 'none';best.click();return 'ok'})()";

    /** A web view for the lookup screen that can later outlive the screen. Drops any page kept before. */
    static WebView create(Context screen) {
        release();
        return new WebView(new MutableContextWrapper(screen));
    }

    /** Keep this page: take it off the screen and let the app context hold it. */
    static void keep(Context app, WebView w, String num, long upd) {
        if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        if (w.getContext() instanceof MutableContextWrapper) ((MutableContextWrapper) w.getContext()).setBaseContext(app);
        web = w;
        train = num == null ? "" : num;
        lastUpd = upd;
        busy = false;
        w.onPause();
    }

    static void release() {
        if (web != null) { try { web.destroy(); } catch (Exception ignored) { } }
        web = null;
        train = "";
        busy = false;
    }

    static boolean has(String num) { return web != null && num != null && num.length() > 0 && num.equals(train); }

    /** Press Refresh, wait for the page to show newer data, and hand back the parsed result (null and a reason on failure). */
    static void refresh(Context app, Done cb) {
        if (web == null) { cb.run(null, "nopage"); return; }
        if (busy) { cb.run(null, "busy"); return; }
        busy = true;
        web.onResume();
        web.evaluateJavascript(CLICK, r -> {
            if (r == null || !r.contains("ok")) { finish(cb, null, "nobutton"); return; }
            poll(app, cb, 0);
        });
    }

    static void poll(Context app, Done cb, int n) {
        H.postDelayed(() -> {
            if (web == null) { finish(cb, null, "nopage"); return; }
            web.evaluateJavascript(LookupActivity.SNAP, raw -> {
                try {
                    JSONObject snap = new JSONObject(LookupActivity.unquote(raw));
                    NtesParser.Result p = NtesParser.parse(snap.optString("text"));
                    boolean fresh = p.updatedMs != 0 && p.updatedMs != lastUpd;
                    // newer data, or 5 s passed and the page shows the same update time (NTES has nothing newer)
                    if (p.stops.size() >= 2 && (fresh || n >= 5)) {
                        JSONObject res = LookupActivity.scheduleJson(app, snap, train);
                        if (res != null) { lastUpd = p.updatedMs; finish(cb, res, ""); return; }
                    }
                } catch (Exception ignored) { }
                if (n >= 14) finish(cb, null, "lost"); else poll(app, cb, n + 1);
            });
        }, 1000);
    }

    static void finish(Done cb, JSONObject res, String why) {
        busy = false;
        if (web != null) web.onPause();
        cb.run(res, why);
    }
}
