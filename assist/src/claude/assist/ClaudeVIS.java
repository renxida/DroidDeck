package claude.assist;
import android.service.voice.VoiceInteractionService; import android.util.Log;
public class ClaudeVIS extends VoiceInteractionService {
  @Override public void onReady() { super.onReady(); Log.i("ClaudeAssist", "VIS_READY"); }
}
