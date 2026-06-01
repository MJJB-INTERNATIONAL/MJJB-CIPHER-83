package com.mjjbcipher;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.os.Environment;

import java.io.*;
import java.nio.file.Files;

/**
 * MainActivity — hosts the WebView showing MJJB_CIPHER_2.html (offline, from assets).
 * JavaScript calls go through MjjbBridge which calls MjjbEngine (NDK).
 */
public class MainActivity extends AppCompatActivity {

    private static final int PICK_FILE_REQUEST = 1;
    private Uri mPendingUri = null;

    private WebView   mWebView;
    private MjjbBridge mBridge;

    // Paths
    private File mTempDir;
    private File mOutputDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Dirs
        mTempDir   = new File(getCacheDir(),     "temp");
        mOutputDir = new File(getFilesDir(),     "output");
        mTempDir.mkdirs();
        mOutputDir.mkdirs();

        // WebView — NO network access (no cleartext, no internet permission)
        mWebView = new WebView(this);
        setContentView(mWebView);

        WebSettings ws = mWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        // Explicitly block network
        ws.setBlockNetworkImage(false); // local SVG logos are ok
        ws.setBlockNetworkLoads(true);  // block all remote network loads

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                super.onPageFinished(view, url);
                if (mPendingUri != null) {
                    deliverFileToWebView(mPendingUri);
                    mPendingUri = null;
                }
            }
        });
        mWebView.setWebChromeClient(new WebChromeClient());

        mBridge = new MjjbBridge();
        mWebView.addJavascriptInterface(mBridge, "Android");

        // Load the bundled HTML from assets
        mWebView.loadUrl("file:///android_asset/mjjb_ui.html");

        // Handle incoming shared file
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) mPendingUri = uri;
        }
    }

    // ─── File picker ───
    public void openFilePicker() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Select File"), PICK_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            deliverFileToWebView(data.getData());
        }
    }

    private void deliverFileToWebView(Uri uri) {
        try {
            String name = queryFileName(uri);
            long   size = queryFileSize(uri);

            final String safeName = (name != null && !name.isEmpty()) ? name : ("picked_input_" + System.currentTimeMillis());

            // Copy to temp dir so NDK can access via native path
            File dest = new File(mTempDir, safeName);
            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream os = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }

            final String absPath = dest.getAbsolutePath();

            // Notify JS
            runOnUiThread(() -> mWebView.evaluateJavascript(
                "window.androidDeliverFile('" +
                escapeJs(absPath) + "','" +
                escapeJs(safeName) + "'," +
                size + ")", null));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String queryFileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        String path = uri.getPath();
        return (path != null) ? path.substring(path.lastIndexOf('/') + 1) : "file";
    }

    private long queryFileSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) return c.getLong(idx);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // Escape string for safe JS injection
    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
    }

    // ─── Save output file to Downloads/MJJB-CIPHER/ via MediaStore (API 29+) ───
    private void shareOutputFile(String absPath) {
        File src = new File(absPath);
        if (!src.exists()) return;

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
        cv.put(MediaStore.Downloads.RELATIVE_PATH,
               Environment.DIRECTORY_DOWNLOADS + "/MJJB-CIPHER");

        Uri collection = MediaStore.Downloads.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = getContentResolver().insert(collection, cv);

        if (itemUri == null) {
            android.widget.Toast.makeText(this,
                "Save failed — could not create file",
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        try (java.io.InputStream in  = new java.io.FileInputStream(src);
             java.io.OutputStream out = getContentResolver().openOutputStream(itemUri)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            e.printStackTrace();
            android.widget.Toast.makeText(this,
                "Save failed: " + e.getMessage(),
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        android.widget.Toast.makeText(this,
            "Saved to Downloads/MJJB-CIPHER/" + src.getName(),
            android.widget.Toast.LENGTH_LONG).show();
    }

    // ═══════════════════════════════════════════
    // JS BRIDGE
    // ═══════════════════════════════════════════
    public class MjjbBridge {

        private final MjjbEngine engine = new MjjbEngine();
        private final Handler    main   = new Handler(Looper.getMainLooper());
        private String  mLastOutputPath = null;

        /** Called from JS — opens native file picker */
        @JavascriptInterface
        public void pickFile() {
            main.post(() -> openFilePicker());
        }

        /**
         * Called from JS to start encode/decode.
         * Returns output filename or "ERROR:..."
         */
        @JavascriptInterface
        public String startProcess(String inPath, String key1, String key2,
                                   String k1Base, String k2Base,
                                   int structure, int blockSize, String mode) {
            String tempPath   = mTempDir.getAbsolutePath()   + "/";
            String outputPath = mOutputDir.getAbsolutePath();
            String result = engine.startProcess(
                inPath, tempPath, outputPath,
                key1, key2, k1Base, k2Base,
                structure, blockSize, mode);
            if (!result.startsWith("ERROR")) {
                mLastOutputPath = outputPath + "/" + result;
            }
            return result;
        }

        /** Poll progress JSON — same format as PC's /progress response */
        @JavascriptInterface
        public String getProgress() {
            return engine.getProgress();
        }

        /** Check if file is MJJB encoded */
        @JavascriptInterface
        public boolean probeIsMjjb(String path) {
            return engine.probeIsMjjb(path);
        }

        /** Get expected output name */
        @JavascriptInterface
        public String makeOutputName(String fname, boolean doEncode) {
            return engine.makeOutputName(fname, doEncode);
        }

        /** Share/save the last output file via Android share sheet */
        @JavascriptInterface
        public void shareOutput() {
            if (mLastOutputPath != null) {
                main.post(() -> shareOutputFile(mLastOutputPath));
            }
        }

        
        /** Get output dir path for reading back file info */
        @JavascriptInterface
        public String getOutputPath() {
            return mOutputDir.getAbsolutePath();
        }
    }

  /**CLAUDE,CLAUDE,CLAUDE,CLAUDE,CLAUDE,CLAUDE,CG,CG,CG,DS*/
    @Override
    public void onBackPressed() {
        if (mWebView.canGoBack()) mWebView.goBack();
        else super.onBackPressed();
    }
}
