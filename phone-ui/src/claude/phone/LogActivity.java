package claude.phone;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Activity: everything Claude did, newest first, with undo where it's still possible, and the
 * pause control at the top. Plain framework views, recycled rows, no inflation.
 */
public class LogActivity extends Activity {
  private Ui ui;
  private final ArrayList<Object> rows = new ArrayList<>(); // String = section header, Entry = row
  private Adapter adapter;
  private TextView statusTitle, statusSub, pauseBtn;
  private View dot, empty;
  private ListView list;
  private String filter = "all";
  private Perf perf;

  @Override protected void onCreate(Bundle b) {
    long t = Perf.now();
    super.onCreate(b);
    ui = Ui.of(this);
    getWindow().setDecorFitsSystemWindows(false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    getWindow().setBackgroundDrawable(new ColorDrawable(ui.bg));
    perf = new Perf("log", t);
    setContentView(build());
    WindowInsetsController ic = getWindow().getDecorView().getWindowInsetsController();
    if (ic != null) {
      int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
      ic.setSystemBarsAppearance(ui.night ? 0 : light, light);
    }
    perf.attach(getWindow());
    perf.phase = "open";
    reload();
    Perf.mark("log_onCreate", t);
  }

  @Override protected void onResume() { super.onResume(); refreshStatus(); }
  @Override protected void onPause() { super.onPause(); perf.report(); }

  private View build() {
    FrameLayout root = new FrameLayout(this);
    list = new ListView(this);
    list.setDivider(null);
    list.setSelector(new ColorDrawable(Color.TRANSPARENT));
    list.setClipToPadding(false);
    list.setVerticalScrollBarEnabled(false);
    list.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
    list.addHeaderView(header(), null, false);
    adapter = new Adapter();
    list.setAdapter(adapter);
    list.setOnScrollListener(new AbsListView.OnScrollListener() {
      @Override public void onScrollStateChanged(AbsListView v, int s) { perf.phase = s == SCROLL_STATE_IDLE ? "idle" : "scroll"; }
      @Override public void onScroll(AbsListView v, int a, int b, int c) {}
    });
    root.addView(list, new FrameLayout.LayoutParams(-1, -1));

    empty = emptyState();
    root.addView(empty, new FrameLayout.LayoutParams(-1, -1));

    root.setOnApplyWindowInsetsListener((v, ins) -> {
      android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars());
      list.setPadding(0, s.top, 0, s.bottom + ui.dp(24));
      empty.setPadding(ui.dp(40), s.top, ui.dp(40), s.bottom);
      return WindowInsets.CONSUMED;
    });
    return root;
  }

  private View header() {
    LinearLayout h = new LinearLayout(this);
    h.setOrientation(LinearLayout.VERTICAL);
    h.setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(4));
    TextView title = ui.text(this, 32, ui.onSurface, Ui.SANS);
    title.setText("Activity");
    title.setAccessibilityHeading(true);
    h.addView(title);

