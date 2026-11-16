package claude.assist;
import android.os.Bundle; import android.service.voice.*; import android.util.Log;
public class ClaudeSessionService extends VoiceInteractionSessionService {
  @Override public VoiceInteractionSession onNewSession(Bundle args) {
    return new VoiceInteractionSession(this) {
      @Override public void onShow(Bundle a, int flags) { super.onShow(a, flags); Log.i("ClaudeAssist", "SESSION_SHOWN flags=" + flags + " args=" + a); }
      @Override public void onHandleAssist(AssistState s) { Log.i("ClaudeAssist", "ASSIST_DATA structure=" + (s.getAssistStructure() != null) + " screenshot? see onHandleScreenshot"); }
      @Override public void onHandleScreenshot(android.graphics.Bitmap b) { Log.i("ClaudeAssist", "SCREENSHOT " + (b == null ? "null" : b.getWidth() + "x" + b.getHeight())); }
    };
  }
}
