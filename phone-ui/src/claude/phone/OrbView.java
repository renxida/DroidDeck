package claude.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/**
 * Claude's presence: a soft living blob. Every visual parameter is a spring toward a target set by
 * the state, so changes between listening, thinking and speaking never jump. Allocation-free per frame.
 */
final class OrbView extends View {
  enum State { IDLE, LISTENING, THINKING, SPEAKING, DONE, OFFLINE }

  private static final int N = 40;
  private static final float[] COS = new float[N], SIN = new float[N];
  static { for (int i = 0; i < N; i++) { double a = 2 * Math.PI * i / N; COS[i] = (float) Math.cos(a); SIN[i] = (float) Math.sin(a); } }

  private final Ui ui;
  private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG), gray = new Paint(Paint.ANTI_ALIAS_FLAG),
      glow = new Paint(Paint.ANTI_ALIAS_FLAG), shine = new Paint(Paint.ANTI_ALIAS_FLAG),
      ring = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();
  private final Matrix m = new Matrix();
  private final float[] px = new float[N], py = new float[N];
  private SweepGradient sweep;

  private final Spring scale = new Spring(0.0f, 220), wobble = new Spring(0.03f, 120), speed = new Spring(0.3f, 60),
      glowA = new Spring(0.0f, 120), grayT = new Spring(0, 90), rings = new Spring(0, 90), level = new Spring(0, 900);
  private State state = State.IDLE;
  private float rawLevel, phase, rot;
  private long last;
  private boolean running;

  OrbView(Context c, Ui ui) {
    super(c);
    this.ui = ui;
    ring.setStyle(Paint.Style.STROKE);
    ring.setColor(ui.accent);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  State state() { return state; }

  void setState(State s) {
    state = s;
    switch (s) {
      case IDLE: scale.target = 0.82f; wobble.target = 0.035f; speed.target = 0.35f; glowA.target = 0.25f; grayT.target = 0; rings.target = 0; break;
      case LISTENING: scale.target = 0.9f; wobble.target = 0.06f; speed.target = 0.7f; glowA.target = 0.5f; grayT.target = 0; rings.target = 0; break;
      case THINKING: scale.target = 0.74f; wobble.target = 0.11f; speed.target = 2.6f; glowA.target = 0.6f; grayT.target = 0; rings.target = 0; break;
      case SPEAKING: scale.target = 0.86f; wobble.target = 0.05f; speed.target = 0.9f; glowA.target = 0.45f; grayT.target = 0; rings.target = 1; break;
      case DONE: scale.target = 0.8f; wobble.target = 0.02f; speed.target = 0.25f; glowA.target = 0.2f; grayT.target = 0; rings.target = 0; break;
      case OFFLINE: scale.target = 0.78f; wobble.target = 0.015f; speed.target = 0.15f; glowA.target = 0.0f; grayT.target = 1; rings.target = 0; break;
    }
    kick();
  }

  /** Mic RMS or TTS envelope, 0..1. Cheap to call every frame. */
  void setLevel(float l) { rawLevel = Math.max(0, Math.min(1, l)); kick(); }

  private void kick() { if (!running && isAttachedToWindow()) { running = true; last = 0; postInvalidateOnAnimation(); } }

  @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); kick(); }
  @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); running = false; }

  @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
    float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
    sweep = new SweepGradient(cx, cy, new int[] { ui.accent, ui.amber, ui.rose, ui.accent, ui.amber, ui.accent }, null);
    blob.setShader(sweep);
    gray.setShader(new SweepGradient(cx, cy, new int[] { 0xFF8E8C86, 0xFFB7B5AE, 0xFF8E8C86 }, null));
    glow.setShader(new RadialGradient(cx, cy, r, new int[] { Ui.alpha(ui.accent, ui.night ? 0.55f : 0.42f), Ui.alpha(ui.accent, 0.12f), 0 },
        new float[] { 0f, 0.55f, 1f }, Shader.TileMode.CLAMP));
    shine.setShader(new RadialGradient(cx - r * 0.22f, cy - r * 0.26f, r * 0.42f, new int[] { 0x66FFFFFF, 0x00FFFFFF }, null, Shader.TileMode.CLAMP));
  }

  @Override protected void onDraw(Canvas c) {
    long now = SystemClock.uptimeMillis();
    float dt = last == 0 ? 0.016f : (now - last) / 1000f;
    last = now;
    float lvl = rawLevel;
    if (state == State.LISTENING) { scale.target = 0.88f + 0.22f * lvl; wobble.target = 0.05f + 0.16f * lvl; glowA.target = 0.4f + 0.5f * lvl; }
    else if (state == State.SPEAKING) { scale.target = 0.84f + 0.12f * lvl; }
    level.target = lvl;
    boolean moving = scale.step(dt) | wobble.step(dt) | speed.step(dt) | glowA.step(dt) | grayT.step(dt) | rings.step(dt) | level.step(dt);
    phase += dt * (1.2f + speed.value);
    rot += dt * speed.value * 60f;

    float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
    float R = Math.min(w, h) / 2f;
    float base = R * 0.56f * scale.value;
    if (base <= 0.5f) { scheduleNext(moving); return; }

    // Glow
    if (glowA.value > 0.01f) {
      glow.setAlpha((int) (255 * Math.min(1, glowA.value)));
      float gs = 0.7f + 0.3f * scale.value + 0.12f * level.value;
      c.save(); c.scale(gs, gs, cx, cy); c.drawCircle(cx, cy, R, glow); c.restore();
    }
    // Speaking rings
    if (rings.value > 0.01f) {
      ring.setStrokeWidth(ui.dpf(1.5f));
      for (int k = 0; k < 2; k++) {
        float t = ((phase * 0.55f) + k * 0.5f) % 1f;
        float rr = base * (1.05f + 0.55f * t);
        ring.setAlpha((int) (255 * rings.value * (1 - t) * (0.25f + 0.5f * level.value)));
        c.drawCircle(cx, cy, Math.min(rr, R - ui.dpf(1)), ring);
      }
    }
    // Blob outline: three travelling harmonics.
    float wb = wobble.value, p = phase;
    for (int i = 0; i < N; i++) {
      float a = (float) (2 * Math.PI * i / N);
      float d = 1 + wb * (0.55f * (float) Math.sin(3 * a + p * 1.3f) + 0.3f * (float) Math.sin(5 * a - p * 1.9f) + 0.15f * (float) Math.sin(2 * a + p * 0.7f));
      px[i] = cx + COS[i] * base * d; py[i] = cy + SIN[i] * base * d;
    }
    path.rewind();
    float mx = (px[N - 1] + px[0]) / 2, my = (py[N - 1] + py[0]) / 2;
    path.moveTo(mx, my);
    for (int i = 0; i < N; i++) {
      int j = (i + 1) % N;
      path.quadTo(px[i], py[i], (px[i] + px[j]) / 2, (py[i] + py[j]) / 2);
    }
    path.close();
    m.setRotate(rot, cx, cy);
    sweep.setLocalMatrix(m);
    float g = grayT.value;
    if (g < 0.99f) { blob.setAlpha((int) (255 * (1 - g))); c.drawPath(path, blob); }
    if (g > 0.01f) { gray.setAlpha((int) (255 * g)); c.drawPath(path, gray); }
    c.drawCircle(cx, cy, base, shine);
    scheduleNext(true);
  }

  private void scheduleNext(boolean moving) {
    if (running && isShown()) postInvalidateOnAnimation(); else running = false;
  }
}
