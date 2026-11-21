package claude.phone;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/** Design tokens: color, type, motion. One place, so every surface matches. */
final class Ui {
  final boolean night;
  final float density, scaled;
  final int bg, surface, surface2, surface3, onSurface, onSurface2, onSurface3, outline,
      accent, accentSoft, onAccent, scrim, success, danger, amber, rose;

  static final Typeface SANS = Typeface.create("sans-serif", Typeface.NORMAL);
  static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

  // Material 3 motion curves.
  static final TimeInterpolator EMPH_DECEL = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
  static final TimeInterpolator EMPH_ACCEL = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
  static final TimeInterpolator STD = new PathInterpolator(0.2f, 0f, 0f, 1f);
  static final TimeInterpolator STD_DECEL = new PathInterpolator(0f, 0f, 0f, 1f);

  private static Ui cachedDay, cachedNight;

  static Ui of(Context c) {
    boolean n = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
    if (n) { if (cachedNight == null || cachedNight.density != c.getResources().getDisplayMetrics().density) cachedNight = new Ui(c, true); return cachedNight; }
    if (cachedDay == null || cachedDay.density != c.getResources().getDisplayMetrics().density) cachedDay = new Ui(c, false);
    return cachedDay;
  }

  private Ui(Context c, boolean night) {
    this.night = night;
    density = c.getResources().getDisplayMetrics().density;
    scaled = c.getResources().getDisplayMetrics().scaledDensity;
    if (!night) {
      bg = 0xFFF5F4EE; surface = 0xFFFFFFFF; surface2 = 0xFFF3F1EA; surface3 = 0xFFE9E6DC;
      onSurface = 0xFF141413; onSurface2 = 0xFF5F5E5A; onSurface3 = 0xFF9A988F; outline = 0xFFE3E0D6;
      accent = 0xFFC96442; accentSoft = 0xFFF6E2D9; onAccent = 0xFFFFFFFF; scrim = 0x52000000;
      success = 0xFF2F7D52; danger = 0xFFB3261E; amber = 0xFFE3A055; rose = 0xFFD07A8C;
    } else {
      bg = 0xFF161615; surface = 0xFF242422; surface2 = 0xFF2E2E2B; surface3 = 0xFF3A3A36;
      onSurface = 0xFFF2F0E8; onSurface2 = 0xFFB9B6AC; onSurface3 = 0xFF85837B; outline = 0xFF3B3A37;
      accent = 0xFFE0886A; accentSoft = 0xFF4A3129; onAccent = 0xFF1A1918; scrim = 0x8C000000;
      success = 0xFF7BC896; danger = 0xFFF2B8B5; amber = 0xFFE8B070; rose = 0xFFE29AAB;
    }
  }

  int dp(float v) { return Math.round(v * density); }
  float dpf(float v) { return v * density; }

  static int alpha(int color, float a) {
    return (Math.round(Color.alpha(color) * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
  }

  static int lerp(int a, int b, float t) {
    return Color.argb(
        Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
        Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
        Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
        Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
  }

  TextView text(Context c, float sp, int color, Typeface tf) {
    TextView t = new TextView(c);
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    t.setTextColor(color);
    t.setTypeface(tf);
    t.setIncludeFontPadding(false);
    return t;
  }

  GradientDrawable rounded(int fill, float radiusDp) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(fill);
    g.setCornerRadius(dpf(radiusDp));
    return g;
  }

  GradientDrawable outlined(int fill, int stroke, float radiusDp) {
    GradientDrawable g = rounded(fill, radiusDp);
    g.setStroke(Math.max(1, dp(1)), stroke);
    return g;
  }

  static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("claude", Context.MODE_PRIVATE);
  }
}
