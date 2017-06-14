package pl.edu.agh.sm.powerdatacollector;

import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.util.Log;
import android.widget.Toast;

import com.jaredrummler.android.processes.AndroidProcesses;
import com.jaredrummler.android.processes.models.AndroidAppProcess;
import com.jaredrummler.android.processes.models.AndroidProcess;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class DataCollectingService extends IntentService {

    private static final String TAG = "DataCollectingService";

    private static final int SECONDS_PER_HOUR = 3600;
    private static final int CLOCK_TICKS_PER_SECOND = 100;
    private static final int MILLIS_PER_CLOCK_TICK = 1000 / CLOCK_TICKS_PER_SECOND;

    private static CpuInfo previousCpuInfo = new CpuInfo(0, 0);
    private static long previousMeasurementMillis;

    private LocationManager mLocationManager = null;
    private static final int LOCATION_INTERVAL = 10000;
    private static final float LOCATION_DISTANCE = 100f;


    public DataCollectingService() {
        super("DataCollectingService");
    }

    private BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context ctx, Intent batteryStatus) {
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;

            int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            boolean usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
            boolean acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;

            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            float batteryPct = level / (float)scale;

            Log.i("Battery", "isCharging" + isCharging);
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            float used;
            float prevBatteryState = Float.valueOf(sharedPref.getString(getString(R.string.batteryState), "0"));
            used = prevBatteryState - (batteryPct*100);
            used = (used < 0) ? 0.0f : used;

            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putString(getString(R.string.batteryState), String.valueOf(batteryPct * 100));
            editor.putString(getString(R.string.batteryUsed), String.valueOf((used)));
            editor.putString(getString(R.string.isCharging), String.valueOf(isCharging));
            editor.commit();
        }
    };


    private class LocationListener implements android.location.LocationListener {
        Location mLastLocation;

        public LocationListener(String provider) {
            Log.d(TAG, "LocationListener " + provider);
            mLastLocation = new Location(provider);
        }

        @Override
        public void onLocationChanged(Location location) {
            Log.d(TAG, "onLocationChanged: " + location);
            mLastLocation.set(location);
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putString(getString(R.string.longitude_property), String.valueOf(location.getLongitude()));
            editor.putString(getString(R.string.latitude_property), String.valueOf(location.getLatitude()));
            editor.commit();
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.d(TAG, "onProviderDisabled: " + provider);
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.d(TAG, "onProviderEnabled: " + provider);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.d(TAG, "onStatusChanged: " + provider);
        }

    }

    LocationListener[] mLocationListeners = new LocationListener[]{
            new LocationListener(LocationManager.PASSIVE_PROVIDER),
            new LocationListener(LocationManager.GPS_PROVIDER),
            new LocationListener(LocationManager.NETWORK_PROVIDER)
    };

    @Override
    public void onCreate() {
        Handler mHandler = new Handler(getMainLooper());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "Service created", Toast.LENGTH_SHORT).show();
            }
        });

        super.onCreate();

        Log.d(TAG, "initializeLocationManager - LOCATION_INTERVAL: "+ LOCATION_INTERVAL + " LOCATION_DISTANCE: " + LOCATION_DISTANCE);
        if (mLocationManager == null) {
            mLocationManager = (LocationManager) getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
        }

        try {
            mLocationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    LOCATION_INTERVAL,
                    LOCATION_DISTANCE,
                    mLocationListeners[0]
            );
            mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_INTERVAL,
                    LOCATION_DISTANCE,
                    mLocationListeners[1]
            );
            mLocationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_INTERVAL,
                    LOCATION_DISTANCE,
                    mLocationListeners[2]
            );
        } catch (java.lang.SecurityException ex) {
            Log.i(TAG, "fail to request location update, ignore", ex);
        } catch (IllegalArgumentException ex) {
            Log.d(TAG, "gps provider does not exist " + ex.getMessage());
        }

        this.registerReceiver(this.mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        stopMeasurements();
        saveMeasurements();
        startMeasurements();
        WakefulBroadcastReceiver.completeWakefulIntent(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        Handler mHandler = new Handler(getMainLooper());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "Service destroyed", Toast.LENGTH_SHORT).show();
            }
        });

        super.onDestroy();
        if (mLocationManager != null) {
            for (int i = 0; i < mLocationListeners.length; i++) {
                try {
                    if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                    mLocationManager.removeUpdates(mLocationListeners[i]);
                } catch (Exception ex) {
                    Log.e(TAG, "fail to remove location listener, ignore", ex);
                }
            }
        }
        this.unregisterReceiver(this.mBatInfoReceiver);
    }

    private void saveMeasurements() {
        String root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).toString();
        File dir = new File(root + File.separator+ "power_stats");
        dir.mkdirs();
        String filename = "stats-" + new SimpleDateFormat("yyyy-MM-dd-HHmmssSSS").format(new Date()) + ".txt";
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        StringBuilder sb = new StringBuilder();
        sb.append("start=").append(preferences.getString(getString(R.string.start_property), "")).append("\n");
        sb.append("end=").append(preferences.getString(getString(R.string.end_property), "")).append("\n");
        sb.append("longitude=").append(preferences.getString(getString(R.string.longitude_property), "")).append("\n");
        sb.append("latitude=").append(preferences.getString(getString(R.string.latitude_property), "")).append("\n");
        sb.append("cpu=").append(preferences.getString(getString(R.string.cpu_property), "")).append("\n");
        sb.append("total%=").append(preferences.getString(getString(R.string.totalPercentage_property), "")).append("\n");
        sb.append("cpuActivePower=").append(preferences.getString(getString(R.string.cpuActivePower_property), "")).append("\n");
        sb.append("wifiActivePower=").append(preferences.getString(getString(R.string.wifiActivePower_property), "")).append("\n");
        sb.append("mobileActivePower=").append(preferences.getString(getString(R.string.mobileActivePower_property), "")).append("\n");
        sb.append("runningApps=").append(preferences.getString(getString(R.string.runningProcesses), "")).append("\n");
        sb.append("isCharging=").append(preferences.getString(getString(R.string.isCharging), "")).append("\n");
        sb.append("batteryState%=").append(preferences.getString(getString(R.string.batteryState), "")).append("\n");
        sb.append("batteryUsed%=").append(preferences.getString(getString(R.string.batteryUsed), "")).append("\n");
        FileOutputStream outputStream;
        File file = new File(dir, filename);
        try {
            file.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "saveMeasurements:", e);
        }


        Log.d(TAG, sb.toString());
        Handler mHandler = new Handler(getMainLooper());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "Saving file", Toast.LENGTH_SHORT).show();
            }
        });

        try {
            outputStream = new FileOutputStream(file);
            PrintWriter pw = new PrintWriter(outputStream);
            pw.println(sb.toString());
            pw.flush();
            pw.close();
            outputStream.close();
        } catch (Exception e) {
            Log.e(TAG, "saveMeasurements: ", e);
        }
    }


    void startMeasurements() {
        try {
            previousCpuInfo = readCpuInfoForAllApps();
            previousMeasurementMillis = System.currentTimeMillis();
        } catch (Exception e) {
            Log.e(TAG, "startMeasurements: ", e);
        }
    }

    void stopMeasurements() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = sharedPref.edit();
        try {
            CpuInfo nextCpuInfo = readCpuInfoForAllApps();
            double cpuDrainMAh = 0;
            if (nextCpuInfo != null) {
                long cpuActiveTime = nextCpuInfo.activeTime - previousCpuInfo.activeTime;
                long cpuIdleTime = nextCpuInfo.idleTime - previousCpuInfo.idleTime;
                int ticksPerHour = CLOCK_TICKS_PER_SECOND * SECONDS_PER_HOUR;
                cpuDrainMAh = (getAveragePower("cpu.idle") * cpuIdleTime / ticksPerHour)
                        + (getAveragePower("cpu.active") * cpuActiveTime / ticksPerHour)
                        + (getAveragePower("cpu.idle") * cpuActiveTime / ticksPerHour);
                previousCpuInfo = nextCpuInfo;
            }
            cpuDrainMAh = (cpuDrainMAh < 0) ? 0.0 : cpuDrainMAh;
            previousMeasurementMillis = (previousMeasurementMillis == 0) ? System.currentTimeMillis() : previousMeasurementMillis;
            editor.putString(getString(R.string.start_property), String.valueOf(previousMeasurementMillis));
            editor.putString(getString(R.string.end_property), String.valueOf(System.currentTimeMillis()));
            editor.putString(getString(R.string.cpuActivePower_property), String.valueOf(getAveragePower("cpu.active", 3)));
            editor.putString(getString(R.string.wifiActivePower_property), String.valueOf(getAveragePower("wifi.active")));
            editor.putString(getString(R.string.mobileActivePower_property), String.valueOf(getAveragePower("radio.active")));
            editor.putString(getString(R.string.cpu_property), String.valueOf(cpuDrainMAh));
            editor.putString(getString(R.string.totalPercentage_property), String.valueOf((cpuDrainMAh / getAveragePower("battery.capacity") * 100)));
            editor.putString(getString(R.string.runningProcesses), String.valueOf(getNumberOfAppProcesses()));
            editor.commit();
        } catch (Exception e) {
            Log.e(TAG, "stopMeasurements: ", e);
        }
    }

    private int getNumberOfAppProcesses() {
        List<AndroidAppProcess> runningAppProcessInfo = AndroidProcesses.getRunningAppProcesses();
        Log.i(TAG, String.valueOf(runningAppProcessInfo.size()));
        return  runningAppProcessInfo.size();
    }

    private double getAveragePower(String componentState) throws Exception {
        return invokePowerProfileMethod("getAveragePower", Double.class,
                new Class[]{String.class}, new Object[]{componentState});
    }

    private double getAveragePower(String componentState, int level) throws Exception {
        return invokePowerProfileMethod("getAveragePower", Double.class,
                new Class[]{String.class, int.class}, new Object[]{componentState, level});
    }

    private <T> T invokePowerProfileMethod(String methodName, Class<T> returnType, Class<?>[] argTypes, Object[] args)
            throws Exception {
        final String powerProfileClass = "com.android.internal.os.PowerProfile";

        Object powerProfile = Class.forName(powerProfileClass)
                .getConstructor(Context.class).newInstance(getApplicationContext());

        return returnType.cast(Class.forName(powerProfileClass)
                .getMethod(methodName, argTypes)
                .invoke(powerProfile, args));
    }

    CpuInfo readCpuInfo() throws Exception {
        RandomAccessFile reader = new RandomAccessFile("/proc/" + Process.myPid() + "/stat", "r");
        String line = reader.readLine();
        reader.close();

        String[] split = line.split("\\s+");

        // utime stime cutime cstime
        long activeTimeTicks = Long.parseLong(split[13]) + Long.parseLong(split[14]) + Long.parseLong(split[15]) +
                Long.parseLong(split[16]);
        long processStartTimeTicks = Long.parseLong(split[21]);

        long systemUptimeMillis = SystemClock.uptimeMillis();
        long processUptimeTicks = (systemUptimeMillis / MILLIS_PER_CLOCK_TICK) - processStartTimeTicks;
        long idleTimeTicks = processUptimeTicks - activeTimeTicks;

        return new CpuInfo(activeTimeTicks, idleTimeTicks);
    }

    CpuInfo readCpuInfoForAllApps() throws Exception {
        List<AndroidProcess> runningAppProcessInfo = AndroidProcesses.getRunningProcesses();
        long active = 0;
        long idle = 0;
        for (AndroidProcess appProcess : runningAppProcessInfo) {
            try {
                RandomAccessFile reader = new RandomAccessFile("/proc/" + appProcess.pid + "/stat", "r");
                String line = reader.readLine();
                reader.close();

                String[] split = line.split("\\s+");

                // utime stime cutime cstime
                long activeTimeTicks = Long.parseLong(split[13]) + Long.parseLong(split[14]) + Long.parseLong(split[15]) +
                        Long.parseLong(split[16]);
                long processStartTimeTicks = Long.parseLong(split[21]);

                long systemUptimeMillis = SystemClock.uptimeMillis();
                long processUptimeTicks = (systemUptimeMillis / MILLIS_PER_CLOCK_TICK) - processStartTimeTicks;
                long idleTimeTicks = processUptimeTicks - activeTimeTicks;
                active += activeTimeTicks;
                idle += idleTimeTicks;
            } catch (Exception e) {
                continue;
            }
        }
        return new CpuInfo(active, idle);
    }

    static class CpuInfo {
        long activeTime;
        long idleTime;

        CpuInfo(long activeTime, long idleTime) {
            this.activeTime = activeTime;
            this.idleTime = idleTime;
        }
    }
}
