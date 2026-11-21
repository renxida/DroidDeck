package claude.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * When Claude acts on the screen you're looking at (not its own workspace), the screen edge glows
 * and a pill at the top says so, with Stop. The glow window passes touches through; its opacity is
 * kept at 0.8 so Android doesn't block touches to the app underneath.
 */
final class Glow {
  private static View edge, pill;

  static boolean showing() { return edge != null; }

  static void show(Context app, String what) {
    if (edge != null) return;
    Context c = app.getApplicationContext();
    if (!android.provider.Settings.canDrawOverlays(c)) { android.util.Log.w(Perf.TAG, "GLOW no overlay permission"); return; }
    try { add(c, what); } catch (RuntimeException e) { android.util.Log.e(Perf.TAG, "GLOW failed", e); edge = null; pill = null; }
  }

  private static void add(Context c, String what) {
    WindowManager wm = c.getSystemService(WindowManager.class);
    Ui ui = Ui.of(c);
    edge = new EdgeView(c, ui);
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT);
    lp.alpha = 0.8f;
    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
    lp.setFitInsetsTypes(0);
    lp.setTitle("ClaudeGlow");
    wm.addView(edge, lp);
    edge.setAlpha(0f);
    edge.animate().alpha(1f).setDuration(300).start();

    LinearLayout p = new LinearLayout(c);
    p.setGravity(Gravity.CENTER_VERTICAL);
    p.setBackground(ui.rounded(ui.night ? ui.surface2 : 0xFF1F1E1D, 20));
    p.setPadding(ui.dp(12), 0, ui.dp(6), 0);
    p.setElevation(ui.dpf(6));
    ImageView ic = new ImageView(c);
    ic.setImageResource(R.drawable.ic_spark);
    ic.setColorFilter(ui.night ? ui.accent : 0xFFE0886A);
    p.addView(ic, new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
    TextView t = ui.text(c, 14, 0xFFF2F0E8, Ui.MEDIUM);
    t.setText(what);
    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
    tl.leftMargin = ui.dp(8); tl.rightMargin = ui.dp(10);
    p.addView(t, tl);
    TextView stop = ui.text(c, 14, 0xFF1F1E1D, Ui.MEDIUM);
    stop.setText("Stop");
    stop.setGravity(Gravity.CENTER);
    stop.setPadding(ui.dp(14), 0, ui.dp(14), 0);
    stop.setBackground(ui.rounded(0xFFF2F0E8, 16));
    stop.setContentDescription("Stop Claude");
    stop.setOnClickListener(v -> Kill.set(c, true));
    p.addView(stop, new LinearLayout.LayoutParams(-2, ui.dp(32)));
    pill = p;
    WindowManager.LayoutParams pl = new WindowManager.LayoutParams(-2, ui.dp(42), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
    pl.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    pl.y = ui.dp(8);
    pl.setTitle("ClaudeGlowPill");
    wm.addView(pill, pl);
    pill.setAlpha(0f); pill.setTranslationY(-ui.dpf(12));
    pill.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(Ui.EMPH_DECEL).start();
  }

  static void hide() {
    final View e = edge, p = pill;
    edge = null; pill = null;
    if (e == null) return;
    final WindowManager wm = e.getContext().getSystemService(WindowManager.class);
    e.animate().alpha(0f).setDuration(200).withEndAction(() -> wm.removeView(e)).start();
    if (p != null) p.animate().alpha(0f).translationY(-p.getHeight() / 2f).setDuration(180).withEndAction(() -> wm.removeView(p)).start();
  }

  static final class EdgeView extends View {
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Matrix m = new Matrix();
    private SweepGradient sg;
    private final Ui ui;
    private float radius;

    EdgeView(Context c, Ui ui) {
      super(c);
      this.ui = ui;
      core.setStyle(Paint.Style.STROKE); halo.setStyle(Paint.Style.STROKE);
      core.setStrokeWidth(ui.dpf(3)); halo.setStrokeWidth(ui.dpf(12));
    }

    @Override protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      Display d = getDisplay();
      radius = ui.dpf(28);
      if (d != null) { RoundedCorner rc = d.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT); if (rc != null && rc.getRadius() > 0) radius = rc.getRadius(); }
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
      sg = new SweepGradient(w / 2f, h / 2f, new int[] { ui.accent, ui.amber, ui.rose, 0x00000000, ui.accent, ui.amber, 0x00000000, ui.accent }, null);
      core.setShader(sg); halo.setShader(sg);
    }

    @Override protected void onDraw(Canvas c) {
      float t = (SystemClock.uptimeMillis() % 6000) / 6000f * 360f;
      m.setRotate(t, getWidth() / 2f, getHeight() / 2f);
      sg.setLocalMatrix(m);
      float i = core.getStrokeWidth() / 2;
      r.set(i, i, getWidth() - i, getHeight() - i);
      halo.setAlpha(70);
      c.drawRoundRect(r, radius, radius, halo);
      c.drawRoundRect(r, radius, radius, core);
      postInvalidateOnAnimation();
    }
  }
}
