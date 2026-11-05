package com.controllerstar.x360bridge;

import android.app.Application;

/** Makes the Shizuku connection available to the activity, service and receivers. */
public class BridgeApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        BridgeManager.get(this);
    }
}
