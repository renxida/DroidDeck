package com.droiddeck.launcher.core;

import android.content.Context;

public abstract class SessionPart {
    private Context app;

    public final void attach(Context context) {
        this.app = context.getApplicationContext();
    }

    protected final Context app() {
        return app;
    }

    public abstract void start();

    public abstract void stop();

    public int suspendPid() {
        return -1;
    }

    /** True for a server the guest's programs draw through: it stops once the guest is gone. */
    public boolean stopsAfterGuest() {
        return false;
    }
}
