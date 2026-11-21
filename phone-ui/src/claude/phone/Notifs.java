package claude.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

/** Claude's notifications: ongoing work, nudges, and asks that need the user's OK. */
final class Notifs {
  static final int WORK = 1, NUDGE = 2, NEEDS = 3;

  static NotificationManager nm(Context c) {
    NotificationManager m = c.getSystemService(NotificationManager.class);
    if (m.getNotificationChannel("work") == null) {
      NotificationChannel w = new NotificationChannel("work", "Claude at work", NotificationManager.IMPORTANCE_LOW);
      w.setDescription("Shows while Claude is doing something for you, with a way to pause it.");
      w.setShowBadge(false);
      NotificationChannel n = new NotificationChannel("nudge", "Nudges", NotificationManager.IMPORTANCE_DEFAULT);
      n.setDescription("Gentle interruptions when you've been scrolling a while.");
      NotificationChannel a = new NotificationChannel("needs", "Needs your OK", NotificationManager.IMPORTANCE_HIGH);
      a.setDescription("Claude is waiting for you to approve something.");
      m.createNotificationChannel(w); m.createNotificationChannel(n); m.createNotificationChannel(a);
    }
    return m;
  }

  static PendingIntent cmd(Context c, String cmd, int req) {
    Intent i = new Intent(c, ActionReceiver.class).setAction("claude.phone." + cmd);
    return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static PendingIntent openLog(Context c) {
    return PendingIntent.getActivity(c, 9, new Intent(c, LogActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
  }

  static Notification.Builder base(Context c, String ch) {
    Ui ui = Ui.of(c);
    return new Notification.Builder(c, ch).setSmallIcon(R.drawable.ic_spark).setColor(0xFFC96442).setContentIntent(openLog(c)).setShowWhen(true);
  }

  static void work(Context c, String task, String where) {
    if (Ui.prefs(c).getBoolean("paused", false)) { paused(c); return; }
    Notification n = base(c, "work")
        .setContentTitle("Claude is " + task)
        .setContentText(where)
        .setOngoing(true).setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .setCategory(Notification.CATEGORY_PROGRESS)
        .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_pause), "Pause Claude", cmd(c, "PAUSE", 1)).build())
        .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_apps), "Activity", openLog(c)).build())
        .build();
    nm(c).notify(WORK, n);
  }

  static void paused(Context c) {
    Notification n = base(c, "work")
        .setContentTitle("Claude is paused")
        .setContentText("Nothing runs until you resume. Claude can still talk.")
        .setOngoing(true).setOnlyAlertOnce(true)
        .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_play), "Resume", cmd(c, "RESUME", 2)).build())
        .build();
    nm(c).notify(WORK, n);
  }

  static void nudge(Context c) {
    Notification n = base(c, "nudge")
        .setContentTitle("25 minutes on Instagram")
        .setContentText("The long read you saved this morning is 12 minutes. Switch?")
        .setStyle(new Notification.BigTextStyle().bigText("The long read you saved this morning is 12 minutes. Switch?"))
        .setAutoCancel(true)
        .addAction(new Notification.Action.Builder(null, "Read it", openLog(c)).build())
        .addAction(new Notification.Action.Builder(null, "5 more minutes", cmd(c, "SNOOZE", 3)).build())
        .build();
    nm(c).notify(NUDGE, n);
  }

  static void needs(Context c) {
    Notification n = base(c, "needs")
        .setContentTitle("Send this to Betty?")
        .setContentText("“Hey! I'll be home by 7. Want me to pick anything up on the way?”")
        .setStyle(new Notification.BigTextStyle().bigText("“Hey! I'll be home by 7. Want me to pick anything up on the way?”"))
        .setCategory(Notification.CATEGORY_REMINDER)
        .addAction(new Notification.Action.Builder(null, "Review", cmd(c, "REVIEW", 4)).build())
        .build();
    nm(c).notify(NEEDS, n);
  }

  static void cancelAll(Context c) { nm(c).cancelAll(); }
}
