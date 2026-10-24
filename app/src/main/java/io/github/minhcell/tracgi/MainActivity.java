package io.github.minhcell.tracgi;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Tra CGI: WebView chạy trang web/index.html nằm trong APK (assets/www).
 * - Chọn file 452.csv.gz qua hộp chọn file của Android
 * - Cấp quyền GPS cho nút "Lấy GPS"
 * - Lưu CSV/KML vào thư mục Download/TraCGI (cầu nối AndroidSave.saveFile)
 * - Đường /api/ocid: app tự gọi API OpenCelliD (không bị trình duyệt chặn CORS)
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/www/index.html";
    private static final int REQ_FILE = 101;
    private static final int REQ_LOC = 102;
    private static final int REQ_STORAGE = 103;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setUserAgentString(s.getUserAgentString() + " TraCGI/1.0");

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost()) && "/api/ocid".equals(u.getPath())) {
                    return proxy(u);
                }
                return loader.shouldInterceptRequest(u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // Link ngoài (Google Maps...) mở bằng app/trình duyệt khác
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    toast("Không mở được liên kết");
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Chọn file dữ liệu cell"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    toast("Không mở được trình chọn file");
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (hasLocation()) {
                    cb.invoke(origin, true, false);
                } else {
                    geoCallback = cb;
                    geoOrigin = origin;
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
                }
            }
        });

        web.addJavascriptInterface(new SaveBridge(), "AndroidSave");

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Gọi API OpenCelliD thay cho trang web. Chỉ cho đường /cell/getInArea trên máy chủ https. */
    private WebResourceResponse proxy(Uri u) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-proxy", "tracgi");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-store");
        try {
            String host = u.getQueryParameter("host");
            if (host == null || host.isEmpty()) host = "https://opencellid.org";
            host = host.replaceAll("/+$", "");
            if (!host.matches("(?i)^https://[a-z0-9.-]+(:\\d+)?$")) {
                return json(400, "Bad Request", "{\"error\":\"Máy chủ API không hợp lệ (phải là https://...)\"}", headers);
            }
            StringBuilder qs = new StringBuilder();
            for (String k : u.getQueryParameterNames()) {
                if ("host".equals(k)) continue;
                String v = u.getQueryParameter(k);
                if (qs.length() > 0) qs.append('&');
                qs.append(Uri.encode(k)).append('=').append(Uri.encode(v == null ? "" : v, ","));
            }
            URL url = new URL(host + "/cell/getInArea?" + qs);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "TraCGI/1.0 (Android)");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] body = in == null ? new byte[0] : readAll(in);
            c.disconnect();
            WebResourceResponse r = new WebResourceResponse("application/json", "utf-8",
                    new ByteArrayInputStream(body));
            r.setStatusCodeAndReasonPhrase(code, code >= 400 ? "Error" : "OK");
            r.setResponseHeaders(headers);
            return r;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).replace("\"", "'");
            return json(502, "Bad Gateway", "{\"error\":\"Không kết nối được máy chủ: " + msg + "\"}", headers);
        }
    }

    private WebResourceResponse json(int code, String reason, String body, Map<String, String> headers) {
        WebResourceResponse r = new WebResourceResponse("application/json", "utf-8",
                new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        r.setStatusCodeAndReasonPhrase(code, reason);
        r.setResponseHeaders(headers);
        return r;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    /** Cầu nối lưu file: AndroidSave.saveFile(tên, mime, base64) */
    public class SaveBridge {
        @JavascriptInterface
        public void saveFile(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TraCGI");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception("không tạo được file");
                    try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                        if (o == null) throw new Exception("không ghi được file");
                        o.write(data);
                    }
                } else {
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        runOnUiThread(() -> requestPermissions(
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
                        toast("Hãy cấp quyền bộ nhớ rồi bấm lưu lại");
                        return;
                    }
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), "TraCGI");
                    if (!dir.exists()) dir.mkdirs();
                    try (FileOutputStream o = new FileOutputStream(new File(dir, safe))) {
                        o.write(data);
                    }
                }
                toast("Đã lưu vào Download/TraCGI/" + safe);
            } catch (Exception e) {
                toast("Lỗi lưu file: " + e.getMessage());
            }
        }
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOC && geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocation(), false);
            geoCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
