package claude.phone;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.FrameMetrics;
import android.view.Window;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-window frame timing, grouped by UI phase, plus latency marks. Everything goes to logcat with
 * the tag ClaudePerf so the emulator test can collect it.
 */
final class Perf implements Window.OnFrameMetricsAvailableListener {
  static final String TAG = "ClaudePerf";
  private static HandlerThread thread;

  private final String surface;
  private final Map<String, float[]> frames = new LinkedHashMap<>();
  private final Map<String, Integer> counts = new LinkedHashMap<>();
  private final Map<String, Integer> janks = new LinkedHashMap<>();
  private final Map<String, float[]> uiT = new LinkedHashMap<>(), rtT = new LinkedHashMap<>();
  /** Uptime (ms) of the key press that opened this surface, if known. */
  volatile long invokeUptimeMs;
  volatile String phase = "enter";
  private final long t0;
  private volatile boolean firstSeen;
  private volatile long tokenAt = -1;
  private volatile String tokenLabel;
  private Window window;

  Perf(String surface, long t0Nanos) { this.surface = surface; this.t0 = t0Nanos; }

  static long now() { return System.nanoTime(); }

  static void mark(String what, long sinceNanos) {
    Log.i(TAG, "MARK " + what + " ms=" + fmt((now() - sinceNanos) / 1e6));
  }

  static void event(String what) { Log.i(TAG, "EVENT " + what + " t=" + now()); }

  void attach(Window w) {
    synchronized (Perf.class) { if (thread == null) { thread = new HandlerThread("perf"); thread.start(); } }
    window = w;
    w.addOnFrameMetricsAvailableListener(this, new Handler(thread.getLooper()));
  }

  void detach() {
    try { if (window != null) window.removeOnFrameMetricsAvailableListener(this); } catch (RuntimeException ignored) {}
  }

  /** The next presented frame after this call reports its latency under the label. */
  void expectFrame(String label) { tokenLabel = label; tokenAt = now(); }

  @Override public void onFrameMetricsAvailable(Window w, FrameMetrics m, int dropped) {
    long total = m.getMetric(FrameMetrics.TOTAL_DURATION);
    long vsync = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
    long end = vsync + total;
    if (!firstSeen) {
      firstSeen = true;
      long inv = invokeUptimeMs;
      Log.i(TAG, "FIRST_FRAME " + surface + " ms=" + fmt((end - t0) / 1e6) + (inv > 0 ? " from_key_ms=" + fmt(end / 1e6 - inv) : "")
          + " first_draw=" + m.getMetric(FrameMetrics.FIRST_DRAW_FRAME));
    }
    long tk = tokenAt;
    if (tk > 0 && end >= tk && m.getMetric(FrameMetrics.VSYNC_TIMESTAMP) + 2_000_000 >= tk) {
      tokenAt = -1;
      Log.i(TAG, "LATENCY " + tokenLabel + " ms=" + fmt((end - tk) / 1e6));
    }
    long deadline = Build.VERSION.SDK_INT >= 31 ? m.getMetric(FrameMetrics.DEADLINE) : 16_666_667L;
    if (deadline <= 0) deadline = 16_666_667L;
    String p = phase;
    synchronized (this) {
      float[] a = frames.get(p);
      int n = counts.containsKey(p) ? counts.get(p) : 0;
      if (a == null) { a = new float[64]; frames.put(p, a); }
      if (n == a.length) { a = Arrays.copyOf(a, n * 2); frames.put(p, a); }
      a[n] = total / 1e6f;
      long ui = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION)
          + m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION) + m.getMetric(FrameMetrics.SYNC_DURATION);
      long rt = m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) + m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION);
      put(uiT, p, n, a.length, ui / 1e6f);
      put(rtT, p, n, a.length, rt / 1e6f);
      counts.put(p, n + 1);
      if (total > deadline) janks.put(p, (janks.containsKey(p) ? janks.get(p) : 0) + 1);
    }
  }

  private static void put(Map<String, float[]> m, String p, int n, int cap, float v) {
    float[] a = m.get(p);
    if (a == null || a.length < cap) { a = a == null ? new float[cap] : Arrays.copyOf(a, cap); m.put(p, a); }
    a[n] = v;
  }

  synchronized void report() {
    float[] all = new float[0];
    int jankAll = 0;
    for (Map.Entry<String, float[]> e : frames.entrySet()) {
      int n = counts.get(e.getKey());
      float[] a = Arrays.copyOf(e.getValue(), n);
      int j = janks.containsKey(e.getKey()) ? janks.get(e.getKey()) : 0;
      jankAll += j;
      Log.i(TAG, "FRAMES " + surface + "/" + e.getKey() + " " + stats(a, j)
          + " | ui " + stats(Arrays.copyOf(uiT.get(e.getKey()), n), -1) + " | rt " + stats(Arrays.copyOf(rtT.get(e.getKey()), n), -1));
      float[] b = Arrays.copyOf(all, all.length + n);
      System.arraycopy(a, 0, b, all.length, n);
      all = b;
    }
    float[] u = new float[0], r = new float[0];
    for (Map.Entry<String, float[]> e : uiT.entrySet()) { int n = counts.get(e.getKey()); u = cat(u, Arrays.copyOf(e.getValue(), n)); r = cat(r, Arrays.copyOf(rtT.get(e.getKey()), n)); }
    Log.i(TAG, "FRAMES " + surface + "/ALL " + stats(all, jankAll) + " | ui " + stats(u, -1) + " | rt " + stats(r, -1));
    frames.clear(); counts.clear(); janks.clear(); uiT.clear(); rtT.clear();
  }

  static float[] cat(float[] a, float[] b) { float[] c = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, c, a.length, b.length); return c; }

  static String stats(float[] a, int jank) {
    if (a.length == 0) return "n=0";
    float[] s = a.clone();
    Arrays.sort(s);
    return "n=" + s.length + " p50=" + fmt(pct(s, .5)) + " p90=" + fmt(pct(s, .9)) + " p99=" + fmt(pct(s, .99))
        + " max=" + fmt(s[s.length - 1]) + (jank >= 0 ? " jank=" + jank : "");
  }

  static float pct(float[] s, double p) { return s[Math.min(s.length - 1, (int) Math.ceil(p * s.length) - 1 < 0 ? 0 : (int) Math.ceil(p * s.length) - 1)]; }
  static String fmt(double v) { return String.format(java.util.Locale.US, "%.1f", v); }
}
