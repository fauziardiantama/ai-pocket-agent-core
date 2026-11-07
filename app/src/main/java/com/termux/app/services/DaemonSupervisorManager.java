package com.termux.app.services;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalSocket;
import android.os.PowerManager;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;

import com.termux.app.TermuxInstaller;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the lifecycle, execution, logging, and hybrid monitoring of daemons.
 * Runs in the :supervisor isolated process.
 */
public class DaemonSupervisorManager {

    private static final String LOG_TAG = "DaemonSupervisorManager";
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024; // 64 KB limit
    private static final long MAX_LOG_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final File mRunDir;
    private final File mLogDir;
    private final Map<String, TrackedDaemon> mActiveDaemons = new ConcurrentHashMap<>();
    private final Map<String, Integer> mBackoffCounters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService mExecutor = Executors.newScheduledThreadPool(4);
    private volatile boolean mIsShuttingDown = false;

    public static class TrackedDaemon {
        public final String name;
        public final String cmd;
        public final List<String> args;
        public final String cwd;
        public final boolean autostart;
        public final long spawnTimestampMs = System.currentTimeMillis();
        public volatile int pid;
        public volatile long startTimeTicks;
        public volatile java.lang.Process process; // null if detached post-LMK
        public volatile boolean isRunning;
        public volatile boolean stoppedByUser = false;
        public volatile java.util.concurrent.ScheduledFuture<?> restartFuture = null;

        public TrackedDaemon(String name, String cmd, List<String> args, String cwd, boolean autostart, int pid, long startTimeTicks, java.lang.Process process) {
            this.name = name;
            this.cmd = cmd;
            this.args = args;
            this.cwd = cwd;
            this.autostart = autostart;
            this.pid = pid;
            this.startTimeTicks = startTimeTicks;
            this.process = process;
            this.isRunning = true;
        }
    }

    public DaemonSupervisorManager(Context context, PowerManager.WakeLock wakeLock) {
        this.mContext = context;
        this.mWakeLock = wakeLock;
        this.mRunDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/run/daemons");
        this.mLogDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/log/daemons");
        ensureDirectories();
        this.mExecutor.scheduleWithFixedDelay(this::checkDetachedDaemons, 5, 5, TimeUnit.SECONDS);
    }

    private void ensureDirectories() {
        if (!TermuxInstaller.isBootstrapInstalled()) return;
        if (!mRunDir.exists()) mRunDir.mkdirs();
        if (!mLogDir.exists()) mLogDir.mkdirs();
    }

