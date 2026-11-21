package claude.phone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.MutableContextWrapper;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;

/**
 * Claude's live canvas: a WebView kept warm in the assistant's process with the canvas runtime
 * already loaded, so drawing a chart or a small tool is a JS call, not a page load. Moved between
 * windows with a MutableContextWrapper.
 */
final class CanvasHost {
  interface Listener { void onHeight(int px); void onRendered(double jsMs); void onEvent(String json); }

  private static WebView web;
  private static MutableContextWrapper ctx;
  private static boolean ready;
  private static String pending;
  private static Listener listener;
  private static long renderAt;
  private static final Handler main = new Handler(Looper.getMainLooper());

  static boolean isWarm() { return web != null; }

  /** Creates the WebView and loads the runtime. ~Hundreds of ms the first time per process, so do it early. */
  @SuppressLint("SetJavaScriptEnabled")
  static void prewarm(Context app) {
    if (web != null) return;
    long t = Perf.now();
    ctx = new MutableContextWrapper(app);
    web = new WebView(ctx);
    web.setBackgroundColor(Color.TRANSPARENT);
    web.setVerticalScrollBarEnabled(false);
    web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setDomStorageEnabled(false);
    s.setOffscreenPreRaster(true);
    s.setTextZoom(100);
    web.addJavascriptInterface(new Bridge(), "claudeBridge");
    web.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String url) {
        ready = true;
        Log.i(Perf.TAG, "MARK canvas_runtime_loaded");
        if (pending != null) { String p = pending; pending = null; eval(p); }
      }
    });
    web.loadUrl("file:///android_asset/canvas/host.html");
    Perf.mark("canvas_prewarm_create", t);
  }

  static WebView take(Context windowCtx, Listener l) {
    if (web == null) prewarm(windowCtx.getApplicationContext());
    if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
    ctx.setBaseContext(windowCtx);
    listener = l;
    return web;
  }

  static void release() {
    listener = null;
    if (web == null) return;
    if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
    ctx.setBaseContext(ctx.getApplicationContext());
    eval("window.clearCanvas && clearCanvas()");
  }

  /** Renders Claude-written content: {kind, src, theme}. */
  static void render(String kind, String src, boolean night) {
    try {
      JSONObject o = new JSONObject();
      o.put("kind", kind); o.put("src", src); o.put("theme", night ? "dark" : "light");
      renderAt = Perf.now();
      String js = "render(" + o + ")";
      if (!ready) pending = js; else eval(js);
    } catch (Exception e) { Log.e(Perf.TAG, "render", e); }
  }

  static void setTheme(boolean night) { eval("window.setTheme && setTheme('" + (night ? "dark" : "light") + "')"); }

  private static void eval(String js) { if (web != null) web.evaluateJavascript(js, null); }

  static final class Bridge {
    @JavascriptInterface public void height(final float cssPx) {
      main.post(() -> { if (listener != null && web != null) listener.onHeight(Math.round(cssPx * web.getResources().getDisplayMetrics().density)); });
    }
    @JavascriptInterface public void rendered(final double jsMs) {
      final long at = renderAt;
      main.post(() -> {
        Log.i(Perf.TAG, "LATENCY canvas_render_call_to_painted ms=" + Perf.fmt((Perf.now() - at) / 1e6) + " js_ms=" + Perf.fmt(jsMs));
        if (listener != null) listener.onRendered(jsMs);
      });
    }
    @JavascriptInterface public void event(final String json) {
      main.post(() -> { if (listener != null) listener.onEvent(json); });
    }
    @JavascriptInterface public void log(String s) { Log.i(Perf.TAG, "canvas: " + s); }
  }
}
