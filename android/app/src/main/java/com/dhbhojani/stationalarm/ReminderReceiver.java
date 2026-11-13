package com.dhbhojani.stationalarm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boarding reminder with the seat card, shown on the lock screen. */
public class ReminderReceiver extends BroadcastReceiver {

    static void schedule(Context c, String id, long at, String title, String text) {
        Intent i = new Intent(c, ReminderReceiver.class).putExtra("id", id).putExtra("title", title).putExtra("text", text);
        PendingIntent pi = PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pi);
        if (at > System.currentTimeMillis()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    static void cancel(Context c, String id) {
        Intent i = new Intent(c, ReminderReceiver.class);
        c.getSystemService(AlarmManager.class).cancel(PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    @Override
    public void onReceive(Context c, Intent in) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("board", "Boarding reminders", NotificationManager.IMPORTANCE_HIGH));
        String id = in.getStringExtra("id");
        Intent open = new Intent(c, MainActivity.class).putExtra("ticket", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification n = new Notification.Builder(c, "board")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(in.getStringExtra("title"))
                .setContentText(in.getStringExtra("text"))
                .setStyle(new Notification.BigTextStyle().bigText(in.getStringExtra("text")))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(c, 300 + (id == null ? 0 : id.hashCode() & 0xff), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null, "Set station alarm",
                        PendingIntent.getActivity(c, 400 + (id == null ? 0 : id.hashCode() & 0xff), open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .build();
        nm.notify(5000 + (id == null ? 0 : id.hashCode() & 0xfff), n);
    }
}
