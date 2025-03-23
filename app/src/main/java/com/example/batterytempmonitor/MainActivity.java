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

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobileconnectors.iot.AWSIotKeystoreHelper;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BatteryTempMonitor";

    // UPDATE THESE VALUES with your own AWS IoT information
    private static final String CUSTOMER_SPECIFIC_ENDPOINT = "adrff8x4zwsxy-ats.iot.eu-west-1.amazonaws.com";
    private static final String IOT_TOPIC = "device/metrics/data";
    private static final Regions MY_REGION = Regions.EU_WEST_1;

    // Certificate information
    private static final String KEYSTORE_NAME = "iot_keystore";
    private static final String KEYSTORE_PASSWORD = "password";
    private static final String CERTIFICATE_ID = "default";

    private Handler handler = new Handler();
    private Runnable updateMetricsTask;

    private TextView cpuUsageText, networkSpeedText, storageText, batteryText, statusText;
    private Button startStopButton;

    private long previousRxBytes = 0;
    private long previousTxBytes = 0;
    private boolean isCollecting = false;

    private AWSIotMqttManager mqttManager;
    private String clientId;
    private KeyStore clientKeyStore;
    private boolean isIotConnected = false;

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

        // Generate a unique client ID
        clientId = UUID.randomUUID().toString();

        // Initialize AWS IoT
        initializeAwsIot();

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

    private void initializeAwsIot() {
        statusText.setText("Initializing AWS IoT...");

        try {
            // Initialize the AWSIotMqttManager with the client endpoint and client ID
            mqttManager = new AWSIotMqttManager(clientId, CUSTOMER_SPECIFIC_ENDPOINT);

            // Set keep alive to 30 seconds
            mqttManager.setKeepAlive(30);

            // Load certificates and initialize the client keystore
            loadCertificates();

            // Connect to AWS IoT
            connectToIot();

        } catch (Exception e) {
            Log.e(TAG, "Error initializing AWS IoT", e);
            statusText.setText("AWS IoT Init Error: " + e.getMessage());
            logToFile("AWS IoT Init Error: " + e.getMessage());
        }
    }

    private void connectToIot() {
        // Connect using certificates
        try {
            mqttManager.connect(clientKeyStore, new AWSIotMqttClientStatusCallback() {
                @Override
                public void onStatusChanged(AWSIotMqttClientStatus status, Throwable throwable) {
                    Log.d(TAG, "IoT connection status: " + status);
                    logToFile("IoT connection status: " + status);

                    runOnUiThread(() -> {
                        switch (status) {
                            case Connected:
                                statusText.setText("Connected to AWS IoT");
                                isIotConnected = true;
                                break;
                            case Connecting:
                                statusText.setText("Connecting to AWS IoT...");
                                break;
                            case Reconnecting:
                                statusText.setText("Reconnecting to AWS IoT...");
                                isIotConnected = false;
                                break;
                            case ConnectionLost:
                                statusText.setText("AWS IoT Connection Lost");
                                isIotConnected = false;
                                break;
                            default:
                                if (throwable != null) {
                                    statusText.setText("AWS IoT Error: " + throwable.getMessage());
                                    logToFile("AWS IoT Error: " + throwable.getMessage());
                                }
                                isIotConnected = false;
                                break;
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Connection error", e);
            statusText.setText("IoT Connection Error: " + e.getMessage());
            logToFile("IoT Connection Error: " + e.getMessage());
        }
    }

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

        // Send data to AWS IoT
        sendDataToAWS(cpuUsage, previousRxBytes, previousTxBytes, batteryTemp, batteryLevel);
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
                return level * 100 / (float) scale;
            }
        }

        return 0.0f;
    }

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
            if (isIotConnected && mqttManager != null) {
                mqttManager.publishString(payloadStr, IOT_TOPIC, AWSIotMqttQos.QOS0);
                Log.i(TAG, "Data sent to AWS IoT");
                logToFile("Data sent to AWS IoT");

                runOnUiThread(() -> {
                    statusText.setText("Data sent at: " + sdf.format(new Date()));
                });
            } else {
                // No MQTT connection, save locally
                saveDataLocally(payloadStr);
                logToFile("No IoT connection, saved locally");

                runOnUiThread(() -> {
                    statusText.setText("No IoT connection, saved locally");
                });
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

            // Save locally if there's an error
            try {
                JSONObject payload = new JSONObject();
                payload.put("deviceId", android.os.Build.MODEL);
                payload.put("timestamp", System.currentTimeMillis());
                payload.put("error", e.getMessage());

                JSONObject metrics = new JSONObject();
                metrics.put("cpuUsage", cpuUsage);
                metrics.put("batteryTemp", batteryTemp);
                metrics.put("batteryLevel", batteryLevel);
                payload.put("metrics", metrics);

                saveDataLocally(payload.toString());
            } catch (JSONException ex) {
                Log.e(TAG, "Error saving data locally after failed send", ex);
            }
        }
    }

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

        // Disconnect from AWS IoT
        if (mqttManager != null && isIotConnected) {
            try {
                mqttManager.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting from AWS IoT", e);
            }
        }

        logToFile("Application destroyed");
    }

    /**
     * Load certificates from assets and create the keystore.
     * Call this before connecting to IoT.
     */
    /**
     * Load certificates from assets and create the keystore.
     * Call this before connecting to IoT.
     */
    private void loadCertificates() {
        try {
            // Check if keystore already exists
            String keystorePath = getFilesDir().getPath();
            if (AWSIotKeystoreHelper.isKeystorePresent(keystorePath, KEYSTORE_NAME)) {
                // Keystore is already present
                logToFile("Keystore already exists - loading...");
                clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(CERTIFICATE_ID,
                        keystorePath, KEYSTORE_NAME, KEYSTORE_PASSWORD);
                return;
            }

            // Create directory for certificates if it doesn't exist
            File internalDir = new File(getFilesDir(), "certificates");
            if (!internalDir.exists()) {
                internalDir.mkdirs();
            }

            // Copy certificates from assets to internal storage
            copyAssetToInternal("AmazonRootCA1.pem", internalDir);

            // Updated certificate filenames
            String privateKeyFilename = "private.pem.key";
            String certificateFilename = "certificate.pem.crt";  // Changed from public.pem.key to certificate.pem.crt

            copyAssetToInternal(privateKeyFilename, internalDir);
            copyAssetToInternal(certificateFilename, internalDir);

            // Use the correct file paths with the exact filenames
            String certificateFile = new File(internalDir, certificateFilename).getAbsolutePath();
            String privateKeyFile = new File(internalDir, privateKeyFilename).getAbsolutePath();

            // Create keystore using the proper method
            AWSIotKeystoreHelper.saveCertificateAndPrivateKey(
                    CERTIFICATE_ID,
                    certificateFile,
                    privateKeyFile,
                    keystorePath,
                    KEYSTORE_NAME,
                    KEYSTORE_PASSWORD
            );

            clientKeyStore = AWSIotKeystoreHelper.getIotKeystore(CERTIFICATE_ID,
                    keystorePath, KEYSTORE_NAME, KEYSTORE_PASSWORD);

            logToFile("Successfully created keystore with certificates");
        } catch (Exception e) {
            Log.e(TAG, "Error loading certificates", e);
            logToFile("Error loading certificates: " + e.getMessage());
            statusText.setText("Certificate Error: " + e.getMessage());
        }


    }

    /**
     * Copy a file from assets to internal storage
     */
    private void copyAssetToInternal(String assetFileName, File destDir) throws IOException {
        try (InputStream in = getAssets().open(assetFileName);
             FileOutputStream out = new FileOutputStream(new File(destDir, assetFileName))) {

            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

}