package claude.phone;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Drives the overlay through a realistic turn. In real use the ASR, model and TTS events arrive
 * here instead; in demo mode they're simulated with human-like timing so the UI can be measured
 * and filmed deterministically.
 */
final class Script implements Choreographer.FrameCallback {
  static final String TAG = "ClaudeDemo";
  private final Context c;
  private final OverlayView v;
  private final Perf perf;
  private final String demo;
  private final Handler h = new Handler(Looper.getMainLooper());
  private boolean stopped, micOn, ttsOn;
  private long t0;

  Script(Context c, OverlayView v, Perf perf, String demo) { this.c = c; this.v = v; this.perf = perf; this.demo = demo; }

  void start() {
    t0 = SystemClock.uptimeMillis();
    state("listening");
    Choreographer.getInstance().postFrameCallback(this);
    switch (demo) {
      case "message": say(new String[] { "Text", "Betty", "that", "I'll", "be", "home", "by", "seven" }, this::messageTurn); break;
      case "chart": say(new String[] { "How", "did", "I", "sleep", "this", "week?" }, this::chartTurn); break;
      case "tool": say(new String[] { "Split", "dinner,", "142", "dollars,", "four", "of", "us" }, this::toolTurn); break;
      case "offline": say(new String[] { "Order", "more", "coffee", "beans" }, () -> { state("offline"); v.offline("I can't reach the network right now. I'll place the order as soon as you're back online, and ask you first."); }); break;
      default: after(7000, () -> { micOn = false; v.orb.setState(OrbView.State.IDLE); v.setLabel("Didn't catch that"); v.transcript.setText("Try “text Betty I'm on my way” or “what's next today?”"); v.showAskBar(); state("idle"); });
    }
  }

  void stop() { stopped = true; h.removeCallbacksAndMessages(null); Choreographer.getInstance().removeFrameCallback(this); }

  private void after(long ms, Runnable r) { h.postDelayed(() -> { if (!stopped) r.run(); }, ms); }

  private void state(String s) { Log.i(TAG, "STATE " + s + " t=" + (SystemClock.uptimeMillis() - t0)); }

  /** Simulated speech: words land every ~280 ms; the last word stays tentative until the next one. */
  private void say(String[] words, Runnable then) {
    micOn = true;
    final long start = 650; // people take a beat before talking
    for (int i = 0; i < words.length; i++) {
      final int n = i;
      after(start + 280L * i, () -> {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) { if (k > 0) sb.append(' '); sb.append(words[k]); }
        v.partial(sb.toString(), words[n]);
      });
    }
    long end = start + 280L * words.length + 120;
    after(end, () -> { StringBuilder sb = new StringBuilder(); for (int k = 0; k < words.length; k++) { if (k > 0) sb.append(' '); sb.append(words[k]); } v.partial(sb.toString(), null); micOn = false; });
    // Endpointing: Smart Turn decides the turn is over ~350 ms after the last word.
    after(end + 350, () -> { Perf.event("speech_end"); v.thinking(); state("thinking"); });
    // First token from the model.
    after(end + 350 + 420, then);
  }

  private void stream(String text, long perWordMs, Runnable then) {
    ttsOn = true;
    String[] parts = text.split("(?<= )");
    for (int i = 0; i < parts.length; i++) { final String p = parts[i]; after(perWordMs * i, () -> v.token(p)); }
    after(perWordMs * parts.length + 200, () -> { ttsOn = false; if (then != null) then.run(); });
  }

  private void messageTurn() {
    state("speaking");
    stream("Here's your text to Betty. Hold the button to send it.", 55, () -> {
      v.showConfirm("To Betty · Messages", "Hey! I'll be home by 7. Want me to pick anything up on the way?", "Hold to send", "Sent", () -> {
        state("sent");
        LogStore.add(c, "message", "Texted Betty", "“Hey! I'll be home by 7…”", true);
        v.done("Sent to Betty", "Undo", "Open chat");
      });
      state("confirm");
      v.post(() -> v.postDelayed(this::logHold, 320));
    });
  }

  private void logHold() {
    int[] loc = new int[2];
    v.hold.getLocationOnScreen(loc);
    Log.i(TAG, "HOLD " + (loc[0] + v.hold.getWidth() / 2) + " " + (loc[1] + v.hold.getHeight() / 2));
  }

  private void chartTurn() {
    state("speaking");
    canvas("sleep");
    stream("You averaged 6 hours 50 minutes, about an hour under your 8 hour goal. Tuesday was the short night.", 55, () -> { state("canvas_done"); v.done("Done", "Show last month", "Set a bedtime"); });
  }

  private void toolTurn() {
    state("speaking");
    canvas("tip");
    stream("Here's a splitter. Drag to change the tip.", 55, () -> { state("canvas_done"); v.done("Done", "Request on Venmo"); });
  }

  private void canvas(String name) {
    String src = asset("demo/" + name + ".js");
    android.webkit.WebView w = CanvasHost.take(v.getContext(), new CanvasHost.Listener() {
      @Override public void onHeight(int px) { v.setCanvasHeight(px); }
      @Override public void onRendered(double jsMs) { state("canvas_rendered"); }
      @Override public void onEvent(String json) { Log.i(TAG, "CANVAS_EVENT " + json); }
    });
    v.showCanvas(w, v.ui.dp(1));
    perf.expectFrame("canvas_first_frame");
    CanvasHost.render("preact", src, v.ui.night);
  }

  private String asset(String p) {
    try (InputStream in = c.getAssets().open(p)) {
      java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
      byte[] b = new byte[8192]; int n;
      while ((n = in.read(b)) > 0) o.write(b, 0, n);
      return o.toString("UTF-8");
    } catch (Exception e) { return "<p>missing " + p + "</p>"; }
  }

  void onAction(String id) {
    switch (id) {
      case "cancel": state("cancelled"); v.confirm.setVisibility(android.view.View.GONE); v.done("Not sent"); after(900, v::dismiss); break;
      case "Undo": state("undone"); v.done("Unsent", "Edit and resend"); break;
      case "mic": case "Retry": stop(); stopped = false; h.removeCallbacksAndMessages(null); v.listen(); start(); break;
      default: break;
    }
  }

  // Fake mic and TTS envelopes, sampled once per frame.
  @Override public void doFrame(long frameNanos) {
    if (stopped) return;
    double t = frameNanos / 1e9;
    float l = 0;
    if (micOn) l = (float) (Math.max(0, Math.sin(t * 2 * Math.PI * 3.7)) * (0.55 + 0.45 * Math.sin(t * 1.9)) * 0.9 + 0.06 * Math.random());
    else if (ttsOn) l = (float) (0.35 + 0.3 * Math.max(0, Math.sin(t * 2 * Math.PI * 4.4)) * (0.6 + 0.4 * Math.sin(t * 2.3)));
    v.speakLevel(l);
    Choreographer.getInstance().postFrameCallback(this);
  }
}
