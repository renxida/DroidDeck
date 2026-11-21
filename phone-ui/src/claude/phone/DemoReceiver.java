package claude.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Test hooks for the emulator run. Guarded by the DUMP permission, which the adb shell has and
 * ordinary apps don't.
 */
public class DemoReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    String cmd = i.getStringExtra("cmd");
    if (cmd == null) return;
    Log.i(Script.TAG, "DEMO " + cmd);
    switch (cmd) {
      case "demo": Ui.prefs(c).edit().putString("demo", i.getStringExtra("v")).commit(); break;
      case "screenshot": Ui.prefs(c).edit().putBoolean("screenshot", "true".equals(i.getStringExtra("v"))).commit(); break;
      case "prewarm": Ui.prefs(c).edit().putBoolean("prewarm", "true".equals(i.getStringExtra("v"))).commit(); break;
      case "show": ClaudeVIS.showDemo(c, i.getStringExtra("v") == null ? "" : i.getStringExtra("v")); break;
      case "seed": LogStore.seed(c, i.getIntExtra("n", 24)); break;
      case "clearlog": LogStore.clear(c); break;
      case "work": Notifs.work(c, "booking a table", "Tuyet Mai · Friday 7:30 · in workspace 2"); break;
      case "nudge": Notifs.nudge(c); break;
      case "needs": Notifs.needs(c); break;
      case "pause": Kill.set(c, true); break;
      case "resume": Kill.set(c, false); break;
      case "glow_on": Glow.show(c, "Claude is using this screen"); break;
      case "glow_off": Glow.hide(); break;
      case "cancel_all": Notifs.cancelAll(c); Glow.hide(); break;
    }
  }
}
