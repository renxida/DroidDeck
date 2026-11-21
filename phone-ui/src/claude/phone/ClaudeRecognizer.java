package claude.phone;

import android.content.Intent;
import android.speech.RecognitionService;

/** Placeholder recognizer (required for the assistant role). On-device ASR plugs in here. */
public class ClaudeRecognizer extends RecognitionService {
  @Override protected void onStartListening(Intent i, Callback cb) {}
  @Override protected void onCancel(Callback cb) {}
  @Override protected void onStopListening(Callback cb) {}
}
