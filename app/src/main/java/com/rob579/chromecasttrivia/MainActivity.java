package com.rob579.chromecasttrivia;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);

        // YouTube's embedded player refuses to start (error 153) unless the
        // page embedding it has a real https origin, so the bundled page is
        // loaded as if it came from https://<app id>.
        String baseUrl = "https://" + getPackageName();
        webView.loadDataWithBaseURL(baseUrl, readAsset("player.html"),
                "text/html", "utf-8", null);
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
                webView.evaluateJavascript("window.remote && remote." + command, null);
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

    @Override
    protected void onPause() {
        super.onPause();
        webView.evaluateJavascript("window.remote && remote.pause()", null);
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    private String readAsset(String name) {
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
