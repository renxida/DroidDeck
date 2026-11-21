package claude.phone;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.TileService;
import android.util.Log;

/** The kill switch: one flag every agent action checks, mirrored in the tile, notification and glow. */
final class Kill {
  static boolean paused(Context c) { return Ui.prefs(c).getBoolean("paused", false); }

  static void set(Context c, boolean paused) {
    long t = Perf.now();
    Ui.prefs(c).edit().putBoolean("paused", paused).commit();
    if (paused) { Glow.hide(); Notifs.paused(c); } else Notifs.work(c, "booking a table", "Tuyet Mai · Friday 7:30 · in workspace 2");
    TileService.requestListeningState(c, new ComponentName(c, KillTile.class));
    Log.i(Perf.TAG, "MARK kill_switch_" + (paused ? "pause" : "resume") + " ms=" + Perf.fmt((Perf.now() - t) / 1e6));
  }
}
