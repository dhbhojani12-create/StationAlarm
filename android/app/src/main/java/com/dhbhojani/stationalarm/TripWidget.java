package com.dhbhojani.stationalarm;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

/** Home-screen widget: saved trips before a trip, stops/time left during one. */
public class TripWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { refresh(c); }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, TripWidget.class));
        if (ids.length == 0) return;
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        int[] btn = {R.id.b0, R.id.b1, R.id.b2};
        try {
            JSONObject s = new JSONObject(TripService.prefs(c).getString("state", "{}"));
            if (s.optBoolean("active") && TripService.prefs(c).contains("trip")) {
                String t = s.optString("title", "Trip starting…");
                int dot = t.indexOf(" · ");
                v.setTextViewText(R.id.label, "Heading to");
                v.setTextViewText(R.id.title, dot > 0 ? t.substring(0, dot) : t);
                v.setTextViewText(R.id.text, dot > 0 ? t.substring(dot + 3) : s.optString("text", ""));
                v.setViewVisibility(R.id.row, View.GONE);
            } else {
                v.setTextViewText(R.id.label, "Station Alarm");
                v.setTextViewText(R.id.title, "Where to?");
                v.setTextViewText(R.id.text, "Tap a saved trip to start");
                JSONArray saved = new JSONArray(TripService.prefs(c).getString("saved", "[]"));
                v.setViewVisibility(R.id.row, saved.length() > 0 ? View.VISIBLE : View.GONE);
                for (int i = 0; i < 3; i++) {
                    if (i < saved.length()) {
                        JSONObject t = saved.getJSONObject(i);
                        v.setViewVisibility(btn[i], View.VISIBLE);
                        v.setTextViewText(btn[i], t.optString("name") + "\n" + t.optString("sub"));
                        Intent go = new Intent(c, MainActivity.class).putExtra("saved", t.optString("id"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        v.setOnClickPendingIntent(btn[i], PendingIntent.getActivity(c, 10 + i, go, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                    } else v.setViewVisibility(btn[i], View.INVISIBLE);
                }
            }
        } catch (Exception ignored) { }
        m.updateAppWidget(ids, v);
    }
}
