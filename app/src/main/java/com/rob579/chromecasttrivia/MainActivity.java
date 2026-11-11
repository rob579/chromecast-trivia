package com.rob579.chromecasttrivia;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class MainActivity extends Activity {

    static final int PORT = 8080;
    private static final String PREFS = "trivia";
    private static final long POSITION_SAVE_MS = 5000;

    private WebView webView;
    private ControlServer server;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pageReady = false;
    private String serverError = null;

    private final Runnable positionSaver = new Runnable() {
        @Override
        public void run() {
            savePosition();
            handler.postDelayed(this, POSITION_SAVE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        // The remote is handled here, not by the page, so the YouTube
        // player can never swallow a button press.
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushState();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);

        // YouTube's embedded player refuses to start (error 153) unless the
        // page embedding it has a real https origin, so the bundled page is
        // loaded as if it came from https://<app id>.
        String baseUrl = "https://" + getPackageName();
        webView.loadDataWithBaseURL(baseUrl, readAsset("player.html"),
                "text/html", "utf-8", null);

        server = new ControlServer(this, PORT);
        try {
            server.start();
        } catch (IOException e) {
            serverError = "Could not start the control page: " + e.getMessage();
        }
    }

    // ---- Quiz state: one JSON document, kept across restarts ----

    synchronized String getState() {
        return prefs.getString("state", "{\"videoId\":\"\",\"listUrl\":\"\",\"current\":0,\"rounds\":[]}");
    }

    /** Called from the web server thread when the phone saves a change. */
    synchronized void setState(String json) throws Exception {
        JSONObject in = new JSONObject(json);
        JSONObject out = new JSONObject();
        out.put("videoId", in.optString("videoId", "").trim());
        out.put("listUrl", in.optString("listUrl", "").trim());
        JSONArray rounds = in.optJSONArray("rounds");
        JSONArray cleanRounds = new JSONArray();
        if (rounds != null) {
            for (int i = 0; i < rounds.length(); i++) {
                JSONObject r = rounds.optJSONObject(i);
                if (r == null) continue;
                JSONObject cr = new JSONObject();
                cr.put("name", r.optString("name", ""));
                JSONArray answers = r.optJSONArray("answers");
                JSONArray cleanAnswers = new JSONArray();
                if (answers != null) {
                    for (int j = 0; j < answers.length(); j++) {
                        cleanAnswers.put(answers.optString(j, ""));
                    }
                }
                cr.put("answers", cleanAnswers);
                // One score per answer; an empty string means "not marked yet".
                JSONArray scores = r.optJSONArray("scores");
                JSONArray cleanScores = new JSONArray();
                for (int j = 0; j < cleanAnswers.length(); j++) {
                    cleanScores.put(scores == null ? "" : scores.optString(j, ""));
                }
                cr.put("scores", cleanScores);
                double points = r.optDouble("points", Double.NaN);
                if (!Double.isNaN(points) && !Double.isInfinite(points) && points >= 0) {
                    cr.put("points", points);
                }
                cleanRounds.put(cr);
            }
        }
        out.put("rounds", cleanRounds);
        int current = in.optInt("current", 0);
        out.put("current", Math.max(0, Math.min(current, Math.max(0, cleanRounds.length() - 1))));
        prefs.edit().putString("state", out.toString()).apply();
        handler.post(this::pushState);
    }

    /** Sends the quiz, the control page address and its QR code to the TV page. */
    private void pushState() {
        if (!pageReady) return;
        try {
            JSONObject env = new JSONObject();
            env.put("state", new JSONObject(getState()));
            String ip = localIp();
            if (serverError != null) {
                env.put("notice", serverError);
            } else if (ip == null) {
                env.put("notice", "No Wi-Fi address yet");
            } else {
                String url = "http://" + ip + ":" + PORT;
                env.put("url", url);
                env.put("qr", qrDataUri(url));
            }
            JSONObject resume = new JSONObject();
            resume.put("videoId", prefs.getString("resumeVideo", ""));
            resume.put("t", prefs.getFloat("resumeTime", 0f));
            env.put("resume", resume);
            webView.evaluateJavascript("window.applyState && applyState(" + env + ")", null);
        } catch (Exception e) {
            // Nothing useful to do on the TV; the next change will retry.
        }
    }

    /** Remembers where the video is up to, so reopening the app resumes there. */
    private void savePosition() {
        if (!pageReady) return;
        webView.evaluateJavascript("window.remote ? remote.position() : null", value -> {
            try {
                if (value == null || value.equals("null")) return;
                JSONObject pos = new JSONObject(value);
                prefs.edit()
                        .putString("resumeVideo", pos.getString("v"))
                        .putFloat("resumeTime", (float) pos.getDouble("t"))
                        .apply();
            } catch (Exception ignored) {
            }
        });
    }

    // ---- Playback commands, from the remote or from the phone ----

    /** Called from the web server thread. Only known commands are accepted. */
    void runCommand(String name, int seconds) {
        final String js;
        switch (name) {
            case "toggle": js = "toggle()"; break;
            case "play":   js = "play()"; break;
            case "pause":  js = "pause()"; break;
            case "seek":   js = "seek(" + seconds + ")"; break;
            case "fullscreen": js = "fullscreen()"; break;
            default: return;
        }
        handler.post(() -> remote(js));
    }

    private void remote(String call) {
        webView.evaluateJavascript("window.remote && remote." + call, null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        String command = commandFor(event.getKeyCode());
        if (command == null) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean isToggle = command.equals("toggle()");
            // Holding a direction keeps skipping; holding OK must not flicker.
            if (!isToggle || event.getRepeatCount() == 0) {
                remote(command);
            }
        }
        return true;
    }

    private static String commandFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                return "toggle()";
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return "play()";
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return "pause()";
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return "seek(-10)";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return "seek(10)";
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return "seek(-60)";
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return "seek(60)";
            default:
                return null;
        }
    }

    // ---- Lifecycle ----

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        pushState(); // the Wi-Fi address may have changed while away
        handler.postDelayed(positionSaver, POSITION_SAVE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(positionSaver);
        savePosition();
        remote("pause()");
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        webView.destroy();
        super.onDestroy();
    }

    // ---- Helpers ----

    private static String localIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String qrDataUri(String text) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        int scale = 8;
        int w = matrix.getWidth(), h = matrix.getHeight();
        int[] pixels = new int[w * scale * h * scale];
        for (int y = 0; y < h * scale; y++) {
            for (int x = 0; x < w * scale; x++) {
                pixels[y * w * scale + x] = matrix.get(x / scale, y / scale) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, w * scale, h * scale, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Missing asset " + name, e);
        }
    }
}
