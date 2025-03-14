package com.example.batterytempmonitor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/*
import com.amplifyframework.AmplifyException;
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.core.AmplifyConfiguration;
import com.amplifyframework.iot.aws.AwsIotMqttManager;
import com.amplifyframework.iot.aws.MqttQualityOfService;
 */
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BatteryTempMonitor";
    private static final String IOT_ENDPOINT = "YOUR_IOT_ENDPOINT.iot.YOUR_REGION.amazonaws.com";
    private static final String IOT_TOPIC = "device/metrics/data";

    private Handler handler = new Handler();
    private Runnable updateMetricsTask;

    private TextView cpuUsageText, networkSpeedText, storageText, batteryText, statusText;
    private Button startStopButton;

    private long previousRxBytes = 0;
    private long previousTxBytes = 0;
    private boolean isCollecting = false;
  //  private AwsIotMqttManager mqttManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI components
        cpuUsageText = findViewById(R.id.cpuUsageText);
        networkSpeedText = findViewById(R.id.networkSpeedText);
        storageText = findViewById(R.id.storageText);
        batteryText = findViewById(R.id.batteryText);
        statusText = findViewById(R.id.statusText);
        startStopButton = findViewById(R.id.startStopButton);

        // Initialize AWS services
        try {
            //initializeAWS();
        } catch (Exception e) {
            logToFile("Failed to initialize AWS: " + e.getMessage());
            Log.e(TAG, "Failed to initialize AWS", e);
            statusText.setText("AWS Init Failed: " + e.getMessage());
        }

        // Set up button click listener
        startStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isCollecting) {
                    stopDataCollection();
                } else {
                    startDataCollection();
                }
            }
        });
    }

    /*
    private void initializeAWS() {
        try {
            // Try to configure Amplify from a configuration file
            try {
                AmplifyConfiguration config = AmplifyConfiguration.builder(getApplicationContext())
                        .devMenuEnabled(false)
                        .build();

                Amplify.addPlugin(new AWSCognitoAuthPlugin());
                Amplify.configure(config, getApplicationContext());

                logToFile("Amplify configured from file");
            } catch (AmplifyException e) {
                // If configuration file approach fails, try direct configuration
                logToFile("Config file approach failed, trying direct config: " + e.getMessage());

                // Set up IoT MQTT Manager directly
                mqttManager = AwsIotMqttManager.builder()
                        .endpoint(IOT_ENDPOINT)
                        .region("YOUR_REGION") // e.g., "us-east-1"
                        .build();
            }

            statusText.setText("AWS Connected");
            logToFile("AWS services initialized");
        } catch (Exception e) {
            logToFile("Error initializing AWS services: " + e.getMessage());
            Log.e(TAG, "Error initializing AWS services", e);
            statusText.setText("AWS Error: " + e.getMessage());
        }
    }
     */
    private void startDataCollection() {
        isCollecting = true;
        startStopButton.setText("Stop Monitoring");
        statusText.setText("Collecting data...");
        logToFile("Data collection started");

        // Start tracking metrics every 5 seconds
        updateMetricsTask = new Runnable() {
            @Override
            public void run() {
                updateMetrics();
                handler.postDelayed(this, 5000); // Repeat every 5 seconds
            }
        };
        handler.post(updateMetricsTask);
    }

    private void stopDataCollection() {
        isCollecting = false;
        startStopButton.setText("Start Monitoring");
        statusText.setText("Monitoring stopped");
        logToFile("Data collection stopped");

        if (updateMetricsTask != null) {
            handler.removeCallbacks(updateMetricsTask);
        }
    }

    private void updateMetrics() {
        float cpuUsage = getCPUUsage();
        String networkSpeed = getNetworkSpeed();
        String storageInfo = getStorageInfo();
        float batteryTemp = getBatteryTemperature();
        float batteryLevel = getBatteryLevel();

        // Update UI
        cpuUsageText.setText("CPU Usage: " + String.format("%.2f", cpuUsage) + "%");
        networkSpeedText.setText("Network: " + networkSpeed);
        storageText.setText("Storage: " + storageInfo);
        batteryText.setText("Battery: " + String.format("%.1f", batteryLevel) + "% | Temp: " +
                String.format("%.1f", batteryTemp) + "°C");

        // Send data to AWS TwinMaker
       // sendDataToAWS(cpuUsage, previousRxBytes, previousTxBytes, batteryTemp, batteryLevel);
    }

    private float getCPUUsage() {
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        activityManager.getMemoryInfo(memoryInfo);

        long usedMemory = memoryInfo.totalMem - memoryInfo.availMem;
        long totalMemory = memoryInfo.totalMem;

        return (float) usedMemory / totalMemory * 100;
    }

    private String getNetworkSpeed() {
        long currentRxBytes = TrafficStats.getTotalRxBytes();
        long currentTxBytes = TrafficStats.getTotalTxBytes();

        long rxSpeed = (currentRxBytes - previousRxBytes) / 1024; // KB/s
        long txSpeed = (currentTxBytes - previousTxBytes) / 1024; // KB/s

        previousRxBytes = currentRxBytes;
        previousTxBytes = currentTxBytes;

        return "Download: " + rxSpeed + " KB/s | Upload: " + txSpeed + " KB/s";
    }

    private String getStorageInfo() {
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long bytesAvailable = (long) stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        long bytesTotal = (long) stat.getBlockCountLong() * stat.getBlockSizeLong();
        long usedStorage = bytesTotal - bytesAvailable;

        return usedStorage / (1024 * 1024) + " MB / " + bytesTotal / (1024 * 1024) + " MB";
    }

    private float getBatteryTemperature() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);

        int temperature = batteryStatus != null ?
                batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
        return temperature / 10.0f; // Convert to degrees Celsius
    }

    private float getBatteryLevel() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);

        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            if (level != -1 && scale != -1) {
                return level * 100 / (float)scale;
            }
        }

        return 0.0f;
    }

    /*
    private void sendDataToAWS(float cpuUsage, long rxBytes, long txBytes,
                               float batteryTemp, float batteryLevel) {
        try {
            // Create JSON payload
            JSONObject payload = new JSONObject();
            payload.put("deviceId", android.os.Build.MODEL);
            payload.put("timestamp", System.currentTimeMillis());

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            payload.put("datetimeISO", sdf.format(new Date()));

            JSONObject metrics = new JSONObject();
            metrics.put("cpuUsage", cpuUsage);
            metrics.put("rxBytes", rxBytes);
            metrics.put("txBytes", txBytes);
            metrics.put("batteryTemp", batteryTemp);
            metrics.put("batteryLevel", batteryLevel);

            // Add storage information
            StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
            long bytesAvailable = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            long bytesTotal = stat.getBlockCountLong() * stat.getBlockSizeLong();
            metrics.put("storageAvailable", bytesAvailable);
            metrics.put("storageTotal", bytesTotal);

            payload.put("metrics", metrics);

            String payloadStr = payload.toString();
            logToFile("Preparing to send data: " + payloadStr);

            // Send via MQTT if connected
            if (mqttManager != null) {
                try {
                    mqttManager.publishString(payloadStr, IOT_TOPIC, MqttQualityOfService.AT_LEAST_ONCE);
                    Log.i(TAG, "Data sent to AWS via MQTT");
                    logToFile("Data sent to AWS via MQTT");

                    runOnUiThread(() -> {
                        statusText.setText("Data sent at: " + sdf.format(new Date()));
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Failed to publish via MQTT", e);
                    logToFile("Failed to publish via MQTT: " + e.getMessage());

                    // Fall back to local storage if MQTT fails
                    saveDataLocally(payloadStr);
                }
            } else {
                // No MQTT manager, save locally
                saveDataLocally(payloadStr);
                logToFile("No MQTT manager available, saved locally");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON payload", e);
            logToFile("Error creating JSON payload: " + e.getMessage());
            runOnUiThread(() -> {
                statusText.setText("Error creating payload: " + e.getMessage());
            });
        } catch (Exception e) {
            Log.e(TAG, "Error sending data to AWS", e);
            logToFile("Error sending data to AWS: " + e.getMessage());
            runOnUiThread(() -> {
                statusText.setText("Error sending data: " + e.getMessage());
            });
        }
    }
    */


    private void saveDataLocally(String data) {
        try {
            File directory = getExternalFilesDir("metrics_data");
            if (directory != null) {
                if (!directory.exists()) {
                    directory.mkdirs();
                }

                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
                String filename = "metrics_" + sdf.format(new Date()) + ".json";
                File file = new File(directory, filename);

                FileWriter fileWriter = new FileWriter(file);
                BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
                bufferedWriter.write(data);
                bufferedWriter.close();

                runOnUiThread(() -> {
                    statusText.setText("Data saved locally: " + filename);
                });

                Log.i(TAG, "Data saved locally: " + file.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "Error saving data locally", e);
            runOnUiThread(() -> {
                statusText.setText("Error saving locally: " + e.getMessage());
            });
        }
    }

    private void logToFile(String message) {
        try {
            File logDir = getExternalFilesDir("logs");
            if (logDir != null) {
                if (!logDir.exists()) {
                    logDir.mkdirs();
                }

                SimpleDateFormat dateSdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
                String filename = "log_" + dateSdf.format(new Date()) + ".txt";
                File logFile = new File(logDir, filename);

                FileWriter fileWriter = new FileWriter(logFile, true);
                BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);

                SimpleDateFormat timeSdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
                String logLine = "[" + timeSdf.format(new Date()) + "] " + message + "\n";

                bufferedWriter.write(logLine);
                bufferedWriter.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing to log file", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDataCollection();
        logToFile("Application destroyed");
    }
}