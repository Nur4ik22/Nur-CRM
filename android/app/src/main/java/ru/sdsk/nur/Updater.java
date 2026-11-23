package ru.sdsk.nur;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Checks GitHub Releases for a newer APK and installs it after one confirmation. */
final class Updater {
    private static final long CHECK_EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final String KEY_CHECKED = "update_checked_at";
    private static final String KEY_PENDING = "update_pending_file";

    private Updater() { }

    static void check(Activity activity, boolean manual) {
        SharedPreferences prefs = activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong(KEY_CHECKED, 0) < CHECK_EVERY_MS) return;
        prefs.edit().putLong(KEY_CHECKED, now).apply();
        Handler main = new Handler(Looper.getMainLooper());
        Executors.newSingleThreadExecutor().execute(() -> {
            Release release = latest();
            main.post(() -> {
                if (activity.isFinishing()) return;
                if (release == null) {
                    if (manual) Toast.makeText(activity, "Не удалось проверить обновления", Toast.LENGTH_SHORT).show();
                } else if (newer(release.version, BuildConfig.VERSION_NAME)) {
                    offer(activity, release);
                } else if (manual) {
                    Toast.makeText(activity, "Установлена последняя версия " + BuildConfig.VERSION_NAME, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    static final class Release {
        final String version;
        final String url;
        final String notes;

        Release(String version, String url, String notes) {
            this.version = version;
            this.url = url;
            this.notes = notes;
        }
    }

    private static Release latest() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO
                    + "/releases/latest").openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            JSONObject json = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            String tag = json.optString("tag_name", "").replaceFirst("^v", "");
            JSONArray assets = json.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.optString("name").endsWith(".apk")) {
                    return new Release(tag, asset.getString("browser_download_url"), json.optString("body", ""));
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static boolean newer(String candidate, String current) {
        String[] a = candidate.split("[^0-9]+");
        String[] b = current.split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static void offer(Activity activity, Release release) {
        String notes = release.notes.length() > 600 ? release.notes.substring(0, 600) + "…" : release.notes;
        new AlertDialog.Builder(activity)
                .setTitle("Доступна версия " + release.version)
                .setMessage("Сейчас установлена " + BuildConfig.VERSION_NAME + "."
                        + (notes.isEmpty() ? "" : "\n\n" + notes))
                .setPositiveButton("Обновить", (d, w) -> download(activity, release))
                .setNegativeButton("Позже", null)
                .show();
    }

    private static void download(Activity activity, Release release) {
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            Toast.makeText(activity, "Нет доступа к памяти телефона", Toast.LENGTH_SHORT).show();
            return;
        }
        File target = new File(dir, "Nur-" + release.version + ".apk");
        if (target.exists() && !target.delete()) target = new File(dir, "Nur-" + release.version + "-" + System.currentTimeMillis() + ".apk");
        DownloadManager manager = activity.getSystemService(DownloadManager.class);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.url))
                .setTitle("Обновление Nur " + release.version)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(target));
        long id = manager.enqueue(request);
        File apk = target;
        Context app = activity.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                app.unregisterReceiver(this);
                boolean ok = false;
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor != null && cursor.moveToFirst()) {
                        ok = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                                == DownloadManager.STATUS_SUCCESSFUL;
                    }
                }
                if (!ok) {
                    Toast.makeText(app, "Обновление не скачалось, попробуйте позже", Toast.LENGTH_LONG).show();
                    return;
                }
                app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_PENDING, apk.getAbsolutePath()).apply();
                install(activity);
            }
        };
        ContextCompat.registerReceiver(app, receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED);
        Toast.makeText(activity, "Скачивается обновление…", Toast.LENGTH_SHORT).show();
    }

    /** Opens the system installer for a downloaded update; called again from onResume after the permission screen. */
    static void install(Activity activity) {
        install(activity, true);
    }

    static void install(Activity activity, boolean askPermission) {
        SharedPreferences prefs = activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String path = prefs.getString(KEY_PENDING, null);
        if (path == null) return;
        File apk = new File(path);
        if (!apk.exists()) {
            prefs.edit().remove(KEY_PENDING).apply();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            if (!askPermission) return;
            Toast.makeText(activity, "Разрешите Nur устанавливать обновления и вернитесь назад", Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        prefs.edit().remove(KEY_PENDING).apply();
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }
}
