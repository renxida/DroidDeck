package claude.phone;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** The action log: everything Claude did, newest first. Small JSON in prefs for the prototype. */
final class LogStore {
  static final class Entry {
    String kind, title, detail; long at; boolean undoable, undone;
  }

  static synchronized void add(Context c, String kind, String title, String detail, boolean undoable) {
    List<Entry> l = load(c);
    Entry e = new Entry(); e.kind = kind; e.title = title; e.detail = detail; e.at = System.currentTimeMillis(); e.undoable = undoable;
    l.add(0, e);
    save(c, l);
  }

  static synchronized List<Entry> load(Context c) {
    ArrayList<Entry> out = new ArrayList<>();
    try {
      JSONArray a = new JSONArray(Ui.prefs(c).getString("log", "[]"));
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.getJSONObject(i);
        Entry e = new Entry();
        e.kind = o.optString("k"); e.title = o.optString("t"); e.detail = o.optString("d"); e.at = o.optLong("a");
        e.undoable = o.optBoolean("u"); e.undone = o.optBoolean("x");
        out.add(e);
      }
    } catch (Exception ignored) {}
    return out;
  }

  static synchronized void save(Context c, List<Entry> l) {
    JSONArray a = new JSONArray();
    try {
      for (Entry e : l) {
        JSONObject o = new JSONObject();
        o.put("k", e.kind); o.put("t", e.title); o.put("d", e.detail); o.put("a", e.at); o.put("u", e.undoable); o.put("x", e.undone);
        a.put(o);
      }
    } catch (Exception ignored) {}
    Ui.prefs(c).edit().putString("log", a.toString()).apply();
  }

  static void clear(Context c) { Ui.prefs(c).edit().remove("log").apply(); }

  /** Realistic sample history for screenshots and scroll tests. */
  static void seed(Context c, int n) {
    String[][] s = {
      { "message", "Texted Betty", "“Hey! I'll be home by 7…”", "1" },
      { "calendar", "Moved your dentist visit", "Thu 4:00 PM → Fri 9:30 AM", "1" },
      { "cart", "Reordered coffee beans", "Nijiya Market · $18.40 · you approved", "0" },
      { "apps", "Paused Instagram for the night", "After 25 minutes of scrolling", "1" },
      { "message", "Replied to Rob about Saturday", "“Sounds good, see you at 8”", "0" },
      { "calendar", "Added PADI pool session", "Sat 8:00 AM · Los Gatos", "1" },
      { "apps", "Turned on Do Not Disturb", "Until your 2 PM meeting ends", "1" },
      { "cart", "Booked a table at Tuyet Mai", "Fri 7:30 PM · 2 people", "1" },
      { "message", "Sent the superbill to Betty", "PDF, 2 pages", "0" },
      { "apps", "Saved a long read for later", "“The quiet hours” · 12 min", "1" },
    };
    ArrayList<Entry> l = new ArrayList<>();
    long now = System.currentTimeMillis();
    long step = 47L * 60 * 1000;
    for (int i = 0; i < n; i++) {
      String[] r = s[i % s.length];
      Entry e = new Entry(); e.kind = r[0]; e.title = r[1]; e.detail = r[2]; e.undoable = r[3].equals("1") && i < 6;
      e.at = now - 9 * 60 * 1000 - i * step;
      l.add(e);
    }
    save(c, l);
  }
}
