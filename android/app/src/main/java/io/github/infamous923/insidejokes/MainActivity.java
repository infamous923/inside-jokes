package io.github.infamous923.insidejokes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shows the Inside Jokes game, which ships inside the app (no website needed). */
public class MainActivity extends ComponentActivity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/www/index.html";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingShare;
    private boolean pageReady;
    private volatile boolean awaitingClipboard;
    private String lastClip;

    private final ActivityResultLauncher<Intent> photoPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (fileCallback == null) return;
                Uri[] uris = null;
                Intent data = result.getData();
                if (result.getResultCode() == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        uris = new Uri[n];
                        for (int i = 0; i < n; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                    } else if (data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(uris);
                fileCallback = null;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.bg));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Keep the game clear of the status bar, navigation bar and camera cutout.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " InsideJokesAndroid");

        web.addJavascriptInterface(new Bridge(), "IJNative");

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = url != null && url.endsWith("/index.html");
                deliverShare();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                // Partiful and other outside links open in the phone's browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    photoPicker.launch(Intent.createChooser(pick, "Choose photos"));
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Ask the game to step back a screen; only leave the app from the home screen.
                web.evaluateJavascript("(window.ijBack && window.ijBack()) ? 'yes' : 'no'", handled -> {
                    if ("\"yes\"".equals(handled)) return;
                    if (web.canGoBack()) {
                        web.goBack();
                    } else {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });
            }
        });

        readShare(getIntent());

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START);
        }
    }

    /** Small helpers the game can call. */
    private class Bridge {
        @JavascriptInterface
        public void openPartiful() {
            Intent open = getPackageManager().getLaunchIntentForPackage("com.partiful.partiful");
            if (open == null) open = new Intent(Intent.ACTION_VIEW, Uri.parse("https://partiful.com/"));
            awaitingClipboard = true;
            final Intent go = open;
            runOnUiThread(() -> {
                try {
                    startActivity(go);
                } catch (Exception ignored) {
                }
            });
        }

        @JavascriptInterface
        public void fetchEvent(String url) {
            new Thread(() -> {
                JSONObject out = new JSONObject();
                try {
                    out.put("url", url);
                    Uri u = Uri.parse(url);
                    String host = u.getHost() == null ? "" : u.getHost();
                    if (!"https".equals(u.getScheme()) || !(host.equals("partiful.com") || host.endsWith(".partiful.com"))) {
                        throw new IllegalArgumentException("not a Partiful link");
                    }
                    String html = new String(download(url, 2_000_000, null), StandardCharsets.UTF_8);
                    String title = meta(html, "og:title");
                    String image = meta(html, "og:image");
                    if (title != null) out.put("title", title);
                    if (image != null) {
                        String[] type = new String[1];
                        byte[] bytes = download(image, 4_000_000, type);
                        out.put("image", "data:" + (type[0] == null ? "image/jpeg" : type[0]) + ";base64,"
                                + Base64.encodeToString(bytes, Base64.NO_WRAP));
                    }
                } catch (Exception e) {
                    try {
                        out.put("error", String.valueOf(e.getMessage()));
                    } catch (Exception ignored) {
                    }
                }
                final String js = "window.ijEventMeta && window.ijEventMeta(" + out + ")";
                runOnUiThread(() -> web.evaluateJavascript(js, null));
            }).start();
        }
    }

    private static String meta(String html, String property) {
        Pattern[] patterns = {
                Pattern.compile("<meta[^>]+property=[\"']" + Pattern.quote(property) + "[\"'][^>]*content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                Pattern.compile("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*property=[\"']" + Pattern.quote(property) + "[\"']", Pattern.CASE_INSENSITIVE)
        };
        for (Pattern p : patterns) {
            Matcher m = p.matcher(html);
            if (m.find()) {
                return m.group(1).replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'").replace("&quot;", "\"").trim();
            }
        }
        return null;
    }

    private static byte[] download(String address, int maxBytes, String[] contentType) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Language", "en-US,en");
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            if (contentType != null) contentType[0] = c.getContentType() == null ? null : c.getContentType().split(";")[0];
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > maxBytes) throw new IllegalStateException("too large");
            }
            return buf.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Back from Partiful after tapping Copy link: hand the copied event link to the game. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || !awaitingClipboard || !pageReady) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text == null) return;
            String s = text.toString();
            if (!s.contains("partiful.com") || s.equals(lastClip)) return;
            lastClip = s;
            awaitingClipboard = false;
            web.evaluateJavascript("window.ijClipboard && window.ijClipboard(" + JSONObject.quote(s) + ")", null);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readShare(intent);
        deliverShare();
    }

    private void readShare(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) pendingShare = text;
        }
    }

    private void deliverShare() {
        if (pendingShare == null || !pageReady || web == null) return;
        String js = "window.ijShared && window.ijShared(" + org.json.JSONObject.quote(pendingShare) + ")";
        pendingShare = null;
        web.evaluateJavascript(js, null);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }
}
