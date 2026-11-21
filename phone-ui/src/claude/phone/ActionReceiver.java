package claude.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.TileService;
import android.content.ComponentName;

/** Notification actions (not exported). The kill switch lives here so pausing is instant. */
public class ActionReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    String a = i.getAction() == null ? "" : i.getAction();
    if (a.endsWith(".PAUSE")) Kill.set(c, true);
    else if (a.endsWith(".RESUME")) Kill.set(c, false);
    else if (a.endsWith(".SNOOZE")) Notifs.nm(c).cancel(Notifs.NUDGE);
    else if (a.endsWith(".REVIEW")) { Notifs.nm(c).cancel(Notifs.NEEDS); ClaudeVIS.showDemo(c, "message"); }
  }
}