    // Status card with the pause control.
    LinearLayout card = new LinearLayout(this);
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setBackground(ui.rounded(ui.surface, 24));
    card.setPadding(ui.dp(18), ui.dp(16), ui.dp(12), ui.dp(16));
    dot = new View(this);
    card.addView(dot, new LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)));
    LinearLayout tx = new LinearLayout(this);
    tx.setOrientation(LinearLayout.VERTICAL);
    statusTitle = ui.text(this, 16, ui.onSurface, Ui.MEDIUM);
    statusSub = ui.text(this, 14, ui.onSurface2, Ui.SANS);
    statusSub.setMaxLines(2);
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
    sp.topMargin = ui.dp(3);
    tx.addView(statusTitle); tx.addView(statusSub, sp);
    LinearLayout.LayoutParams txl = new LinearLayout.LayoutParams(0, -2, 1);
    txl.leftMargin = ui.dp(12); txl.rightMargin = ui.dp(12);
    card.addView(tx, txl);
    pauseBtn = ui.text(this, 15, ui.onAccent, Ui.MEDIUM);
    pauseBtn.setGravity(Gravity.CENTER);
    pauseBtn.setPadding(ui.dp(18), 0, ui.dp(18), 0);
    pauseBtn.setOnClickListener(v -> { Kill.set(this, !Kill.paused(this)); refreshStatus(); });
    card.addView(pauseBtn, new LinearLayout.LayoutParams(-2, ui.dp(44)));
    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
    cl.topMargin = ui.dp(18);
    h.addView(card, cl);

    // Filters.
    LinearLayout chips = new LinearLayout(this);
    String[][] f = { { "all", "All" }, { "message", "Messages" }, { "cart", "Purchases" }, { "calendar", "Calendar" }, { "apps", "Apps" } };
    android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    hs.setClipToPadding(false);
    hs.addView(chips);
    for (int i = 0; i < f.length; i++) {
      final String key = f[i][0];
      TextView c = ui.text(this, 14, ui.onSurface, Ui.MEDIUM);
      c.setText(f[i][1]);
      c.setTag(key);
      c.setGravity(Gravity.CENTER);
      c.setPadding(ui.dp(16), 0, ui.dp(16), 0);
      c.setOnClickListener(v -> { filter = key; styleChips(chips); reload(); });
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, ui.dp(36));
      if (i > 0) p.leftMargin = ui.dp(8);
      chips.addView(c, p);
    }
    styleChips(chips);
    LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
    hl.topMargin = ui.dp(16);
    h.addView(hs, hl);
    return h;
  }

  private void styleChips(LinearLayout chips) {
    for (int i = 0; i < chips.getChildCount(); i++) {
      TextView c = (TextView) chips.getChildAt(i);
      boolean on = filter.equals(c.getTag());
      c.setBackground(on ? ui.rounded(ui.onSurface, 18) : ui.outlined(Color.TRANSPARENT, ui.outline, 18));
      c.setTextColor(on ? ui.bg : ui.onSurface);
      c.setSelected(on);
    }
  }

  private View emptyState() {
    LinearLayout e = new LinearLayout(this);
    e.setOrientation(LinearLayout.VERTICAL);
    e.setGravity(Gravity.CENTER);
    OrbView orb = new OrbView(this, ui);
    orb.setState(OrbView.State.IDLE);
    e.addView(orb, new LinearLayout.LayoutParams(ui.dp(120), ui.dp(120)));
    TextView t = ui.text(this, 20, ui.onSurface, Ui.MEDIUM);
    t.setText("Nothing yet");
    t.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
    tl.topMargin = ui.dp(8);
    e.addView(t, tl);
    TextView s = ui.text(this, 15, ui.onSurface2, Ui.SANS);
    s.setText("When Claude does something for you, it shows up here, with a way to undo it.");
    s.setGravity(Gravity.CENTER);
    s.setLineSpacing(ui.dpf(3), 1f);
    LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
    sl.topMargin = ui.dp(8);
    e.addView(s, sl);
    e.setVisibility(View.GONE);
    return e;
  }

  private void refreshStatus() {
    boolean p = Kill.paused(this);
    statusTitle.setText(p ? "Claude is paused" : "Claude is working");
    statusSub.setText(p ? "Nothing runs until you resume." : "Booking a table at Tuyet Mai in workspace 2");
    dot.setBackground(ui.rounded(p ? ui.onSurface3 : ui.success, 5));
    pauseBtn.setText(p ? "Resume" : "Pause");
    pauseBtn.setTextColor(p ? ui.onAccent : ui.onSurface);
    pauseBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.alpha(ui.onSurface, 0.12f)),
        p ? ui.rounded(ui.accent, 22) : ui.rounded(ui.surface2, 22), null));
    pauseBtn.setContentDescription(p ? "Resume Claude" : "Pause Claude");
  }

  private void reload() {
    List<LogStore.Entry> all = LogStore.load(this);
    rows.clear();
    String last = null;
    for (LogStore.Entry e : all) {
      if (!filter.equals("all") && !filter.equals(e.kind)) continue;
      String sec = section(e.at);
      if (!sec.equals(last)) { rows.add(sec); last = sec; }
      rows.add(e);
    }
    adapter.notifyDataSetChanged();
    boolean none = all.isEmpty();
    empty.setVisibility(none ? View.VISIBLE : View.GONE);
    list.setVisibility(none ? View.GONE : View.VISIBLE);
  }

  private String section(long at) {
    if (DateUtils.isToday(at)) return "Today";
    if (DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS)) return "Yesterday";
    Calendar c = Calendar.getInstance(); c.setTimeInMillis(at);
    return DateUtils.formatDateTime(this, at, DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_ALL);
  }

  static int icon(String kind) {
    switch (kind) {
      case "message": return R.drawable.ic_message;
      case "cart": return R.drawable.ic_cart;
      case "calendar": return R.drawable.ic_calendar;
      default: return R.drawable.ic_apps;
    }
  }

  final class Adapter extends BaseAdapter {
    @Override public int getCount() { return rows.size(); }
    @Override public Object getItem(int i) { return rows.get(i); }
    @Override public long getItemId(int i) { return i; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int i) { return rows.get(i) instanceof String ? 0 : 1; }
    @Override public boolean isEnabled(int i) { return getItemViewType(i) == 1; }

    @Override public View getView(int i, View cv, ViewGroup parent) {
      Object o = rows.get(i);
      if (o instanceof String) {
        TextView t = (TextView) cv;
        if (t == null) {
          t = ui.text(LogActivity.this, 14, ui.onSurface2, Ui.MEDIUM);
          t.setPadding(ui.dp(20), ui.dp(22), ui.dp(20), ui.dp(8));
          t.setAccessibilityHeading(true);
        }
        t.setText((String) o);
        return t;
      }
      Row r = cv == null ? new Row(LogActivity.this) : (Row) cv;
      r.bind((LogStore.Entry) o);
      return r;
    }
  }

  final class Row extends LinearLayout {
    final ImageView ic; final TextView title, detail, time, undo; final FrameLayout icBox;
    LogStore.Entry e;

    Row(Context c) {
      super(c);
      setGravity(Gravity.CENTER_VERTICAL);
      setPadding(ui.dp(20), ui.dp(12), ui.dp(16), ui.dp(12));
      setMinimumHeight(ui.dp(72));
      setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.alpha(ui.onSurface, 0.08f)), null, new ColorDrawable(Color.WHITE)));
      icBox = new FrameLayout(c);
      icBox.setBackground(ui.rounded(ui.accentSoft, 20));
      ic = new ImageView(c);
      ic.setColorFilter(ui.accent);
      ic.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
      FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER);
      icBox.addView(ic, il);
      addView(icBox, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
      LinearLayout col = new LinearLayout(c);
      col.setOrientation(VERTICAL);
      LinearLayout top = new LinearLayout(c);
      top.setGravity(Gravity.CENTER_VERTICAL);
      title = ui.text(c, 16, ui.onSurface, Ui.SANS);
      title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
      top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      time = ui.text(c, 13, ui.onSurface3, Ui.SANS);
      LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
      tl.leftMargin = ui.dp(8);
      top.addView(time, tl);
      col.addView(top);
      detail = ui.text(c, 14, ui.onSurface2, Ui.SANS);
      detail.setSingleLine(); detail.setEllipsize(TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
      dl.topMargin = ui.dp(4);
      col.addView(detail, dl);
      LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
      cl.leftMargin = ui.dp(14);
      addView(col, cl);
      undo = ui.text(c, 14, ui.accent, Ui.MEDIUM);
      undo.setText("Undo");
      undo.setGravity(Gravity.CENTER);
      undo.setPadding(ui.dp(12), 0, ui.dp(12), 0);
      undo.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.alpha(ui.accent, 0.16f)), null, ui.rounded(Color.WHITE, 18)));
      undo.setOnClickListener(v -> {
        e.undone = true; e.undoable = false;
        List<LogStore.Entry> l = LogStore.load(LogActivity.this);
        for (LogStore.Entry x : l) if (x.at == e.at && x.title.equals(e.title)) { x.undone = true; x.undoable = false; }
        LogStore.save(LogActivity.this, l);
        bind(e);
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
      });
      LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(-2, ui.dp(40));
      ul.leftMargin = ui.dp(4);
      addView(undo, ul);
    }

    void bind(LogStore.Entry e) {
      this.e = e;
      ic.setImageResource(icon(e.kind));
      title.setText(e.title);
      title.setTextColor(e.undone ? ui.onSurface3 : ui.onSurface);
      title.setPaintFlags(e.undone ? title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG : title.getPaintFlags() & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
      detail.setText(e.undone ? "Undone" : e.detail);
      time.setText(DateUtils.formatDateTime(getContext(), e.at, DateUtils.FORMAT_SHOW_TIME));
      undo.setVisibility(e.undoable ? VISIBLE : GONE);
      setContentDescription(e.title + ", " + detail.getText() + ", " + time.getText());
    }
  }
}
