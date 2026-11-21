package claude.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

/** One assistant invocation: the overlay window, its timing, and (in demo mode) a scripted turn. */
final class Session extends VoiceInteractionSession implements OverlayView.Host {
  private final long createdAt = Perf.now();
  private OverlayView view;
  private Perf perf;
  private Script script;
  private boolean shown;

  Session(Context c) { super(c); }

  @Override public void onCreate() {
    long t = Perf.now();
    super.onCreate();
    Window w = getWindow().getWindow();
    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    w.setDecorFitsSystemWindows(false);
    w.setStatusBarColor(Color.TRANSPARENT);
    w.setNavigationBarColor(Color.TRANSPARENT);
    w.setNavigationBarContrastEnforced(false);
    w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    w.setWindowAnimations(0); // we animate ourselves
    Perf.mark("session_onCreate", t);
  }

  @Override public View onCreateContentView() {
    long t = Perf.now();
    perf = new Perf("overlay", createdAt);
    view = new OverlayView(getContext(), Ui.of(getContext()), this, perf);
    Perf.mark("overlay_build", t);
    return view;
  }

  @Override public void onShow(Bundle args, int flags) {
    super.onShow(args, flags);
    long now = Perf.now();
    Log.i(Perf.TAG, "MARK create_to_onShow ms=" + Perf.fmt((now - createdAt) / 1e6) + " flags=" + flags + " canvas_warm=" + CanvasHost.isWarm());
    if (args != null) {
      StringBuilder sb = new StringBuilder();
      for (String k : args.keySet()) sb.append(k).append('=').append(args.get(k)).append(' ');
      Log.i(Perf.TAG, "ARGS " + sb);
      long ev = args.getLong("android.intent.extra.TIME", args.getLong("invocation_time_ms", 0));
      if (ev > 0) Log.i(Perf.TAG, "MARK key_to_onShow ms=" + (SystemClock.uptimeMillis() - ev));
    }
    if (shown) { perf.report(); perf.detach(); perf = new Perf("overlay", now); view = rebuild(); }
    shown = true;
    perf.attach(getWindow().getWindow());
    view.enter();
    view.listen();
    String demo = Ui.prefs(getContext()).getString("demo", "");
    script = new Script(getContext(), view, perf, demo);
    script.start();
  }

  private OverlayView rebuild() {
    OverlayView v = new OverlayView(getContext(), Ui.of(getContext()), this, perf);
    setContentView(v);
    return v;
  }

  @Override public void onHandleAssist(AssistState s) {
    Log.i(Perf.TAG, "EVENT assist_data structure=" + (s.getAssistStructure() != null));
  }

  @Override public void onHandleScreenshot(Bitmap b) {
    Log.i(Perf.TAG, "EVENT screenshot " + (b == null ? "null" : b.getWidth() + "x" + b.getHeight()));
  }

  @Override public void onBackPressed() { if (view != null) view.dismiss(); else hide(); }

  @Override public void onHide() {
    super.onHide();
    if (script != null) script.stop();
    if (perf != null) { perf.report(); perf.detach(); }
    CanvasHost.release();
    Log.i("ClaudeDemo", "STATE hidden");
  }

  @Override public void dismiss() { hide(); }

  @Override public void onAction(String id) {
    Log.i("ClaudeDemo", "ACTION " + id);
    if (script != null) script.onAction(id);
  }
}
