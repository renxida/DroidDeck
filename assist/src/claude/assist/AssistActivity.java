package claude.assist;
import android.app.Activity; import android.os.Bundle; import android.util.Log;
public class AssistActivity extends Activity {
  @Override protected void onCreate(Bundle b) { super.onCreate(b); Log.i("ClaudeAssist", "ASSIST_ACTIVITY"); finish(); }
}
