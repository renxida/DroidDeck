package claude.phone;

import android.content.Context;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.widget.TextView;
import java.util.ArrayList;

/**
 * Text that streams in word by word. Uses an Editable buffer so Android's DynamicLayout only
 * re-lays out the changed paragraph, and each new chunk fades in over 160 ms via a mutable span
 * that changes appearance only (no relayout).
 */
final class StreamText extends TextView {
  static final int FADE_MS = 160;

  static final class Fade extends CharacterStyle implements UpdateAppearance {
    float a; long start;
    @Override public void updateDrawState(TextPaint tp) { tp.setColor(Ui.alpha(tp.getColor(), a)); }
  }

  private final ArrayList<Fade> active = new ArrayList<>();
  private final Runnable tick = this::tick;
  private boolean ticking;
  /** Called after the next frame that shows appended text; used to measure token-to-pixels. */
  Runnable onShown;

  StreamText(Context c) {
    super(c);
    setText("", BufferType.EDITABLE);
  }

  void clear() { active.clear(); setText("", BufferType.EDITABLE); }

  void append(String chunk, boolean fade) {
    Editable e = getEditableText();
    int s = e.length();
    e.append(chunk);
    if (fade && chunk.length() > 0) {
      Fade f = new Fade(); f.a = 0; f.start = SystemClock.uptimeMillis();
      e.setSpan(f, s, e.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
      active.add(f);
      if (!ticking) { ticking = true; postOnAnimation(tick); }
    }
  }

  /** Live transcript: committed words in full color, the tentative tail dimmed. */
  void setTranscript(String committed, String tentative, int tentativeColor) {
    SpannableStringBuilder b = new SpannableStringBuilder(committed);
    if (tentative != null && !tentative.isEmpty()) {
      int s = b.length();
      if (s > 0) b.append(' ');
      b.append(tentative);
      b.setSpan(new android.text.style.ForegroundColorSpan(tentativeColor), s, b.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    setText(b, BufferType.EDITABLE);
  }

  private void tick() {
    long now = SystemClock.uptimeMillis();
    for (int i = active.size() - 1; i >= 0; i--) {
      Fade f = active.get(i);
      float t = (now - f.start) / (float) FADE_MS;
      f.a = t >= 1 ? 1 : 1 - (1 - t) * (1 - t);
      if (t >= 1) active.remove(i);
    }
    invalidate();
    if (!active.isEmpty()) postOnAnimation(tick); else ticking = false;
  }
}
