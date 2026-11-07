package com.termux.app;

import android.app.Application;

import com.termux.shared.crash.TermuxCrashUtils;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.logger.Logger;


public class TermuxApplication extends Application {
    public void onCreate() {
        super.onCreate();

        if (isSupervisorProcess()) {
            setLogLevel();
            Logger.logDebug("Starting Supervisor Application Process");
            return;
        }

        // Set crash handler for the app
        TermuxCrashUtils.setCrashHandler(this);

        // Set log level for the app
        setLogLevel();
    }

    private void setLogLevel() {
        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }

    private static boolean isSupervisorProcess() {
        String processName = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            processName = Application.getProcessName();
        } else {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/cmdline"))) {
                String line = reader.readLine();
                if (line != null) {
                    int nullIdx = line.indexOf('\0');
                    processName = (nullIdx >= 0) ? line.substring(0, nullIdx) : line.trim();
                }
            } catch (Exception ignored) {}
        }
        return processName != null && processName.endsWith(":supervisor");
    }
}

