package com.droiddeck.launcher.session

internal object SessionVisibilityPolicy {
    fun isHidden(
        activityStarted: Boolean,
        defaultDisplayOn: Boolean,
        activityOnDefaultDisplay: Boolean,
        activityDisplayOn: Boolean,
    ): Boolean = !sessionHasVisibleSurface(
        panelVisible = activityStarted,
        screenOn = if (activityOnDefaultDisplay) defaultDisplayOn else activityDisplayOn,
    )
}
