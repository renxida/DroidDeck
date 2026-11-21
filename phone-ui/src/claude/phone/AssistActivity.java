package claude.phone;

import android.app.Activity;
import android.os.Bundle;

/** ACTION_ASSIST fallback: hand straight to the voice session. */
public class AssistActivity extends Activity {
  @Override protected void onCreate(Bundle b) { super.onCreate(b); ClaudeVIS.showDemo(this, ""); finish(); overridePendingTransition(0, 0); }
}
