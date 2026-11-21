package claude.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;

/**
 * Press and hold to confirm. The fill sweeps across in 650 ms; the label is drawn twice and clipped
 * so the filled part reads in the on-accent color. Letting go early springs back. One tick haptic on
 * press, a confirm haptic on completion.
 */
final class HoldButton extends View {
  interface Listener { void onConfirmed(); }

  static final int HOLD_MS = 650;
  private final Ui ui;
  private final Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG), fillP = new Paint(Paint.ANTI_ALIAS_FLAG),
      textP = new Paint(Paint.ANTI_ALIAS_FLAG), strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF r = new RectF();
  private final Path clip = new Path();
  private float progress;
  private ValueAnimator anim;
  private boolean done;
  private String label, holding = "Keep holding", doneLabel = "Sent";
  Listener listener;

  HoldButton(Context c, Ui ui, String label) {
    super(c);
    this.ui = ui; this.label = label;
    bgP.setColor(ui.accentSoft);
    fillP.setColor(ui.accent);
    textP.setTypeface(Ui.MEDIUM);
    textP.setTextSize(16 * ui.scaled);
    textP.setTextAlign(Paint.Align.CENTER);
    strokeP.setStyle(Paint.Style.STROKE);
    setFocusable(true);
    setClickable(true);
    setContentDescription(label);
    setMinimumHeight(ui.dp(56));
  }

  void setLabels(String label, String doneLabel) { this.label = label; this.doneLabel = doneLabel; setContentDescription(label); invalidate(); }
  boolean isDone() { return done; }

  @Override protected void onMeasure(int w, int h) {
    setMeasuredDimension(MeasureSpec.getSize(w), resolveSize(ui.dp(56), h));
  }

  @Override public boolean onTouchEvent(MotionEvent e) {
    if (done) return false;
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        animateTo(1f, (long) (HOLD_MS * (1 - progress)), true);
        setPressed(true);
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        setPressed(false);
        if (!done) animateTo(0f, 220, false);
        return true;
    }
    return true;
  }

  private void animateTo(float to, long ms, boolean linear) {
    if (anim != null) anim.cancel();
    anim = ValueAnimator.ofFloat(progress, to);
    anim.setDuration(Math.max(1, ms));
    anim.setInterpolator(linear ? new LinearInterpolator() : Ui.STD);
    anim.addUpdateListener(a -> {
      progress = (float) a.getAnimatedValue();
      invalidate();
      if (progress >= 1f && !done) complete();
    });
    anim.start();
  }

  private void complete() {
    done = true;
    performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    setContentDescription(doneLabel);
    invalidate();
    if (listener != null) listener.onConfirmed();
  }

  @Override protected void onDraw(Canvas c) {
    float w = getWidth(), h = getHeight(), rad = h / 2f;
    r.set(0, 0, w, h);
    c.drawRoundRect(r, rad, rad, bgP);
    clip.rewind();
    clip.addRoundRect(r, rad, rad, Path.Direction.CW);
    float fx = done ? w : w * progress;
    String t = done ? doneLabel : (progress > 0.02f ? holding : label);
    float ty = h / 2f - (textP.descent() + textP.ascent()) / 2f;
    c.save();
    c.clipPath(clip);
    c.drawRect(0, 0, fx, h, fillP);
    c.restore();
    // Label: on-accent where filled, accent elsewhere.
    c.save(); c.clipRect(fx, 0, w, h); textP.setColor(ui.night ? ui.onSurface : ui.accent); c.drawText(t, w / 2f, ty, textP); c.restore();
    if (fx > 0) { c.save(); c.clipRect(0, 0, fx, h); textP.setColor(ui.onAccent); c.drawText(t, w / 2f, ty, textP); c.restore(); }
    if (isFocused()) { strokeP.setColor(ui.accent); strokeP.setStrokeWidth(ui.dpf(2)); r.inset(ui.dpf(1), ui.dpf(1)); c.drawRoundRect(r, rad, rad, strokeP); }
  }

  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName("android.widget.Button");
    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "confirm"));
  }

  @Override public boolean performAccessibilityAction(int action, Bundle args) {
    if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && !done) { progress = 1; complete(); return true; }
    return super.performAccessibilityAction(action, args);
  }
}
