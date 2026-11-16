package claude.assist;
import android.content.Intent; import android.speech.RecognitionService;
public class ClaudeRecognizer extends RecognitionService {
  @Override protected void onStartListening(Intent i, Callback c) {}
  @Override protected void onCancel(Callback c) {}
  @Override protected void onStopListening(Callback c) {}
}
