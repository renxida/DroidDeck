package com.droiddeck.launcher.input

object GuestText {
    const val KEY_TAB = 15
    const val KEY_LEFTCTRL = 29
    const val KEY_V = 47
    private const val KEY_LEFTSHIFT = 42

    /** Presses the evdev keys that type [text] on a US layout; returns how many characters have no such key. */
    fun type(text: CharSequence, sendKey: (code: Int, down: Boolean) -> Unit): Int {
        var skipped = 0
        for (ch in text) {
            val plain = EvdevKeys.unshiftedChar(ch.code)
            val evdev = EvdevKeys.fromKeyCode(EvdevKeys.keycodeForChar(if (plain != 0) plain else ch.code))
            if (evdev <= 0) {
                skipped++
                continue
            }
            if (plain != 0) sendKey(KEY_LEFTSHIFT, true)
            sendKey(evdev, true)
            sendKey(evdev, false)
            if (plain != 0) sendKey(KEY_LEFTSHIFT, false)
        }
        return skipped
    }

    fun tap(code: Int, sendKey: (code: Int, down: Boolean) -> Unit) {
        sendKey(code, true)
        sendKey(code, false)
    }

    fun chord(modifier: Int, code: Int, sendKey: (code: Int, down: Boolean) -> Unit) {
        sendKey(modifier, true)
        tap(code, sendKey)
        sendKey(modifier, false)
    }

    /** Types the filled fields of a sign-in form, with Tab between them; returns the characters that could not be typed. */
    fun signIn(account: CharSequence, password: CharSequence, sendKey: (code: Int, down: Boolean) -> Unit): Int {
        var skipped = type(account, sendKey)
        if (account.isNotEmpty() && password.isNotEmpty()) tap(KEY_TAB, sendKey)
        skipped += type(password, sendKey)
        return skipped
    }
}
