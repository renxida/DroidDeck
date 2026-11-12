package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Base64
import android.util.Log
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import org.json.JSONObject

object SteamLive {
    private const val TAG = "SteamLive"
    private const val EVAL_TIMEOUT_MS = 20_000L

    fun launchOptions(context: Context, appId: Long): String? {
        if (!clientRunning()) return null
        val js = """
            new Promise(done => {
              let handle = null, answer;
              const finish = d => { clearTimeout(timer); try { handle?.unregister(); } catch (e) {} done(d); };
              const timer = setTimeout(() => finish(null), 4000);
              handle = SteamClient.Apps.RegisterForAppDetails(${appId and 0xFFFFFFFFL}, d => { answer = d; if (handle) finish(d); });
              if (answer !== undefined) finish(answer);
            }).then(d => typeof d?.strLaunchOptions === 'string' ? d.strLaunchOptions : null)
        """.trimIndent()
        return evaluate(context, js)
    }

    fun setLaunchOptions(context: Context, appId: Long, options: String): Boolean {
        if (!clientRunning()) return false
        val id = appId and 0xFFFFFFFFL
        val done = evaluate(context, "(async () => { SteamClient.Apps.SetAppLaunchOptions($id, ${JSONObject.quote(options)}); return 1; })()") != null
        if (done) Log.i(TAG, "live: launch options for $id")
        return done
    }

    fun setArt(context: Context, appId: Long, assetType: Int?, file: java.io.File?) {
        if (!clientRunning()) return
        val id = appId and 0xFFFFFFFFL
        val js = when {
            assetType == null -> return
            file == null -> "(async () => { try { await SteamClient.Apps.ClearCustomArtworkForApp($id, $assetType); } catch (e) {} return 1; })()"
            else -> {
                val data = runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }.getOrNull() ?: return
                val type = if (file.extension.equals("png", true)) "png" else "jpg"
                "(async () => { try { await SteamClient.Apps.SetCustomArtworkForApp($id, ${JSONObject.quote(data)}, ${JSONObject.quote(type)}, $assetType); } catch (e) {} return 1; })()"
            }
        }
        if (evaluate(context, js) != null) Log.i(TAG, "live: art $assetType for $id")
    }

    fun setLogoPosition(context: Context, appId: Long, position: LogoPosition.Value?) {
        if (!clientRunning()) return
        val id = appId and 0xFFFFFFFFL
        val js = if (position == null) "(async () => { try { await SteamClient.Apps.ClearCustomLogoPositionForApp($id); } catch (e) {} return 1; })()"
        else "(async () => { try { await SteamClient.Apps.SetCustomLogoPositionForApp($id, ${JSONObject.quote(LogoPosition.toJson(position).toString())}); } catch (e) {} return 1; })()"
        if (evaluate(context, js) != null) Log.i(TAG, "live: logo position for $id")
    }

    private fun clientRunning(): Boolean =
        SessionState.running && SessionState.mode == SessionService.MODE_STEAM && !SessionState.stopRequested && !SessionState.suspended

    private fun evaluate(context: Context, expression: String): String? {
        val answer = try {
            AgentGuest.steamEval(context, expression, EVAL_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "cdp: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (!answer.optBoolean("ok")) {
            Log.w(TAG, "cdp: ${answer.optJSONObject("error")?.optString("message")}")
            return null
        }
        return answer.opt("value")?.takeIf { it != JSONObject.NULL }?.toString()
    }
}
