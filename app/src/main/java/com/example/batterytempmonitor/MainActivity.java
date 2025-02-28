package com.example.batterytempmonitor;

import android.app.ActivityManager;
import android.content.Context;
import android.net.TrafficStats;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private Handler handler = new Handler();
    private Runnable updateMetricsTask;

    private TextView cpuUsageText, networkSpeedText, storageText;

    private long previousRxBytes = 0;
    private long previousTxBytes = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cpuUsageText = findViewById(R.id.cpuUsageText);
        networkSpeedText = findViewById(R.id.networkSpeedText);
        storageText = findViewById(R.id.storageText);

        // Start tracking CPU, Network, and Storage every 5 seconds
        updateMetricsTask = new Runnable() {
            @Override
            public void run() {
                updateMetrics();
                handler.postDelayed(this, 5000); // Repeat every 5 seconds
            }
        };
        handler.post(updateMetricsTask);
    }

    private void updateMetrics() {
        float cpuUsage = getCPUUsage();
        String networkSpeed = getNetworkSpeed();
        String storageInfo = getStorageInfo();

        // Update UI
        cpuUsageText.setText("CPU Usage: " + String.format("%.2f", cpuUsage) + "%");
        networkSpeedText.setText("Network: " + networkSpeed);
        storageText.setText("Storage: " + storageInfo);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(updateMetricsTask); // Stop updates when the app closes
    }
}
