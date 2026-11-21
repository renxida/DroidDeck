package claude.phone;

import android.service.voice.VoiceInteractionService;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

/** Holds the assistant role. On ready, warms everything the first invocation needs. */
public class ClaudeVIS extends VoiceInteractionService {
  static ClaudeVIS instance;

  /** Opens the overlay from inside the app (notification Review button, tests). */
  static void showDemo(android.content.Context c, String demo) {
    Ui.prefs(c).edit().putString("demo", demo).commit();
    if (instance != null) instance.showSession(new android.os.Bundle(), 0);
  }

  @Override public void onShutdown() { instance = null; super.onShutdown(); }
  @Override public void onReady() {
    super.onReady();
    instance = this;
    // Claude reads the screen's structure (text, views) on invoke; a full screenshot is taken only
    // when needed, off the critical path. Saves a capture on every press.
    if (!Ui.prefs(this).getBoolean("screenshot", false)) setDisabledShowContext(android.service.voice.VoiceInteractionSession.SHOW_WITH_SCREENSHOT);
    boolean warm = Ui.prefs(this).getBoolean("prewarm", true);
    Log.i(Perf.TAG, "EVENT vis_ready prewarm=" + warm);
    if (!warm) return;
    new Handler(Looper.getMainLooper()).post(() -> {
      long t = Perf.now();
      Ui ui = Ui.of(this);
      // Touch the classes and resources the overlay uses so the first show doesn't pay for them.
      new OverlayView(this, ui, new OverlayView.Host() { public void dismiss() {} public void onAction(String id) {} }, new Perf("warm", t));
      Perf.mark("prewarm_overlay", t);
      CanvasHost.prewarm(getApplicationContext());
    });
  }
}
