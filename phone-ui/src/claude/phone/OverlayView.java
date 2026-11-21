package claude.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The assistant overlay: a floating card over whatever is on screen. States: listening, thinking,
 * speaking, a live canvas, a hold-to-confirm, done, offline. Built in code (no inflation) so the
 * first frame is cheap.
 */
final class OverlayView extends FrameLayout {
  interface Host { void dismiss(); void onAction(String id); }

  final Ui ui;
  final Host host;
  final Perf perf;
  final View scrim;
  final Card card;
  final OrbView orb;
  final TextView label, label2;
  final StreamText transcript, response;
  final ScrollView body;
  final LinearLayout bodyCol, chips, confirm;
  final FrameLayout canvasSlot;
  final HorizontalScrollView chipScroll;
  final TextView confirmMeta, confirmBubble, cancel;
  final HoldButton hold;
  final LinearLayout askBar;
  private int bottomInset, topInset;
  private ValueAnimator growAnim, transcriptAnim;
  private boolean leaving;
  private String committed = "";
  private boolean transcriptSmall;

  /** A LinearLayout capped at a fraction of the screen, so long answers scroll inside it. */
  final class Card extends LinearLayout {
    Card(Context c) { super(c); }
    @Override protected void onMeasure(int w, int h) {
      int max = (int) ((OverlayView.this.getHeight() > 0 ? OverlayView.this.getHeight() : getResources().getDisplayMetrics().heightPixels) * 0.82f) - topInset;
      super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
    }
  }

