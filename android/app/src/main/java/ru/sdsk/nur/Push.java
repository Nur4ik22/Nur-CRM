package ru.sdsk.nur;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Registers this phone's FCM token with the CRM session that is open in the WebView. */
final class Push {
    private static final String KEY_REGISTERED = "push_registered";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static boolean ready;

    private Push() { }

    private static final String KEY_CONFIG = "push_config";

    static void init(Context context) {
        if (ready) return;
        FirebaseOptions options = options(context);
        if (options == null) return;
        try {
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context, options);
            ready = true;
        } catch (RuntimeException e) {
            ready = false;
        }
    }

    private static FirebaseOptions options(Context context) {
        String appId = BuildConfig.FCM_APP_ID, apiKey = BuildConfig.FCM_API_KEY;
        String projectId = BuildConfig.FCM_PROJECT_ID, senderId = BuildConfig.FCM_SENDER_ID;
        if (appId.isEmpty()) {
            String saved = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, null);
            if (saved == null) return null;
            try {
                JSONObject json = new JSONObject(saved);
                appId = json.getString("app_id");
                apiKey = json.getString("api_key");
                projectId = json.getString("project_id");
                senderId = json.getString("sender_id");
            } catch (Exception e) {
                return null;
            }
        }
        return new FirebaseOptions.Builder().setApplicationId(appId).setApiKey(apiKey)
                .setProjectId(projectId).setGcmSenderId(senderId).build();
    }

    /** Reads push settings from the CRM server so push can be switched on without a new APK. */
    static void refreshConfig(Context context, String server, Runnable changed) {
        IO.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(server + "/api/v1/mobile/config").openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                if (connection.getResponseCode() != 200) return;
                StringBuilder text = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) text.append(line);
                }
                JSONObject pushConfig = new JSONObject(text.toString()).optJSONObject("push");
                String value = pushConfig == null ? null : pushConfig.toString();
                android.content.SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
                String old = prefs.getString(KEY_CONFIG, null);
                if (value == null ? old == null : value.equals(old)) return;
                if (value == null) prefs.edit().remove(KEY_CONFIG).apply();
                else prefs.edit().putString(KEY_CONFIG, value).apply();
                forget(context);
                if (ready && old != null && !FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.getInstance().delete();
                    ready = false;
                }
                init(context);
                if (changed != null) changed.run();
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    static void forget(Context context) {
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().remove(KEY_REGISTERED).apply();
    }

    private static String session(String cookies) {
        if (cookies == null) return null;
        for (String part : cookies.split(";")) {
            String item = part.trim();
            if (item.startsWith("crm_session=")) return item.substring("crm_session=".length());
        }
        return null;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return value;
        }
    }

    static void register(Context context, String server) {
        init(context);
        if (!ready || server == null) return;
        String cookies = CookieManager.getInstance().getCookie(server);
        String session = session(cookies);
        if (session == null) return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            String key = hash(server + "|" + session + "|" + token);
            String done = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_REGISTERED, "");
            if (key.equals(done)) return;
            IO.execute(() -> {
                if (post(server, cookies, token)) {
                    context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                            .putString(KEY_REGISTERED, key).apply();
                }
            });
        });
    }

    private static boolean post(String server, String cookies, String token) {
        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject();
            body.put("token", token);
            body.put("platform", "android");
            body.put("app_version", BuildConfig.VERSION_NAME);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            Uri base = Uri.parse(server);
            connection = (HttpURLConnection) new URL(server + "/api/v1/me/devices").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Cookie", cookies);
            connection.setRequestProperty("Origin", base.getScheme() + "://" + base.getAuthority());
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
