package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System notices for the main process: the TV booted, or this app was updated. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    static final String SLEEP = "io.freemytv.SLEEP";   // la minuterie du panneau (PanelView) est arrivée au bout

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (SLEEP.equals(action)) {
            PendingResult pending = goAsync();
            Maison.sleep(context, pending::finish);
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
            Maison.keepGoogleHomeOff(context);   // projo : réactivé au démarrage, il prendrait la touche Accueil
            HomeMode.gearService(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            HomeMode.verify(context);   // app updated: complete Free My TV mode if something is missing
            HomeMode.ensureRemoteService(context, 3_000);
            HomeMode.gearService(context);
        }
    }
}