    private synchronized void updateWakeLockState() {
        if (mWakeLock == null) return;
        boolean needWakeLock = !mIsShuttingDown && !mActiveDaemons.isEmpty();
        try {
            if (needWakeLock && !mWakeLock.isHeld()) {
                mWakeLock.acquire();
                Logger.logInfo(LOG_TAG, "WakeLock acquired (active/pending daemons: " + mActiveDaemons.size() + ")");
            } else if (!needWakeLock && mWakeLock.isHeld()) {
                mWakeLock.release();
                Logger.logInfo(LOG_TAG, "WakeLock released (0 active/pending daemons)");
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to update WakeLock state", e);
        }
    }

    public void shutdown() {
        mIsShuttingDown = true;
        mExecutor.shutdownNow();
        updateWakeLockState();
    }

    /**
     * Handles incoming client connection from LocalServerSocket.
     */
    public void handleClient(LocalSocket client) {
        try {
            client.setSoTimeout(3000); // 3 seconds timeout to prevent starvation
            Credentials creds = client.getPeerCredentials();
            if (creds == null || creds.getUid() != Process.myUid()) {
                Logger.logError(LOG_TAG, "Unauthorized UID attempt: " + (creds != null ? creds.getUid() : "null"));
                client.close();
                return;
            }

            if (TermuxInstaller.isBootstrapInstalled()) {
                ensureDirectories();
                File paBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "pa-service");
                if (!paBin.exists()) {
                    PocketAgentAccessibilityService.extractPaServiceClient(mContext);
                }
            }

            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            byte[] buffer = new byte[MAX_PAYLOAD_BYTES];
            int totalRead = 0;
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') break;
                if (totalRead >= MAX_PAYLOAD_BYTES - 1) {
                    sendJsonResponse(out, createErrorJson("Payload size exceeded maximum limit of 64 KB"));
                    client.close();
                    return;
                }
                buffer[totalRead++] = (byte) b;
            }

            if (totalRead == 0) {
                client.close();
                return;
            }

            String line = new String(buffer, 0, totalRead, StandardCharsets.UTF_8).trim();
            JSONObject request = new JSONObject(line);
            JSONObject response = processRequest(request);
            sendJsonResponse(out, response);

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error handling client request", e);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {}
        }
    }

    private void sendJsonResponse(OutputStream out, JSONObject response) throws IOException {
        byte[] bytes = (response.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        out.flush();
    }

    private JSONObject processRequest(JSONObject req) {
        String action = req.optString("action", "");
        switch (action) {
            case "start":
                return handleStart(req);
            case "stop":
                return handleStop(req);
            case "restart":
                return handleRestart(req);
            case "status":
                return handleStatus(req);
            default:
                return createErrorJson("Unknown action: " + action);
        }
    }

    private JSONObject handleStart(JSONObject req) {
        String name = req.optString("name", "");
        if (!isValidDaemonName(name)) {
            return createErrorJson("Invalid daemon name: " + name);
        }
        String cmd = req.optString("cmd", "");
        if (cmd.isEmpty()) {
            return createErrorJson("Missing required parameter: cmd");
        }
        boolean autostart = req.optBoolean("autostart", false);
        String cwd = req.optString("cwd", TermuxConstants.TERMUX_HOME_DIR_PATH);

        List<String> argsList = new ArrayList<>();
        JSONArray jsonArgs = req.optJSONArray("args");
        if (jsonArgs != null) {
            for (int i = 0; i < jsonArgs.length(); i++) {
                argsList.add(jsonArgs.optString(i));
            }
        }

        // Check if already running
        TrackedDaemon existing = mActiveDaemons.get(name);
        if (existing != null && isProcessAliveWithTicks(existing.pid, existing.startTimeTicks)) {
            JSONObject res = new JSONObject();
            try {
                res.put("status", "error");
                res.put("msg", "Daemon already running");
                res.put("name", name);
                res.put("pid", existing.pid);
            } catch (JSONException ignored) {}
            return res;
        }

        return spawnDaemon(name, cmd, argsList, cwd, autostart);
    }

    private synchronized JSONObject spawnDaemon(String name, String cmd, List<String> args, String cwd, boolean autostart) {
        if (!TermuxInstaller.isBootstrapInstalled()) {
            return createErrorJson("Termux bootstrap is not installed yet");
        }
        ensureDirectories();

        TrackedDaemon prev = mActiveDaemons.get(name);
        if (prev != null) {
            prev.stoppedByUser = true;
            java.util.concurrent.ScheduledFuture<?> prevFuture = prev.restartFuture;
            if (prevFuture != null) {
                prevFuture.cancel(false);
                prev.restartFuture = null;
            }
        }

        File logFile = new File(mLogDir, name + ".log");

        // Rotate log if exceeds 10 MB
        if (logFile.exists() && logFile.length() >= MAX_LOG_SIZE_BYTES) {
            File oldLog = new File(mLogDir, name + ".log.old");
            if (oldLog.exists()) oldLog.delete();
            logFile.renameTo(oldLog);
        }

        List<String> fullCmd = new ArrayList<>();
        fullCmd.add(resolveExecutablePath(cmd));
        fullCmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(fullCmd);
        File workDir = new File(cwd);
        if (workDir.exists() && workDir.isDirectory()) {
            pb.directory(workDir);
        } else {
            pb.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        }

        Map<String, String> env = pb.environment();
        env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        env.remove("LD_LIBRARY_PATH");
        env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        env.put("TERM", "xterm-256color");
        env.put("LANG", "en_US.UTF-8");

        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));

        try {
            java.lang.Process process = pb.start();
            int pid = extractPid(process);
            long ticks = getProcessStartTimeTicks(pid);

            TrackedDaemon daemon = new TrackedDaemon(name, cmd, args, cwd, autostart, pid, ticks, process);
            mActiveDaemons.put(name, daemon);
            saveDaemonState(daemon);
            updateWakeLockState();

            // Active hybrid monitoring: event-driven wait on dedicated thread
            mExecutor.submit(() -> monitorProcess(daemon));

            JSONObject res = new JSONObject();
            res.put("status", "ok");
            res.put("msg", "Daemon started");
            res.put("name", name);
            res.put("pid", pid);
            return res;

        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to spawn daemon " + name, e);
            return createErrorJson("Failed to spawn process: " + e.getMessage());
        }
    }

    private void monitorProcess(TrackedDaemon daemon) {
        try {
            int exitCode = daemon.process.waitFor();
            daemon.isRunning = false;
            Logger.logInfo(LOG_TAG, "Daemon " + daemon.name + " exited with code " + exitCode);

            if (System.currentTimeMillis() - daemon.spawnTimestampMs >= 60_000L) {
                mBackoffCounters.remove(daemon.name);
            }

            if (daemon.stoppedByUser || mIsShuttingDown) {
                mActiveDaemons.remove(daemon.name, daemon);
                updateWakeLockState();
                return;
            }

            if (daemon.autostart) {
                scheduleAutoRestart(daemon);
            } else {
                if (mActiveDaemons.remove(daemon.name, daemon)) {
                    deleteDaemonState(daemon.name);
                }
                updateWakeLockState();
            }
        } catch (InterruptedException e) {
            // Service stopping
        }
    }

    private void scheduleAutoRestart(TrackedDaemon daemon) {
        if (mIsShuttingDown || daemon.stoppedByUser) {
            updateWakeLockState();
            return;
        }
        mActiveDaemons.put(daemon.name, daemon);
        int failures = mBackoffCounters.getOrDefault(daemon.name, 0);
        long delaySeconds;
        if (failures == 0) delaySeconds = 5;
        else if (failures == 1) delaySeconds = 15;
        else delaySeconds = 60;

        mBackoffCounters.put(daemon.name, failures + 1);
        Logger.logInfo(LOG_TAG, "Scheduling restart for " + daemon.name + " in " + delaySeconds + "s (backoff level " + (failures + 1) + ")");

        daemon.restartFuture = mExecutor.schedule(() -> {
            if (mIsShuttingDown || daemon.stoppedByUser || mActiveDaemons.get(daemon.name) != daemon) {
                updateWakeLockState();
                return;
            }
            Logger.logInfo(LOG_TAG, "Executing scheduled restart for " + daemon.name);
            spawnDaemon(daemon.name, daemon.cmd, daemon.args, daemon.cwd, daemon.autostart);
        }, delaySeconds, TimeUnit.SECONDS);
        updateWakeLockState();
    }

    private JSONObject handleStop(JSONObject req) {
        String name = req.optString("name", "");
        if (!isValidDaemonName(name)) {
            return createErrorJson("Invalid daemon name: " + name);
        }

        // Reset autostart and cancel any scheduled restart
        mBackoffCounters.remove(name);
        TrackedDaemon daemon = mActiveDaemons.remove(name);
        int pid = -1;
        long ticks = -1;
        if (daemon != null) {
            daemon.stoppedByUser = true;
            java.util.concurrent.ScheduledFuture<?> future = daemon.restartFuture;
            if (future != null) {
                future.cancel(false);
                daemon.restartFuture = null;
            }
            pid = daemon.pid;
            ticks = daemon.startTimeTicks;
        }
        JSONObject state = readDaemonState(name);
        if (pid <= 0 && state != null) {
            pid = state.optInt("pid", -1);
            ticks = state.optLong("start_time_ticks", -1);
        }
        deleteDaemonState(name);
        updateWakeLockState();

        if (daemon == null && state == null && pid <= 0) {
            return createErrorJson("Daemon not running or not found: " + name);
        }

        if (pid > 0 && isProcessAliveWithTicks(pid, ticks)) {
            try {
                // Send SIGTERM
                Os.kill(pid, OsConstants.SIGTERM);

                // Graceful wait up to 5 seconds
                for (int i = 0; i < 25; i++) {
                    Thread.sleep(200);
                    if (!isProcessAliveWithTicks(pid, ticks)) {
                        break;
                    }
                }

                // If still alive, escalate to SIGKILL
                if (isProcessAliveWithTicks(pid, ticks)) {
                    Os.kill(pid, OsConstants.SIGKILL);
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Error terminating daemon " + name, e);
            }
        }

        JSONObject res = new JSONObject();
        try {
            res.put("status", "ok");
            res.put("msg", "Daemon stopped");
            res.put("name", name);
            res.put("pid", pid);
        } catch (JSONException ignored) {}
        return res;
    }

    private JSONObject handleRestart(JSONObject req) {
        String name = req.optString("name", "");
        TrackedDaemon daemon = mActiveDaemons.get(name);
        String cmd;
        List<String> args;
        String cwd;
        boolean autostart;

        if (daemon != null) {
            cmd = daemon.cmd;
            args = daemon.args;
            cwd = daemon.cwd;
            autostart = daemon.autostart;
        } else {
            JSONObject state = readDaemonState(name);
            if (state == null) {
                return createErrorJson("Daemon not found: " + name);
            }
            cmd = state.optString("cmd");
            cwd = state.optString("cwd", TermuxConstants.TERMUX_HOME_DIR_PATH);
            autostart = state.optBoolean("autostart", false);
            args = new ArrayList<>();
            JSONArray jsonArgs = state.optJSONArray("args");
            if (jsonArgs != null) {
                for (int i = 0; i < jsonArgs.length(); i++) {
                    args.add(jsonArgs.optString(i));
                }
            }
        }

        handleStop(req);
        return spawnDaemon(name, cmd, args, cwd, autostart);
    }

    private JSONObject handleStatus(JSONObject req) {
        String name = req.optString("name", "");
        if (!name.isEmpty()) {
            return getSingleDaemonStatus(name);
        }

        JSONObject res = new JSONObject();
        try {
            res.put("status", "ok");
            JSONArray daemonsArray = new JSONArray();

            File[] stateFiles = mRunDir.listFiles((dir, fName) -> fName.endsWith(".state"));
            if (stateFiles != null) {
                for (File sf : stateFiles) {
                    String dName = sf.getName().replace(".state", "");
                    daemonsArray.put(getSingleDaemonStatusObject(dName));
                }
            }
            res.put("daemons", daemonsArray);
        } catch (JSONException ignored) {}
        return res;
    }

    private JSONObject getSingleDaemonStatus(String name) {
        JSONObject obj = getSingleDaemonStatusObject(name);
        JSONObject res = new JSONObject();
        try {
            res.put("status", "ok");
            res.put("daemon", obj);
        } catch (JSONException ignored) {}
        return res;
    }

    private JSONObject getSingleDaemonStatusObject(String name) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("name", name);
            TrackedDaemon daemon = mActiveDaemons.get(name);
            int pid = -1;
            long ticks = -1;
            boolean autostart = false;

            if (daemon != null) {
                pid = daemon.pid;
                ticks = daemon.startTimeTicks;
                autostart = daemon.autostart;
            } else {
                JSONObject state = readDaemonState(name);
                if (state != null) {
                    pid = state.optInt("pid", -1);
                    ticks = state.optLong("start_time_ticks", -1);
                    autostart = state.optBoolean("autostart", false);
                }
            }

            boolean running = (pid > 0 && isProcessAliveWithTicks(pid, ticks));
            obj.put("pid", pid);
            obj.put("running", running);
            obj.put("autostart", autostart);
            obj.put("status", running ? "RUNNING" : "STOPPED");
        } catch (JSONException ignored) {}
        return obj;
    }

    /**
     * Post-LMK State Reconciliation.
     * Scans all .state files, reconciles with /proc/<PID>/stat ticks.
     */
    public void reconcileStateFiles() {
        if (!TermuxInstaller.isBootstrapInstalled()) return;
        mExecutor.submit(() -> {
            Logger.logInfo(LOG_TAG, "Starting post-LMK state reconciliation.");
            File[] stateFiles = mRunDir.listFiles((dir, name) -> name.endsWith(".state"));
            if (stateFiles == null) {
                updateWakeLockState();
                return;
            }

            for (File stateFile : stateFiles) {
                String name = stateFile.getName().replace(".state", "");
                JSONObject state = readDaemonState(name);
                if (state == null) continue;

                int pid = state.optInt("pid", -1);
                long savedTicks = state.optLong("start_time_ticks", -1);
                boolean autostart = state.optBoolean("autostart", false);
                String cmd = state.optString("cmd", "");
                String cwd = state.optString("cwd", TermuxConstants.TERMUX_HOME_DIR_PATH);

                List<String> argsList = new ArrayList<>();
                JSONArray jsonArgs = state.optJSONArray("args");
                if (jsonArgs != null) {
                    for (int i = 0; i < jsonArgs.length(); i++) {
                        argsList.add(jsonArgs.optString(i));
                    }
                }

                if (pid > 0 && isProcessAliveWithTicks(pid, savedTicks)) {
                    Logger.logInfo(LOG_TAG, "Reconciled daemon " + name + " (PID " + pid + ") is RUNNING DETACHED.");
                    TrackedDaemon daemon = new TrackedDaemon(name, cmd, argsList, cwd, autostart, pid, savedTicks, null);
                    mActiveDaemons.put(name, daemon);
                    updateWakeLockState();
                } else {
                    Logger.logInfo(LOG_TAG, "Daemon " + name + " (PID " + pid + ") was CRASHED or KILLED_BY_LMK.");
                    deleteDaemonState(name);
                    if (autostart && !mIsShuttingDown) {
                        Logger.logInfo(LOG_TAG, "Auto-restarting killed daemon: " + name);
                        TrackedDaemon daemon = new TrackedDaemon(name, cmd, argsList, cwd, autostart, -1, -1, null);
                        scheduleAutoRestart(daemon);
                    }
                }
            }
            updateWakeLockState();
        });
    }

    private void checkDetachedDaemons() {
        if (mIsShuttingDown) return;
        for (TrackedDaemon d : mActiveDaemons.values()) {
            if (d.process == null && d.isRunning && !d.stoppedByUser) {
                if (!isProcessAliveWithTicks(d.pid, d.startTimeTicks)) {
                    d.isRunning = false;
                    Logger.logInfo(LOG_TAG, "Detached daemon " + d.name + " (PID " + d.pid + ") exited.");
                    if (System.currentTimeMillis() - d.spawnTimestampMs >= 60_000L) {
                        mBackoffCounters.remove(d.name);
                    }
                    if (d.autostart && !mIsShuttingDown && !d.stoppedByUser) {
                        scheduleAutoRestart(d);
                    } else {
                        if (mActiveDaemons.remove(d.name, d)) {
                            deleteDaemonState(d.name);
                        }
                        updateWakeLockState();
                    }
                }
            }
        }
    }

    private void saveDaemonState(TrackedDaemon daemon) {
        File stateFile = new File(mRunDir, daemon.name + ".state");
        try (FileWriter writer = new FileWriter(stateFile)) {
            JSONObject json = new JSONObject();
            json.put("name", daemon.name);
            json.put("pid", daemon.pid);
            json.put("start_time_ticks", daemon.startTimeTicks);
            json.put("cmd", daemon.cmd);
            json.put("cwd", daemon.cwd);
            json.put("autostart", daemon.autostart);
            json.put("args", new JSONArray(daemon.args));
            json.put("timestamp", System.currentTimeMillis());
            writer.write(json.toString());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save state for " + daemon.name, e);
        }
    }

    private JSONObject readDaemonState(String name) {
        File stateFile = new File(mRunDir, name + ".state");
        if (!stateFile.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(stateFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteDaemonState(String name) {
        File stateFile = new File(mRunDir, name + ".state");
        if (stateFile.exists()) {
            stateFile.delete();
        }
    }

    private boolean isProcessAlive(int pid) {
        if (pid <= 0) return false;
        return new File("/proc/" + pid).exists();
    }

    private boolean isProcessAliveWithTicks(int pid, long expectedTicks) {
        if (!isProcessAlive(pid)) return false;
        long currentTicks = getProcessStartTimeTicks(pid);
        return currentTicks > 0 && currentTicks == expectedTicks;
    }

    public static long getProcessStartTimeTicks(int pid) {
        File statFile = new File("/proc/" + pid + "/stat");
        if (!statFile.exists()) return -1;
        try (BufferedReader reader = new BufferedReader(new FileReader(statFile))) {
            String line = reader.readLine();
            if (line == null) return -1;
            int lastParen = line.lastIndexOf(')');
            if (lastParen == -1 || lastParen + 2 >= line.length()) return -1;
            String rest = line.substring(lastParen + 2);
            String[] tokens = rest.split("\\s+");
            // Field 3 (state) is index 0. Field 22 (starttime) is index 19.
            if (tokens.length > 19) {
                return Long.parseLong(tokens[19]);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private int extractPid(java.lang.Process process) {
        try {
            java.lang.reflect.Method pidMethod = process.getClass().getMethod("pid");
            long pid = (long) pidMethod.invoke(process);
            return (int) pid;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            return pidField.getInt(process);
        } catch (Throwable ignored) {}
        return -1;
    }

    private String resolveExecutablePath(String cmd) {
        if (cmd.startsWith("/") || cmd.startsWith("./") || cmd.startsWith("../")) {
            return cmd;
        }
        File inTermuxBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, cmd);
        if (inTermuxBin.exists()) {
            return inTermuxBin.getAbsolutePath();
        }
        return cmd;
    }

    private boolean isValidDaemonName(String name) {
        return name != null && name.matches("^[a-zA-Z0-9_-]+$");
    }

    private JSONObject createErrorJson(String msg) {
        JSONObject json = new JSONObject();
        try {
            json.put("status", "error");
            json.put("msg", msg);
        } catch (JSONException ignored) {}
        return json;
    }
}
