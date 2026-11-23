package ru.sdsk.nur;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    static final String PREFS = "nur";
    static final String KEY_SERVER = "server";
    static final String EXTRA_URL = "url";
    static final String ACTION_CHANGE_SERVER = "ru.sdsk.nur.CHANGE_SERVER";
    static final String ACTION_CHECK_UPDATE = "ru.sdsk.nur.CHECK_UPDATE";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private SwipeRefreshLayout swipe;
    private View errorView;
    private TextView errorText;
    private String server;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileCallback == null) return;
                Uri[] uris = null;
                Intent data = result.getData();
                if (result.getResultCode() == RESULT_OK) {
                    if (data != null && data.getClipData() != null) {
                        uris = new Uri[data.getClipData().getItemCount()];
                        for (int i = 0; i < uris.length; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data != null && data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    } else if (cameraUri != null) {
                        uris = new Uri[]{cameraUri};
                    }
                }
                fileCallback.onReceiveValue(uris);
                fileCallback = null;
            });

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> { });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createChannel();
        Push.init(this);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web != null && errorView.getVisibility() != View.VISIBLE && web.canGoBack()) {
                    web.goBack();
                } else {
                    finish();
                }
            }
        });
        server = prefs().getString(KEY_SERVER, null);
        if (server == null || ACTION_CHANGE_SERVER.equals(getIntent().getAction())) {
            showSetup(null);
        } else {
            showWeb(getIntent().getStringExtra(EXTRA_URL));
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
        Updater.check(this, ACTION_CHECK_UPDATE.equals(getIntent().getAction()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.install(this, false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_CHANGE_SERVER.equals(intent.getAction())) {
            showSetup(null);
            return;
        }
        if (ACTION_CHECK_UPDATE.equals(intent.getAction())) {
            Updater.check(this, true);
            return;
        }
        String path = intent.getStringExtra(EXTRA_URL);
        if (path != null && web != null && server != null) {
            web.loadUrl(absolute(path));
        } else if (path != null && server != null) {
            showWeb(path);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel("tickets", getString(R.string.channel_tickets),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.channel_tickets_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static String normalize(String input) {
        String value = input == null ? "" : input.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return value;
        if (!value.startsWith("https://") && !value.startsWith("http://")) value = "https://" + value;
        return value;
    }

    private String absolute(String path) {
        if (path == null || path.isEmpty()) return server + "/";
        if (path.startsWith("https://") || path.startsWith("http://")) {
            return sameHost(Uri.parse(path)) ? path : server + "/";
        }
        return server + (path.startsWith("/") ? path : "/" + path);
    }

    private boolean sameHost(Uri uri) {
        Uri base = Uri.parse(server);
        return uri.getHost() != null && uri.getHost().equalsIgnoreCase(base.getHost())
                && uri.getPort() == base.getPort() && "https".equalsIgnoreCase(uri.getScheme());
    }

    private void showSetup(String message) {
        if (web != null) {
            web.destroy();
            web = null;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(36);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ContextCompat.getColor(this, R.color.accent));
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Адрес сервера CRM");
        hint.setTextSize(16);
        hint.setPadding(0, dp(24), 0, dp(8));
        box.addView(hint);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        String current = prefs().getString(KEY_SERVER, BuildConfig.DEFAULT_SERVER);
        input.setText(current);
        input.setSelection(input.getText().length());
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(this);
        status.setTextColor(Color.rgb(0xB0, 0x24, 0x24));
        status.setPadding(0, dp(12), 0, dp(12));
        if (message != null) status.setText(message);
        box.addView(status);

        ProgressBar progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        box.addView(progress);

        Button connect = new Button(this);
        connect.setText("Подключиться");
        connect.setAllCaps(false);
        box.addView(connect, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Runnable attempt = () -> {
            String url = normalize(input.getText().toString());
            if (!url.startsWith("https://")) {
                status.setText("Нужен адрес с https://");
                return;
            }
            status.setText("");
            progress.setVisibility(View.VISIBLE);
            connect.setEnabled(false);
            io.execute(() -> {
                String error = check(url);
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    connect.setEnabled(true);
                    if (error != null) {
                        status.setText(error);
                        return;
                    }
                    boolean changed = !url.equals(prefs().getString(KEY_SERVER, null));
                    prefs().edit().putString(KEY_SERVER, url).apply();
                    if (changed) Push.forget(this);
                    server = url;
                    showWeb(null);
                });
            });
        };
        connect.setOnClickListener(v -> attempt.run());
        input.setOnEditorActionListener((v, actionId, event) -> {
            attempt.run();
            return true;
        });
        setContentView(box);
    }

    private static String check(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url + "/api/v1/health/live").openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            int code = connection.getResponseCode();
            connection.disconnect();
            return code == 200 ? null : "Сервер ответил кодом " + code + ". Это точно адрес CRM?";
        } catch (javax.net.ssl.SSLException e) {
            return "Сертификат сервера не принят телефоном. Нужен доверенный сертификат (Let's Encrypt) "
                    + "или установленный на телефон корневой сертификат вашей сети.";
        } catch (java.net.UnknownHostException e) {
            return "Адрес не найден. Проверьте имя сервера и интернет.";
        } catch (Exception e) {
            return "Не удалось подключиться: " + e.getClass().getSimpleName();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showWeb(String path) {
        FrameLayout root = new FrameLayout(this);
        swipe = new SwipeRefreshLayout(this);
        swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent));
        web = new WebView(this);
        swipe.addView(web);
        swipe.setOnRefreshListener(() -> web.reload());
        root.addView(swipe);
        errorView = buildError();
        errorView.setVisibility(View.GONE);
        root.addView(errorView);
        setContentView(root);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportZoom(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " NurApp/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (sameHost(uri)) return false;
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                swipe.setRefreshing(false);
                if (errorView.getVisibility() != View.VISIBLE) Push.register(MainActivity.this, server);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("Нет связи с сервером " + Uri.parse(server).getHost()
                            + ".\nПроверьте интернет и попробуйте ещё раз.");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showError("Сертификат сервера не принят телефоном.\nОбратитесь к администратору CRM.");
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                openPicker(params);
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, disposition, mime, length) -> download(url, userAgent, disposition, mime));
        web.loadUrl(absolute(path));
    }

    private View buildError() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(28), dp(28), dp(28));
        box.setBackgroundColor(ContextCompat.getColor(this, R.color.background));
        box.setClickable(true);
        errorText = new TextView(this);
        errorText.setTextSize(16);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(0, 0, 0, dp(20));
        box.addView(errorText);
        Button retry = new Button(this);
        retry.setText("Повторить");
        retry.setAllCaps(false);
        retry.setOnClickListener(v -> {
            errorView.setVisibility(View.GONE);
            web.reload();
        });
        box.addView(retry);
        Button change = new Button(this);
        change.setText("Сменить сервер");
        change.setAllCaps(false);
        change.setOnClickListener(v -> showSetup(null));
        box.addView(change);
        return box;
    }

    private void showError(String text) {
        swipe.setRefreshing(false);
        errorText.setText(text);
        errorView.setVisibility(View.VISIBLE);
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Нет приложения, чтобы открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    private void openPicker(WebChromeClient.FileChooserParams params) {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        String[] types = params.getAcceptTypes();
        List<String> mimes = new ArrayList<>();
        for (String type : types) {
            if (type != null && type.contains("/")) mimes.add(type);
        }
        pick.setType(mimes.size() == 1 ? mimes.get(0) : "*/*");
        if (mimes.size() > 1) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);

        List<Intent> extra = new ArrayList<>();
        cameraUri = null;
        try {
            File dir = new File(getCacheDir(), "camera");
            if (dir.isDirectory() || dir.mkdirs()) {
                File photo = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
                cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", photo);
                Intent camera = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                camera.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri);
                camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                extra.add(camera);
            }
        } catch (IllegalArgumentException e) {
            cameraUri = null;
        }
        Intent chooser = Intent.createChooser(pick, "Прикрепить файл");
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extra.toArray(new Intent[0]));
        try {
            filePicker.launch(chooser);
        } catch (ActivityNotFoundException e) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
    }

    private void download(String url, String userAgent, String disposition, String mime) {
        Uri uri = Uri.parse(url);
        if (!sameHost(uri)) {
            openExternal(uri);
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
            request.addRequestHeader("User-Agent", userAgent);
            request.setMimeType(mime);
            request.setTitle(name);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            getSystemService(DownloadManager.class).enqueue(request);
            Toast.makeText(this, "Скачивается: " + name, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Не удалось скачать файл", Toast.LENGTH_SHORT).show();
        }
    }
}
