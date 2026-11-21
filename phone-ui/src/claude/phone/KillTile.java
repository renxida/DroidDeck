package claude.phone;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap pauses everything Claude is doing. */
public class KillTile extends TileService {
  @Override public void onStartListening() { refresh(); }
  @Override public void onClick() { Kill.set(this, !Kill.paused(this)); refresh(); }

  private void refresh() {
    Tile t = getQsTile();
    if (t == null) return;
    boolean p = Kill.paused(this);
    t.setLabel("Claude");
    t.setSubtitle(p ? "Paused" : "Working");
    t.setIcon(Icon.createWithResource(this, p ? R.drawable.ic_play : R.drawable.ic_spark));
    t.setState(p ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
    t.setContentDescription(p ? "Claude is paused. Tap to resume." : "Claude is working. Tap to pause.");
    t.updateTile();
  }
}
