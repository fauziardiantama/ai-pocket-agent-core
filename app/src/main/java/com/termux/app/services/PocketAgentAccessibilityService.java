package com.termux.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.PowerManager;
import android.system.Os;
import android.view.accessibility.AccessibilityEvent;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pocket Agent Hardened Accessibility Supervisor.
 * Runs in the isolated :supervisor process.
 * Provides resilient background daemon supervision immune to Recent Apps dismissal.
 */
public class PocketAgentAccessibilityService extends AccessibilityService {

    private static final String LOG_TAG = "PocketAgentAccessibilityService";
    public static final String SUPERVISOR_CHANNEL_ID = "pocket_agent_supervisor_channel";
    public static final String SUPERVISOR_CHANNEL_NAME = "Pocket Agent Supervisor";
    public static final int SUPERVISOR_NOTIFICATION_ID = 2001;
    public static final String SOCKET_ABSTRACT_NAME = "ai.pa.core.paservice";

    private LocalServerSocket mServerSocket;
    private Thread mServerThread;
    private volatile boolean mIsRunning = false;
    private DaemonSupervisorManager mDaemonManager;
    private ExecutorService mClientThreadPool;
    private PowerManager.WakeLock mWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.logInfo(LOG_TAG, "PocketAgentAccessibilityService onCreate");
        mClientThreadPool = Executors.newCachedThreadPool();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAgent:SupervisorLock");
            mWakeLock.setReferenceCounted(false);
        }

        mDaemonManager = new DaemonSupervisorManager(this, mWakeLock);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Logger.logInfo(LOG_TAG, "PocketAgentAccessibilityService onServiceConnected");

        // Dynamic guard: enforce deaf service (0 eventTypes)
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.eventTypes = 0;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        setServiceInfo(info);

        // 1. Start static persistent foreground notification (importance LOW)
        setupNotificationChannel();
        Notification notification = buildForegroundNotification();
        startForeground(SUPERVISOR_NOTIFICATION_ID, notification);

        // 2. Start Abstract UNIX Domain Socket Server immediately
        startSocketServer();

        // 3. Ensure daemon directories, extract CLI binary, and reconcile state if bootstrap installed
        if (TermuxInstaller.isBootstrapInstalled()) {
            TermuxInstaller.setupDaemonDirectories();
            extractPaServiceClient(this);
            mDaemonManager.reconcileStateFiles();
        } else {
            Logger.logInfo(LOG_TAG, "Bootstrap not yet installed; deferring daemon directory setup and CLI extraction.");
        }
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                    SUPERVISOR_CHANNEL_ID,
                    SUPERVISOR_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Pocket Agent daemon process supervisor");
                channel.enableLights(false);
                channel.enableVibration(false);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildForegroundNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, SUPERVISOR_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        builder.setContentTitle(getString(R.string.pocket_agent_accessibility_service_name))
               .setContentText("Daemon process supervisor is active")
               .setSmallIcon(R.drawable.ic_service_notification)
               .setOngoing(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_SERVICE);
        }

        return builder.build();
    }

    private void startSocketServer() {
        if (mServerSocket != null || mIsRunning) return;

        mIsRunning = true;
        mServerThread = new Thread(() -> {
            try {
                Logger.logInfo(LOG_TAG, "Binding Linux Abstract Socket: \\0" + SOCKET_ABSTRACT_NAME);
                mServerSocket = new LocalServerSocket(SOCKET_ABSTRACT_NAME);

                while (mIsRunning) {
                    try {
                        LocalSocket client = mServerSocket.accept();
                        if (!mIsRunning) {
                            client.close();
                            break;
                        }
                        mClientThreadPool.submit(() -> mDaemonManager.handleClient(client));
                    } catch (IOException e) {
                        if (!mIsRunning) break;
                        Logger.logStackTraceWithMessage(LOG_TAG, "Error accepting client connection", e);
                    }
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Fatal error on LocalServerSocket: \\0" + SOCKET_ABSTRACT_NAME, e);
            } finally {
                closeServerSocket();
            }
        }, "PAService-SocketListener");

        mServerThread.start();
    }

    private void closeServerSocket() {
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException ignored) {}
            mServerSocket = null;
        }
    }

    public static synchronized void extractPaServiceClient(Context context) {
        if (!TermuxInstaller.isBootstrapInstalled()) return;
        try {
            File binDir = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            if (!binDir.exists()) binDir.mkdirs();

            File runDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/run");
            if (!runDir.exists()) runDir.mkdirs();

            File targetBinary = new File(binDir, "pa-service");
            File versionFile = new File(runDir, "pa-service.version");
            int currentVersionCode = BuildConfig.VERSION_CODE;

            boolean needsExtraction = !targetBinary.exists() || targetBinary.length() == 0;
            if (!needsExtraction && versionFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(versionFile))) {
                    String line = reader.readLine();
                    if (line == null || Integer.parseInt(line.trim()) != currentVersionCode) {
                        needsExtraction = true;
                    }
                } catch (Exception e) {
                    needsExtraction = true;
                }
            } else if (!needsExtraction) {
                needsExtraction = true;
            }

            if (!needsExtraction) return;

            Logger.logInfo(LOG_TAG, "Extracting pa-service CLI binary to " + targetBinary.getAbsolutePath());
            File tmpFile = new File(binDir, "pa-service.tmp");
            boolean copied = false;

            // 1. Coba salin dari nativeLibraryDir
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            if (nativeLibDir != null) {
                File src = new File(nativeLibDir, "libpa-service.so");
                if (src.exists() && src.length() > 0) {
                    copyFileStream(new java.io.FileInputStream(src), tmpFile);
                    copied = true;
                }
            }

            // 2. Fallback: Ekstraksi langsung dari APK zip entry lib/<abi>/libpa-service.so
            if (!copied) {
                copied = extractNativeLibFromApkZip(context, "libpa-service.so", tmpFile);
            }

            if (copied && tmpFile.exists() && tmpFile.length() > 0) {
                if (!tmpFile.renameTo(targetBinary)) {
                    Logger.logError(LOG_TAG, "Failed to rename pa-service.tmp to pa-service");
                    tmpFile.delete();
                    return;
                }
                Os.chmod(targetBinary.getAbsolutePath(), 0755);
                try (FileWriter writer = new FileWriter(versionFile)) {
                    writer.write(String.valueOf(currentVersionCode) + "\n");
                }
                Logger.logInfo(LOG_TAG, "pa-service CLI binary installed successfully (v" + currentVersionCode + ").");
            } else {
                Logger.logError(LOG_TAG, "Failed to extract pa-service CLI binary from both nativeLibraryDir and APK zip");
                if (tmpFile.exists()) tmpFile.delete();
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to check/extract pa-service asset", e);
        }
    }

    private static void copyFileStream(InputStream in, File dst) throws IOException {
        try (InputStream input = in; FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int r;
            while ((r = input.read(buffer)) != -1) {
                out.write(buffer, 0, r);
            }
        }
    }

    private static boolean extractNativeLibFromApkZip(Context context, String libFileName, File targetFile) {
        String apkPath = context.getPackageCodePath();
        if (apkPath == null) return false;
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apkPath)) {
            for (String abi : Build.SUPPORTED_ABIS) {
                String entryName = "lib/" + abi + "/" + libFileName;
                java.util.zip.ZipEntry entry = zipFile.getEntry(entryName);
                if (entry != null) {
                    copyFileStream(zipFile.getInputStream(entry), targetFile);
                    return true;
                }
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to extract " + libFileName + " from APK zip", e);
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Deaf Accessibility Service: eventTypes = 0
    }

    @Override
    public void onInterrupt() {
        Logger.logInfo(LOG_TAG, "PocketAgentAccessibilityService onInterrupt");
    }

    @Override
    public void onDestroy() {
        Logger.logInfo(LOG_TAG, "PocketAgentAccessibilityService onDestroy");
        mIsRunning = false;
        closeServerSocket();

        if (mServerThread != null) {
            mServerThread.interrupt();
        }

        if (mDaemonManager != null) {
            mDaemonManager.shutdown();
        }

        if (mClientThreadPool != null) {
            mClientThreadPool.shutdownNow();
        }

        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }

        stopForeground(true);
        super.onDestroy();
    }
}
