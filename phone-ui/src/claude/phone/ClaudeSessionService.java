package claude.phone;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class ClaudeSessionService extends VoiceInteractionSessionService {
  @Override public VoiceInteractionSession onNewSession(Bundle args) { return new Session(this); }
}
