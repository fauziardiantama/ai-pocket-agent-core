package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.app.services.PocketAgentAccessibilityService;
import com.termux.app.utils.CrashUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.models.ExecutionCommand;
import com.termux.shared.models.errors.Error;
import com.termux.shared.packages.PackageUtils;
import com.termux.shared.shell.TermuxShellEnvironmentClient;
import com.termux.shared.shell.TermuxTask;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (!PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message, MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError) + "\nTERMUX_FILES_DIR: " + MarkdownUtils.getMarkdownCodeForString(TermuxConstants.TERMUX_FILES_DIR_PATH, false);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            File[] PREFIX_FILE_LIST =  TERMUX_PREFIX_DIR.listFiles();
            // If prefix directory is empty or only contains the tmp directory
            if(PREFIX_FILE_LIST == null || PREFIX_FILE_LIST.length == 0 || (PREFIX_FILE_LIST.length == 1 && TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH.equals(PREFIX_FILE_LIST[0].getAbsolutePath()))) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains the tmp directory.");
            } else {
                setupDaemonDirectories();
                PocketAgentAccessibilityService.extractPaServiceClient(activity);
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    if (oldPath.contains("com.termux")) {
                                        oldPath = oldPath.replace("com.termux", "ai.pa.core");
                                    }
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods") ||
                                        zipEntryName.equals("etc/termux/bootstrap/termux-bootstrap-second-stage.sh")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Patching com.termux to ai.pa.core in staging files.");
                    replaceBytesInStaging(TERMUX_STAGING_PREFIX_DIR, "com.termux".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), "ai.pa.core".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

                    Logger.logInfo(LOG_TAG, "Setting up dpkg-deb wrapper and Debian diversions database.");
                    installDpkgDebWrapper(activity);

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    // Run Termux bootstrap second stage.
                    String termuxBootstrapSecondStageFile = TERMUX_PREFIX_DIR_PATH + "/etc/termux/bootstrap/termux-bootstrap-second-stage.sh";
                    if (!FileUtils.fileExists(termuxBootstrapSecondStageFile, false)) {
                        Logger.logInfo(LOG_TAG, "Not running Termux bootstrap second stage since script not found at \"" + termuxBootstrapSecondStageFile + "\" path.");
                    } else {
                        if (!FileUtils.fileExists(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", true)) {
                            Logger.logInfo(LOG_TAG, "Not running Termux bootstrap second stage since bash not found.");
                        }
                        Logger.logInfo(LOG_TAG, "Running Termux bootstrap second stage.");

                        ExecutionCommand executionCommand = new ExecutionCommand(-1,
                            termuxBootstrapSecondStageFile, null, null,
                            null, true, false);
                        executionCommand.commandLabel = "Termux Bootstrap Second Stage Command";
                        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_NORMAL;
                        TermuxTask termuxTask = TermuxTask.execute(activity, executionCommand, null, new TermuxShellEnvironmentClient(), true);
                        if (termuxTask == null || !executionCommand.isSuccessful() || executionCommand.resultData.exitCode != 0) {
                            // Generate debug report before deleting broken prefix directory to get `stat` info at time of failure.
                            showBootstrapErrorDialog(activity, whenDone, MarkdownUtils.getMarkdownCodeForString(executionCommand.toString(), true));

                            // Delete prefix directory as otherwise when app is restarted, the broken prefix directory would be used and logged into.
                            error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                            if (error != null)
                                Logger.logErrorExtended(LOG_TAG, error.toString());
                            return;
                        }
                    }

                    setupDaemonDirectories();
                    PocketAgentAccessibilityService.extractPaServiceClient(activity);

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        CrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            "## Bootstrap Error\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Error.getErrorMarkdownString(error), true, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    final File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 1) {
                        for (int i = 1; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)), true, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

    private static void copyFile(File src, File dst) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        }
    }

    private static boolean extractWrapperFromApk(Context context, File targetFile) {
        String apkPath = context.getPackageCodePath();
        if (apkPath == null) return false;
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(apkPath)) {
            for (String abi : Build.SUPPORTED_ABIS) {
                String entryName = "lib/" + abi + "/libdpkg-deb-wrapper.so";
                ZipEntry entry = zipFile.getEntry(entryName);
                if (entry == null) {
                    entryName = "lib/" + abi + "/libdpkg-deb-wrapper";
                    entry = zipFile.getEntry(entryName);
                }
                if (entry != null) {
                    try (java.io.InputStream in = zipFile.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(targetFile)) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) != -1) {
                            out.write(buf, 0, r);
                        }
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to extract wrapper from APK zip", e);
        }
        return false;
    }

    private static void installDpkgDebWrapper(Context context) throws Exception {
        File binDir = new File(TERMUX_STAGING_PREFIX_DIR_PATH, "bin");
        File origDpkgDeb = new File(binDir, "dpkg-deb");
        File realDpkgDeb = new File(binDir, "dpkg-deb.real");

        File tempWrapper = new File(binDir, "dpkg-deb.wrapper.tmp");
        boolean copied = false;
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeLibDir != null) {
            File wrapperSrc = new File(nativeLibDir, "libdpkg-deb-wrapper.so");
            if (!wrapperSrc.exists()) {
                wrapperSrc = new File(nativeLibDir, "libdpkg-deb-wrapper");
            }
            if (wrapperSrc.exists()) {
                copyFile(wrapperSrc, tempWrapper);
                copied = true;
            }
        }

        if (!copied) {
            copied = extractWrapperFromApk(context, tempWrapper);
        }

        if (copied && tempWrapper.exists() && tempWrapper.length() > 0) {
            if (origDpkgDeb.exists() && !realDpkgDeb.exists()) {
                if (!origDpkgDeb.renameTo(realDpkgDeb)) {
                    Logger.logError(LOG_TAG, "Failed to rename dpkg-deb to dpkg-deb.real");
                    tempWrapper.delete();
                    return;
                }
                Os.chmod(realDpkgDeb.getAbsolutePath(), 0755);
            }

            if (!tempWrapper.renameTo(origDpkgDeb)) {
                Logger.logError(LOG_TAG, "Failed to rename tempWrapper to dpkg-deb");
                realDpkgDeb.renameTo(origDpkgDeb);
                return;
            }
            Os.chmod(origDpkgDeb.getAbsolutePath(), 0755);
            Logger.logInfo(LOG_TAG, "dpkg-deb wrapper installed successfully.");

            File dpkgDir = new File(TERMUX_STAGING_PREFIX_DIR_PATH, "var/lib/dpkg");
            if (!dpkgDir.exists()) {
                dpkgDir.mkdirs();
            }
            File diversionsFile = new File(dpkgDir, "diversions");
            String diversionContent = "/data/data/ai.pa.core/files/usr/bin/dpkg-deb\n" +
                                      "/data/data/ai.pa.core/files/usr/bin/dpkg-deb.real\n:\n";
            try (FileOutputStream out = new FileOutputStream(diversionsFile)) {
                out.write(diversionContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Os.chmod(diversionsFile.getAbsolutePath(), 0644);
            Logger.logInfo(LOG_TAG, "dpkg diversions database registered at " + diversionsFile.getAbsolutePath());
        } else {
            Logger.logError(LOG_TAG, "Could not find or extract libdpkg-deb-wrapper! Keeping original dpkg-deb without diversion.");
        }
    }

    private static void replaceInFile(File file, byte[] oldBytes, byte[] newBytes) {
        if (!file.isFile() || file.length() == 0 || file.length() > 50 * 1024 * 1024) return;
        try {
            int len = (int) file.length();
            byte[] data = new byte[len];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = 0;
                while (read < len) {
                    int r = in.read(data, read, len - read);
                    if (r < 0) break;
                    read += r;
                }
            }
            boolean modified = false;
            int oldLen = oldBytes.length;
            for (int i = 0; i <= len - oldLen; i++) {
                boolean match = true;
                for (int j = 0; j < oldLen; j++) {
                    if (data[i + j] != oldBytes[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    System.arraycopy(newBytes, 0, data, i, oldLen);
                    modified = true;
                    i += oldLen - 1;
                }
            }
            if (modified) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to patch file: " + file.getAbsolutePath(), e);
        }
    }

    private static void replaceBytesInStaging(File dir, byte[] oldBytes, byte[] newBytes) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                replaceBytesInStaging(f, oldBytes, newBytes);
            } else if (f.isFile()) {
                replaceInFile(f, oldBytes, newBytes);
            }
        }
    }

    public static boolean isBootstrapInstalled() {
        File loginBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "login");
        File shBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "sh");
        return loginBin.exists() && shBin.exists();
    }

    public static void setupDaemonDirectories() {
        if (!isBootstrapInstalled()) return;
        File runDaemons = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/run/daemons");
        if (!runDaemons.exists()) {
            runDaemons.mkdirs();
        }
        File logDaemons = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "var/log/daemons");
        if (!logDaemons.exists()) {
            logDaemons.mkdirs();
        }
    }

}
