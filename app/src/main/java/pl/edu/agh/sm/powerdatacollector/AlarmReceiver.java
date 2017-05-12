package pl.edu.agh.sm.powerdatacollector;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.widget.Toast;

public class AlarmReceiver extends WakefulBroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Toast.makeText(context, "Alarm Received", Toast.LENGTH_SHORT).show();
        startWakefulService(context, new Intent(context, DataCollectingService.class));
    }
}