  OverlayView(Context c, Ui ui, Host host, Perf perf) {
    super(c);
    this.ui = ui; this.host = host; this.perf = perf;
    setClipChildren(false);

    scrim = new View(c);
    scrim.setBackgroundColor(ui.scrim);
    scrim.setAlpha(0f);
    scrim.setOnClickListener(v -> dismiss());
    scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(scrim, new LayoutParams(-1, -1));

    card = new Card(c);
    card.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable bg = ui.rounded(ui.surface, 28);
    if (ui.night) bg.setStroke(ui.dp(1), ui.outline);
    card.setBackground(bg);
    card.setElevation(ui.dpf(ui.night ? 2 : 12));
    card.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(16));
    card.setClipToOutline(true);
    card.setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), ui.dpf(28)); }
    });
    LayoutParams clp = new LayoutParams(-1, -2, Gravity.BOTTOM);
    clp.leftMargin = clp.rightMargin = ui.dp(10);
    addView(card, clp);

    View handle = new View(c);
    handle.setBackground(ui.rounded(ui.surface3, 2));
    LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
    hlp.gravity = Gravity.CENTER_HORIZONTAL; hlp.bottomMargin = ui.dp(8);
    card.addView(handle, hlp);

    LinearLayout header = new LinearLayout(c);
    header.setGravity(Gravity.CENTER_VERTICAL);
    orb = new OrbView(c, ui);
    header.addView(orb, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    FrameLayout labels = new FrameLayout(c);
    label = ui.text(c, 15, ui.onSurface2, Ui.MEDIUM);
    label2 = ui.text(c, 15, ui.onSurface2, Ui.MEDIUM);
    label2.setAlpha(0);
    labels.addView(label); labels.addView(label2);
    labels.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1);
    llp.leftMargin = ui.dp(8);
    header.addView(labels, llp);
    ImageView close = new ImageView(c);
    close.setImageResource(R.drawable.ic_close);
    close.setColorFilter(ui.onSurface2);
    close.setScaleType(ImageView.ScaleType.CENTER);
    close.setBackground(ui.rounded(ui.surface2, 20));
    close.setContentDescription("Close");
    close.setOnClickListener(v -> dismiss());
    header.addView(close, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
    card.addView(header, new LinearLayout.LayoutParams(-1, ui.dp(52)));

    body = new ScrollView(c);
    body.setVerticalScrollBarEnabled(false);
    body.setOverScrollMode(OVER_SCROLL_NEVER);
    body.setClipToPadding(false);
    bodyCol = new LinearLayout(c);
    bodyCol.setOrientation(LinearLayout.VERTICAL);
    body.addView(bodyCol, new FrameLayout.LayoutParams(-1, -2));
    card.addView(body, new LinearLayout.LayoutParams(-1, -2, 1));

    transcript = new StreamText(c);
    transcript.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
    transcript.setTypeface(Ui.SANS);
    transcript.setTextColor(ui.onSurface);
    transcript.setLineSpacing(ui.dpf(3), 1f);
    transcript.setIncludeFontPadding(false);
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
    tlp.topMargin = ui.dp(10);
    bodyCol.addView(transcript, tlp);

    response = new StreamText(c);
    response.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
    response.setTypeface(Ui.SANS);
    response.setTextColor(ui.onSurface);
    response.setLineSpacing(ui.dpf(6), 1f);
    response.setIncludeFontPadding(false);
    response.setVisibility(GONE);
    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
    rlp.topMargin = ui.dp(14);
    bodyCol.addView(response, rlp);

    canvasSlot = new FrameLayout(c);
    canvasSlot.setVisibility(GONE);
    canvasSlot.setClipToOutline(true);
    canvasSlot.setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), ui.dpf(18)); }
    });
    LinearLayout.LayoutParams cvlp = new LinearLayout.LayoutParams(-1, ui.dp(1));
    cvlp.topMargin = ui.dp(14);
    bodyCol.addView(canvasSlot, cvlp);

    confirm = new LinearLayout(c);
    confirm.setOrientation(LinearLayout.VERTICAL);
    confirm.setVisibility(GONE);
    confirmMeta = ui.text(c, 13, ui.onSurface3, Ui.MEDIUM);
    confirmMeta.setLetterSpacing(0.02f);
    confirm.addView(confirmMeta);
    confirmBubble = ui.text(c, 17, ui.onSurface, Ui.SANS);
    confirmBubble.setLineSpacing(ui.dpf(4), 1f);
    confirmBubble.setBackground(ui.rounded(ui.surface2, 20));
    confirmBubble.setPadding(ui.dp(16), ui.dp(13), ui.dp(16), ui.dp(14));
    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
    blp.topMargin = ui.dp(8);
    confirm.addView(confirmBubble, blp);
    hold = new HoldButton(c, ui, "Hold to send");
    LinearLayout.LayoutParams hbl = new LinearLayout.LayoutParams(-1, ui.dp(56));
    hbl.topMargin = ui.dp(16);
    confirm.addView(hold, hbl);
    cancel = ui.text(c, 15, ui.onSurface2, Ui.MEDIUM);
    cancel.setText("Cancel");
    cancel.setGravity(Gravity.CENTER);
    cancel.setOnClickListener(v -> host.onAction("cancel"));
    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, ui.dp(48));
    cl.topMargin = ui.dp(4);
    confirm.addView(cancel, cl);
    LinearLayout.LayoutParams cfl = new LinearLayout.LayoutParams(-1, -2);
    cfl.topMargin = ui.dp(16);
    bodyCol.addView(confirm, cfl);

    chipScroll = new HorizontalScrollView(c);
    chipScroll.setHorizontalScrollBarEnabled(false);
    chipScroll.setOverScrollMode(OVER_SCROLL_NEVER);
    chips = new LinearLayout(c);
    chipScroll.addView(chips);
    chipScroll.setVisibility(GONE);
    LinearLayout.LayoutParams csl = new LinearLayout.LayoutParams(-1, -2);
    csl.topMargin = ui.dp(14);
    card.addView(chipScroll, csl);

    askBar = new LinearLayout(c);
    askBar.setGravity(Gravity.CENTER_VERTICAL);
    askBar.setBackground(ui.rounded(ui.surface2, 26));
    askBar.setPadding(ui.dp(18), 0, ui.dp(6), 0);
    TextView hint = ui.text(c, 16, ui.onSurface3, Ui.SANS);
    hint.setText("Ask Claude");
    askBar.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView mic = new ImageView(c);
    mic.setImageResource(R.drawable.ic_mic);
    mic.setColorFilter(ui.onAccent);
    mic.setScaleType(ImageView.ScaleType.CENTER);
    mic.setBackground(ui.rounded(ui.accent, 20));
    mic.setContentDescription("Speak");
    mic.setOnClickListener(v -> host.onAction("mic"));
    askBar.addView(mic, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
    askBar.setVisibility(GONE);
    LinearLayout.LayoutParams abl = new LinearLayout.LayoutParams(-1, ui.dp(52));
    abl.topMargin = ui.dp(14);
    card.addView(askBar, abl);

    // Smooth growth: when the card gets taller, its top edge glides instead of jumping.
    card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      int oldH = ob - ot, newH = b - t;
      if (oldH == 0 || oldH == newH || leaving || dragging) return;
      float start = card.getTranslationY() + (newH - oldH);
      if (growAnim != null) growAnim.cancel();
      card.setTranslationY(start);
      growAnim = ValueAnimator.ofFloat(start, 0);
      growAnim.setDuration(240);
      growAnim.setInterpolator(Ui.STD);
      growAnim.addUpdateListener(a -> card.setTranslationY((float) a.getAnimatedValue()));
      growAnim.start();
    });

    setOnApplyWindowInsetsListener((v, insets) -> {
      int b = Math.max(insets.getInsets(WindowInsets.Type.systemBars()).bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
      topInset = insets.getInsets(WindowInsets.Type.systemBars()).top;
      if (b != bottomInset) {
        bottomInset = b;
        LayoutParams p = (LayoutParams) card.getLayoutParams();
        p.bottomMargin = b + ui.dp(10);
        card.setLayoutParams(p);
      }
      return WindowInsets.CONSUMED;
    });
  }

  // ---- Enter / exit ----

  void enter() {
    card.setAlpha(0f);
    card.setTranslationY(ui.dpf(28));
    card.setScaleX(0.97f); card.setScaleY(0.97f);
    card.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(Ui.EMPH_DECEL)
        .withEndAction(() -> perf.phase = "listen").start();
    scrim.animate().alpha(1f).setDuration(200).setInterpolator(Ui.STD_DECEL).start();
  }

  void dismiss() {
    if (leaving) return;
    leaving = true;
    perf.phase = "exit";
    card.animate().alpha(0f).translationY(card.getTranslationY() + ui.dpf(24)).setDuration(170).setInterpolator(Ui.EMPH_ACCEL)
        .withEndAction(host::dismiss).start();
    scrim.animate().alpha(0f).setDuration(170).start();
  }

  // ---- States ----

  void setLabel(String text) {
    TextView in = label.getAlpha() > 0.5f ? label2 : label, out = in == label ? label2 : label;
    if (TextUtils.equals(text, out.getText())) return;
    in.setText(text);
    in.setTranslationY(ui.dpf(6));
    in.animate().alpha(1f).translationY(0).setDuration(180).setInterpolator(Ui.STD).start();
    out.animate().alpha(0f).translationY(-ui.dpf(6)).setDuration(120).setInterpolator(Ui.STD).start();
  }

  void listen() {
    perf.phase = perf.phase.equals("enter") ? "enter" : "listen";
    orb.setState(OrbView.State.LISTENING);
    setLabel("Listening");
    committed = "";
    transcript.setTextColor(ui.onSurface3);
    transcript.setText("Go ahead, I'm listening");
    setTranscriptSmall(false, false);
    response.clear(); response.setVisibility(GONE);
    confirm.setVisibility(GONE); canvasSlot.setVisibility(GONE);
    chipScroll.setVisibility(GONE); askBar.setVisibility(GONE);
  }

  void partial(String commit, String tentative) {
    committed = commit;
    transcript.setTextColor(ui.onSurface);
    transcript.setTranscript(commit, tentative, ui.onSurface3);
  }

  void thinking() {
    perf.phase = "think";
    orb.setState(OrbView.State.THINKING);
    setLabel("Thinking");
    transcript.setTranscript(committed, null, 0);
    setTranscriptSmall(true, true);
  }

  private void setTranscriptSmall(boolean small, boolean animate) {
    if (small == transcriptSmall) return;
    transcriptSmall = small;
    if (transcriptAnim != null) transcriptAnim.cancel();
    final float from = small ? 22 : 16, to = small ? 16 : 22;
    final int c0 = small ? ui.onSurface : ui.onSurface2, c1 = small ? ui.onSurface2 : ui.onSurface;
    if (!animate) { transcript.setTextSize(TypedValue.COMPLEX_UNIT_SP, to); transcript.setTextColor(c1); return; }
    transcriptAnim = ValueAnimator.ofFloat(0, 1);
    transcriptAnim.setDuration(260);
    transcriptAnim.setInterpolator(Ui.STD);
    transcriptAnim.addUpdateListener(a -> {
      float f = (float) a.getAnimatedValue();
      transcript.setTextSize(TypedValue.COMPLEX_UNIT_SP, from + (to - from) * f);
      transcript.setTextColor(Ui.lerp(c0, c1, f));
    });
    transcriptAnim.start();
  }

  void token(String chunk) {
    if (response.getVisibility() != VISIBLE) {
      perf.phase = "stream";
      orb.setState(OrbView.State.SPEAKING);
      setLabel("Claude");
      response.setVisibility(VISIBLE);
    }
    perf.expectFrame("token_to_pixels");
    response.append(chunk, true);
  }

  void speakLevel(float l) { orb.setLevel(l); }

  void showCanvas(View web, int heightPx) {
    canvasSlot.setVisibility(VISIBLE);
    if (web.getParent() != canvasSlot) {
      if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
      canvasSlot.addView(web, new FrameLayout.LayoutParams(-1, -1));
    }
    setCanvasHeight(heightPx);
  }

  void setCanvasHeight(int px) {
    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) canvasSlot.getLayoutParams();
    if (p.height == px) return;
    p.height = px;
    canvasSlot.setLayoutParams(p);
  }

  void showConfirm(String meta, String text, String holdLabel, String doneLabel, HoldButton.Listener l) {
    perf.phase = "confirm";
    orb.setState(OrbView.State.IDLE);
    setLabel("Needs your OK");
    confirmMeta.setText(meta);
    confirmBubble.setText(text);
    hold.setLabels(holdLabel, doneLabel);
    hold.listener = l;
    confirm.setAlpha(0f);
    confirm.setTranslationY(ui.dpf(12));
    confirm.setVisibility(VISIBLE);
    confirm.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(Ui.EMPH_DECEL).start();
    post(() -> this.body.smoothScrollTo(0, bodyCol.getHeight()));
  }

  void done(String what, String... chipLabels) {
    perf.phase = "done";
    orb.setState(OrbView.State.DONE);
    setLabel(what);
    cancel.animate().alpha(0f).setDuration(150).withEndAction(() -> cancel.setVisibility(GONE)).start();
    setChips(chipLabels);
    performHapticFeedback(HapticFeedbackConstants.CONFIRM);
  }

  void offline(String msg) {
    perf.phase = "offline";
    orb.setState(OrbView.State.OFFLINE);
    setLabel("Offline");
    response.setVisibility(VISIBLE);
    response.clear();
    response.append(msg, true);
    setChips("Retry", "Save for later");
  }

  void setChips(String... labels) {
    chips.removeAllViews();
    for (int i = 0; i < labels.length; i++) {
      final String id = labels[i];
      TextView t = ui.text(getContext(), 14, ui.onSurface, Ui.MEDIUM);
      t.setText(id);
      t.setGravity(Gravity.CENTER);
      t.setPadding(ui.dp(16), 0, ui.dp(16), 0);
      t.setBackground(ui.outlined(ui.surface, ui.outline, 18));
      t.setOnClickListener(v -> host.onAction(id));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, ui.dp(40));
      if (i > 0) p.leftMargin = ui.dp(8);
      chips.addView(t, p);
      t.setAlpha(0f); t.setTranslationY(ui.dpf(8));
      t.animate().alpha(1f).translationY(0).setStartDelay(40L * i).setDuration(220).setInterpolator(Ui.EMPH_DECEL).start();
    }
    chipScroll.setVisibility(labels.length > 0 ? VISIBLE : GONE);
  }

  void showAskBar() { askBar.setVisibility(VISIBLE); }

  // ---- Drag down to dismiss ----

  private boolean dragging;
  private float downY, downX;
  private VelocityTracker vt;

  @Override public boolean onInterceptTouchEvent(MotionEvent e) {
    if (leaving) return true;
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downY = e.getRawY(); downX = e.getRawX(); dragging = false;
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain(); vt.addMovement(e);
        return false;
      case MotionEvent.ACTION_MOVE:
        if (vt != null) vt.addMovement(e);
        float dy = e.getRawY() - downY;
        boolean atTop = !body.canScrollVertically(-1);
        if (dy > ViewConfiguration.get(getContext()).getScaledTouchSlop() && Math.abs(dy) > Math.abs(e.getRawX() - downX) * 1.5f
            && atTop && e.getY() >= card.getTop() + card.getTranslationY()) {
          dragging = true;
          if (growAnim != null) growAnim.cancel();
          return true;
        }
    }
    return false;
  }

  @Override public boolean onTouchEvent(MotionEvent e) {
    if (!dragging) return super.onTouchEvent(e);
    if (vt != null) vt.addMovement(e);
    float dy = e.getRawY() - downY;
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_MOVE:
        card.setTranslationY(dy > 0 ? dy : dy * 0.2f);
        scrim.setAlpha(Math.max(0, 1 - dy / (card.getHeight() * 1.2f)));
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        vt.computeCurrentVelocity(1000);
        float v = vt.getYVelocity() / ui.density;
        dragging = false;
        if (dy > card.getHeight() * 0.28f || v > 900) {
          leaving = true;
          perf.phase = "exit";
          card.animate().translationY(card.getHeight() + bottomInset + ui.dpf(16)).setDuration(220).setInterpolator(Ui.EMPH_ACCEL)
              .withEndAction(host::dismiss).start();
          scrim.animate().alpha(0f).setDuration(220).start();
        } else {
          card.animate().translationY(0).setDuration(260).setInterpolator(Ui.EMPH_DECEL).start();
          scrim.animate().alpha(1f).setDuration(260).start();
        }
        return true;
    }
    return true;
  }
}
