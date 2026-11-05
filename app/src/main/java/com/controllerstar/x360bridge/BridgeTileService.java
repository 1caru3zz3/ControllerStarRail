package com.controllerstar.x360bridge;

import android.annotation.TargetApi;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap to toggle the bridge without opening the app. */
@TargetApi(Build.VERSION_CODES.N)
public class BridgeTileService extends TileService {
    @Override
    public void onClick() {
        super.onClick();
        Intent service = new Intent(this, BridgeService.class)
                .setAction(BridgeService.ACTION_TOGGLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }
}
