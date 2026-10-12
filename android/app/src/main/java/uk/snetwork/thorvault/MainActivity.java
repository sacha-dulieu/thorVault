package uk.snetwork.thorvault;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.documentfile.provider.DocumentFile;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends ComponentActivity {
  private static final long MAX_FILE = 16L * 1024L * 1024L;
  private static final int MAX_SCAN_DEPTH = 24;
  private static final int MAX_SCAN_VISITS = 25000;
  private static final int MAX_SCAN_RESULTS = 500;

  private static final class ScanStats {
    int visited = 0;
    int ignored = 0;
    int maxDepth = 0;
  }
  private WebView webView;
  private SharedPreferences prefs;
  private ActivityResultLauncher<Uri> folderPicker;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    prefs = getSharedPreferences(VaultSyncWorker.PREFS, Context.MODE_PRIVATE);
    folderPicker = registerForActivityResult(
        new ActivityResultContracts.OpenDocumentTree(),
        uri -> {
          if (uri == null) {
            prefs.edit()
                .putString("game_tree_status", "cancelled")
                .putString("game_tree_error", "Folder selection was cancelled.")
                .commit();
            notifyFolderChanged();
            return;
          }
          persistSelectedTree(uri);
        }
    );
    if (!prefs.contains("device_id")) {
      prefs.edit().putString("device_id", UUID.randomUUID().toString()).apply();
    }

    webView = new WebView(this);
    webView.setBackgroundColor(Color.rgb(7, 17, 31));
    setContentView(webView);

    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    settings.setUserAgentString(
        settings.getUserAgentString() + " ThorVaultAndroid/0.12.22"
    );
    String bundledVersion = prefs.getString("web_asset_version", "");
    if (!"0.12.22".equals(bundledVersion)) {
      webView.clearCache(true);
      webView.clearHistory();
      prefs.edit().putString("web_asset_version", "0.12.22").apply();
    }

    WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
        .build();
    webView.setWebChromeClient(new WebChromeClient());
    webView.setWebViewClient(new WebViewClientCompat() {
      @Override public WebResourceResponse shouldInterceptRequest(
          WebView view, WebResourceRequest request) {
        return loader.shouldInterceptRequest(request.getUrl());
      }

      @Override public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        view.evaluateJavascript(
            "window.__THORVAULT_ANDROID__=true;" +
            "window.isAndroidShell=true;" +
            "document.body.classList.add('android-shell','thorvault-native');" +
            "if(window.initializeThorVaultAndroid){window.initializeThorVaultAndroid();}",
            null
        );
      }
    });
    webView.addJavascriptInterface(new Bridge(), "ThorVaultNative");
    webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html?v=1222");
  }

  @Override protected void onPause() {
    if (webView != null) {
      webView.evaluateJavascript(
          "if(window.thorVaultAppPaused){window.thorVaultAppPaused();}",
          null
      );
    }
    super.onPause();
  }

  @Override protected void onResume() {
    super.onResume();
    if (webView != null) {
      webView.post(() -> webView.evaluateJavascript(
          "if(window.thorVaultAppResumed){window.thorVaultAppResumed();}",
          null
      ));
    }
  }

  private void persistSelectedTree(Uri uri) {
    int readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    int writeFlag = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    int persistedFlags = 0;
    String permissionMode = "temporary";
    String warning = "";

    try {
      getContentResolver().takePersistableUriPermission(uri, readFlag | writeFlag);
      persistedFlags = readFlag | writeFlag;
      permissionMode = "read-write";
    } catch (SecurityException readWriteError) {
      try {
        getContentResolver().takePersistableUriPermission(uri, readFlag);
        persistedFlags = readFlag;
        permissionMode = "read-only";
        warning = "The provider granted read-only persistent access.";
      } catch (SecurityException readError) {
        warning = "Android did not grant persistent access. The folder may stop working after restart.";
      }
    }

    DocumentFile root = DocumentFile.fromTreeUri(this, uri);
    boolean exists = root != null && root.exists();
    boolean readable = root != null && root.canRead();
    boolean writable = root != null && root.canWrite();

    boolean committed = prefs.edit()
        .putString("game_tree_uri", uri.toString())
        .putString("game_tree_status", readable ? "granted" : "unreadable")
        .putString("game_tree_permission_mode", permissionMode)
        .putInt("game_tree_persisted_flags", persistedFlags)
        .putString(
            "game_tree_error",
            readable
                ? warning
                : "The selected folder was returned, but Android reports that it is not readable."
        )
        .commit();

    if (!committed) {
      prefs.edit()
          .putString("game_tree_status", "storage-error")
          .putString("game_tree_error", "ThorVault could not save the selected folder URI.")
          .apply();
    }

    notifyFolderChanged();
  }

  private void notifyFolderChanged() {
    if (webView == null) return;
    webView.post(() -> webView.evaluateJavascript(
        "if(window.thorVaultNativeFolderChanged){" +
        "window.thorVaultNativeFolderChanged(" +
        JSONObject.quote(folderStatusJson()) +
        ");}",
        null
    ));
  }

  private boolean hasPersistedReadPermission(Uri treeUri) {
    for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
      if (treeUri.equals(permission.getUri()) && permission.isReadPermission()) return true;
    }
    return false;
  }

  private boolean hasPersistedWritePermission(Uri treeUri) {
    for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
      if (treeUri.equals(permission.getUri()) && permission.isWritePermission()) return true;
    }
    return false;
  }

  private String folderStatusJson() {
    try {
      JSONObject result = new JSONObject();
      String value = prefs.getString("game_tree_uri", "");
      result.put("ok", true);
      result.put("stored", !value.isEmpty());
      result.put("status", prefs.getString("game_tree_status", value.isEmpty() ? "none" : "stored"));
      result.put("error", prefs.getString("game_tree_error", ""));
      result.put("permissionMode", prefs.getString("game_tree_permission_mode", "none"));

      if (value.isEmpty()) {
        result.put("folderUri", JSONObject.NULL);
        result.put("persistedRead", false);
        result.put("persistedWrite", false);
        result.put("readable", false);
        result.put("writable", false);
        return result.toString();
      }

      Uri uri = Uri.parse(value);
      DocumentFile root = DocumentFile.fromTreeUri(this, uri);
      result.put("folderUri", value);
      result.put("folderName", root == null ? JSONObject.NULL : root.getName());
      result.put("exists", root != null && root.exists());
      result.put("readable", root != null && root.canRead());
      result.put("writable", root != null && root.canWrite());
      result.put("persistedRead", hasPersistedReadPermission(uri));
      result.put("persistedWrite", hasPersistedWritePermission(uri));
      return result.toString();
    } catch (Exception error) {
      return failure(error);
    }
  }

  private final class Bridge {
    @JavascriptInterface public boolean isAndroidApp() { return true; }

    @JavascriptInterface public void chooseGameFolder() {
      runOnUiThread(() -> {
        String current = prefs.getString("game_tree_uri", "");
        folderPicker.launch(current.isEmpty() ? null : Uri.parse(current));
      });
    }

    @JavascriptInterface public String getFolderStatus() {
      return folderStatusJson();
    }

    @JavascriptInterface public String forgetGameFolder() {
      try {
        String current = prefs.getString("game_tree_uri", "");
        if (!current.isEmpty()) {
          Uri uri = Uri.parse(current);
          int flags = 0;
          if (hasPersistedReadPermission(uri)) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
          if (hasPersistedWritePermission(uri)) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
          if (flags != 0) {
            try {
              getContentResolver().releasePersistableUriPermission(uri, flags);
            } catch (SecurityException ignored) {
              // The provider may already have removed the grant.
            }
          }
        }
        prefs.edit()
            .remove("game_tree_uri")
            .remove("game_tree_status")
            .remove("game_tree_error")
            .remove("game_tree_permission_mode")
            .remove("game_tree_persisted_flags")
            .commit();
        notifyFolderChanged();
        return new JSONObject().put("ok", true).toString();
      } catch (Exception error) {
        return failure(error);
      }
    }

    private String scanGamesJson() {
      try {
        JSONObject status = new JSONObject(folderStatusJson());
        JSONObject result = new JSONObject()
            .put("ok", true)
            .put("folderStatus", status)
            .put("folderUri", status.opt("folderUri"))
            .put("folderName", status.opt("folderName"))
            .put("persistedRead", status.optBoolean("persistedRead", false))
            .put("persistedWrite", status.optBoolean("persistedWrite", false))
            .put("readable", status.optBoolean("readable", false))
            .put("writable", status.optBoolean("writable", false))
            .put("permissionMode", status.optString("permissionMode", "none"))
            .put("error", status.optString("error", ""));

        String value = prefs.getString("game_tree_uri", "");
        if (value.isEmpty()) {
          return result
              .put("games", new JSONArray())
              .put("visitedCount", 0)
              .put("ignoredCount", 0)
              .put("maxDepthReached", 0)
              .toString();
        }

        Uri uri = Uri.parse(value);
        DocumentFile root = DocumentFile.fromTreeUri(MainActivity.this, uri);
        if (root == null || !root.exists() || !root.canRead()) {
          return result
              .put("error", "The selected folder is stored, but Android cannot currently read it.")
              .put("games", new JSONArray())
              .toString();
        }

        List<JSONObject> games = new ArrayList<>();
        ScanStats stats = new ScanStats();
        scan(root, "", 0, games, stats);
        return result
            .put("folderUri", value)
            .put("folderName", root.getName())
            .put("games", new JSONArray(games))
            .put("visitedCount", stats.visited)
            .put("ignoredCount", stats.ignored)
            .put("maxDepthReached", stats.maxDepth)
            .put("scanLimitReached", stats.visited >= MAX_SCAN_VISITS)
            .toString();
      } catch (Exception error) {
        return failure(error);
      }
    }

    @JavascriptInterface public String scanGames() {
      return scanGamesJson();
    }

    @JavascriptInterface public void scanGamesAsync(String requestId) {
      new Thread(() -> {
        String result = scanGamesJson();
        webView.post(() -> webView.evaluateJavascript(
            "if(window.thorVaultNativeScanResult){" +
            "window.thorVaultNativeScanResult(" +
            JSONObject.quote(requestId) + "," +
            JSONObject.quote(result) +
            ");}",
            null
        ));
      }, "ThorVault-Scan").start();
    }

    @JavascriptInterface public String readSave(String uriText) {
      try {
        Uri uri = Uri.parse(uriText);
        byte[] data = read(uri);
        return new JSONObject().put("ok", true).put("name", name(uri))
            .put("size", data.length).put("sha256", sha(data))
            .put("base64", Base64.encodeToString(data, Base64.NO_WRAP)).toString();
      } catch (Exception error) { return failure(error); }
    }

    private String inspectSaveFileJson(String uriText) {
      try {
        Uri uri = Uri.parse(uriText);
        byte[] data = read(uri);
        DocumentFile file = DocumentFile.fromSingleUri(MainActivity.this, uri);
        return new JSONObject()
            .put("ok", true)
            .put("uri", uriText)
            .put("name", name(uri))
            .put("size", data.length)
            .put("sha256", sha(data))
            .put("lastModified", file == null ? 0 : file.lastModified())
            .toString();
      } catch (Exception error) {
        return failure(error);
      }
    }

    @JavascriptInterface public String inspectSaveFile(String uriText) {
      return inspectSaveFileJson(uriText);
    }

    @JavascriptInterface public void inspectSaveFileAsync(String requestId, String uriText) {
      new Thread(() -> {
        String result = inspectSaveFileJson(uriText);
        webView.post(() -> webView.evaluateJavascript(
            "if(window.thorVaultNativeSaveFingerprintResult){" +
            "window.thorVaultNativeSaveFingerprintResult(" +
            JSONObject.quote(requestId) + "," +
            JSONObject.quote(result) +
            ");}",
            null
        ));
      }, "ThorVault-SaveFingerprint").start();
    }

    @JavascriptInterface public String applySave(String uriText, String base64) {
      try {
        Uri target = Uri.parse(uriText);
        byte[] original = read(target);
        byte[] replacement = Base64.decode(base64, Base64.DEFAULT);

        if (replacement.length == 0)
          throw new IllegalStateException("Generated save is empty.");

        String originalSha = sha(original);
        String expectedSha = sha(replacement);
        if (originalSha.equals(expectedSha))
          throw new IllegalStateException(
              "The generated save is identical to the current device save. Nothing was written."
          );

        String tree = prefs.getString("game_tree_uri", "");
        if (tree.isEmpty())
          throw new IllegalStateException("No writable Android game folder is granted.");

        DocumentFile root = DocumentFile.fromTreeUri(
            MainActivity.this, Uri.parse(tree)
        );
        if (root == null || !root.canWrite())
          throw new IllegalStateException("Granted folder is not writable.");

        DocumentFile backups = root.findFile("ThorVault Backups");
        if (backups == null)
          backups = root.createDirectory("ThorVault Backups");
        if (backups == null)
          throw new IllegalStateException("Could not create backup folder.");

        String stamp = new SimpleDateFormat(
            "yyyyMMdd-HHmmss", Locale.US
        ).format(new Date());
        String safeTargetName = name(target)
            .replaceAll("[^A-Za-z0-9._-]+", "-");
        String backupName =
            stamp + "-" + safeTargetName + ".bak";

        DocumentFile backup = backups.createFile(
            "application/octet-stream", backupName
        );
        if (backup == null)
          throw new IllegalStateException("Could not create backup.");

        write(backup.getUri(), original);

        byte[] backupCheck = read(backup.getUri());
        String backupSha = sha(backupCheck);
        if (!originalSha.equals(backupSha)) {
          try { backup.delete(); } catch (Exception ignored) {}
          throw new IllegalStateException(
              "Backup verification failed. The active save was not modified."
          );
        }

        write(target, replacement);
        byte[] written = read(target);
        String actualSha = sha(written);

        if (!expectedSha.equals(actualSha) ||
            written.length != replacement.length) {
          write(target, original);
          byte[] rollback = read(target);
          String rollbackSha = sha(rollback);
          if (!originalSha.equals(rollbackSha))
            throw new IllegalStateException(
                "Write verification failed and automatic rollback could not be verified. " +
                "Use the backup file: " + backupName
            );
          throw new IllegalStateException(
              "Write verification failed; the original save was restored and verified."
          );
        }

        long appliedAt = System.currentTimeMillis();
        prefs.edit()
            .putString("last_apply_target_uri", uriText)
            .putString("last_apply_target_name", name(target))
            .putString("last_apply_backup_uri", backup.getUri().toString())
            .putString("last_apply_backup_name", backupName)
            .putString("last_apply_original_sha256", originalSha)
            .putString("last_apply_written_sha256", actualSha)
            .putLong("last_apply_byte_length", replacement.length)
            .putLong("last_apply_timestamp", appliedAt)
            .commit();

        return new JSONObject()
            .put("ok", true)
            .put("targetName", name(target))
            .put("backupName", backupName)
            .put("backupUri", backup.getUri().toString())
            .put("originalSha256", originalSha)
            .put("backupSha256", backupSha)
            .put("writtenSha256", actualSha)
            .put("byteLength", replacement.length)
            .put("appliedAt", appliedAt)
            .put("verified", true)
            .toString();
      } catch (Exception error) {
        return failure(error);
      }
    }

    @JavascriptInterface public String getLastApplyInfo() {
      try {
        String targetUri = prefs.getString("last_apply_target_uri", "");
        String backupUri = prefs.getString("last_apply_backup_uri", "");
        if (targetUri.isEmpty() || backupUri.isEmpty())
          return new JSONObject()
              .put("ok", true)
              .put("present", false)
              .toString();

        boolean backupReadable = false;
        try {
          backupReadable = read(Uri.parse(backupUri)).length > 0;
        } catch (Exception ignored) {}

        return new JSONObject()
            .put("ok", true)
            .put("present", true)
            .put("targetUri", targetUri)
            .put("targetName", prefs.getString("last_apply_target_name", ""))
            .put("backupUri", backupUri)
            .put("backupName", prefs.getString("last_apply_backup_name", ""))
            .put("originalSha256",
                prefs.getString("last_apply_original_sha256", ""))
            .put("writtenSha256",
                prefs.getString("last_apply_written_sha256", ""))
            .put("byteLength",
                prefs.getLong("last_apply_byte_length", 0))
            .put("appliedAt",
                prefs.getLong("last_apply_timestamp", 0))
            .put("backupReadable", backupReadable)
            .toString();
      } catch (Exception error) {
        return failure(error);
      }
    }

    @JavascriptInterface public String restoreLastAppliedBackup() {
      try {
        String targetText = prefs.getString("last_apply_target_uri", "");
        String backupText = prefs.getString("last_apply_backup_uri", "");
        String backupName = prefs.getString("last_apply_backup_name", "");
        String expectedOriginalSha =
            prefs.getString("last_apply_original_sha256", "");

        if (targetText.isEmpty() || backupText.isEmpty())
          throw new IllegalStateException("No applied-save backup is recorded.");

        Uri target = Uri.parse(targetText);
        Uri backupUri = Uri.parse(backupText);
        byte[] backupData = read(backupUri);
        String backupSha = sha(backupData);

        if (backupData.length == 0)
          throw new IllegalStateException("The recorded backup is empty.");
        if (!expectedOriginalSha.isEmpty() &&
            !expectedOriginalSha.equals(backupSha))
          throw new IllegalStateException(
              "The recorded backup failed SHA-256 verification. Restore was cancelled."
          );

        // Preserve the current save before restoration, so Restore itself
        // never destroys the state being replaced.
        byte[] current = read(target);
        String currentSha = sha(current);

        String tree = prefs.getString("game_tree_uri", "");
        DocumentFile root = DocumentFile.fromTreeUri(
            MainActivity.this, Uri.parse(tree)
        );
        if (root == null || !root.canWrite())
          throw new IllegalStateException("Granted folder is not writable.");
        DocumentFile backups = root.findFile("ThorVault Backups");
        if (backups == null)
          backups = root.createDirectory("ThorVault Backups");
        if (backups == null)
          throw new IllegalStateException("Could not access backup folder.");

        String stamp = new SimpleDateFormat(
            "yyyyMMdd-HHmmss", Locale.US
        ).format(new Date());
        String safeTargetName = name(target)
            .replaceAll("[^A-Za-z0-9._-]+", "-");
        String beforeRestoreName =
            stamp + "-" + safeTargetName + ".before-restore.bak";
        DocumentFile beforeRestore = backups.createFile(
            "application/octet-stream", beforeRestoreName
        );
        if (beforeRestore == null)
          throw new IllegalStateException(
              "Could not create the pre-restore safety backup."
          );

        write(beforeRestore.getUri(), current);
        String preRestoreSha = sha(read(beforeRestore.getUri()));
        if (!currentSha.equals(preRestoreSha)) {
          try { beforeRestore.delete(); } catch (Exception ignored) {}
          throw new IllegalStateException(
              "Pre-restore safety backup verification failed. Restore was cancelled."
          );
        }

        write(target, backupData);
        byte[] restored = read(target);
        String restoredSha = sha(restored);
        if (!backupSha.equals(restoredSha) ||
            restored.length != backupData.length) {
          write(target, current);
          String rollbackSha = sha(read(target));
          if (!currentSha.equals(rollbackSha))
            throw new IllegalStateException(
                "Restore verification failed and rollback could not be verified. " +
                "Use " + beforeRestoreName + " manually."
            );
          throw new IllegalStateException(
              "Restore verification failed; the pre-restore save was put back."
          );
        }

        long restoredAt = System.currentTimeMillis();
        prefs.edit()
            .putLong("last_restore_timestamp", restoredAt)
            .putString("last_restore_sha256", restoredSha)
            .putString("last_restore_safety_backup_name", beforeRestoreName)
            .commit();

        return new JSONObject()
            .put("ok", true)
            .put("targetName", name(target))
            .put("restoredBackupName", backupName)
            .put("restoredSha256", restoredSha)
            .put("preRestoreBackupName", beforeRestoreName)
            .put("preRestoreSha256", preRestoreSha)
            .put("byteLength", restored.length)
            .put("restoredAt", restoredAt)
            .put("verified", true)
            .toString();
      } catch (Exception error) {
        return failure(error);
      }
    }

    private String performHttpRequest(
        String method,
        String urlText,
        String headersJson,
        String bodyBase64,
        int timeoutMs
    ) {
      HttpURLConnection connection = null;
      try {
        URL url = new URL(urlText);
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) &&
            !"https".equalsIgnoreCase(protocol))
          throw new IllegalArgumentException(
              "Only HTTP(S) server URLs are supported."
          );

        connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(
            method == null ? "GET" : method.toUpperCase(Locale.ROOT)
        );
        int timeout = Math.max(
            1500,
            Math.min(timeoutMs <= 0 ? 60000 : timeoutMs, 120000)
        );
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);

        JSONObject headers = new JSONObject(
            headersJson == null || headersJson.isEmpty() ? "{}" : headersJson
        );
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
          String key = keys.next();
          connection.setRequestProperty(key, headers.optString(key, ""));
        }
        connection.setRequestProperty(
            "X-ThorVault-Client-Version", "0.12.22"
        );
        connection.setRequestProperty("X-ThorVault-Api-Version", "1");
        connection.setRequestProperty(
            "X-ThorVault-Device-Id",
            prefs.getString("device_id", "")
        );
        connection.setRequestProperty(
            "X-ThorVault-Device-Name",
            prefs.getString("device_name", "AYN Thor")
        );
        connection.setRequestProperty("X-ThorVault-Platform", "android");

        byte[] requestBody = bodyBase64 == null || bodyBase64.isEmpty()
            ? new byte[0]
            : Base64.decode(bodyBase64, Base64.DEFAULT);
        if (requestBody.length > 0) {
          connection.setDoOutput(true);
          connection.setFixedLengthStreamingMode(requestBody.length);
          try (OutputStream output = connection.getOutputStream()) {
            output.write(requestBody);
            output.flush();
          }
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 400
            ? connection.getErrorStream()
            : connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (stream != null) {
          try (InputStream input = stream) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
              if (output.size() + count > 50 * 1024 * 1024)
                throw new IllegalStateException(
                    "Server response exceeds the 50 MB limit."
                );
              output.write(buffer, 0, count);
            }
          }
        }

        JSONObject responseHeaders = new JSONObject();
        for (Map.Entry<String, List<String>> entry :
            connection.getHeaderFields().entrySet()) {
          if (entry.getKey() == null ||
              entry.getValue() == null ||
              entry.getValue().isEmpty()) continue;
          responseHeaders.put(entry.getKey(), entry.getValue().get(0));
        }

        return new JSONObject()
            .put("ok", true)
            .put("status", status)
            .put("url", urlText)
            .put("headers", responseHeaders)
            .put(
                "bodyBase64",
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            )
            .toString();
      } catch (Exception error) {
        return failure(error);
      } finally {
        if (connection != null) connection.disconnect();
      }
    }

    @JavascriptInterface public String httpRequest(
        String method,
        String urlText,
        String headersJson,
        String bodyBase64,
        int timeoutMs
    ) {
      return performHttpRequest(
          method, urlText, headersJson, bodyBase64, timeoutMs
      );
    }

    @JavascriptInterface public void httpRequestAsync(
        String requestId,
        String method,
        String urlText,
        String headersJson,
        String bodyBase64,
        int timeoutMs
    ) {
      new Thread(() -> {
        String result = performHttpRequest(
            method, urlText, headersJson, bodyBase64, timeoutMs
        );
        webView.post(() -> webView.evaluateJavascript(
            "if(window.thorVaultNativeHttpResult){" +
            "window.thorVaultNativeHttpResult(" +
            JSONObject.quote(requestId) + "," +
            JSONObject.quote(result) +
            ");}",
            null
        ));
      }, "ThorVault-HTTP").start();
    }

    @JavascriptInterface public void storeVault(String json, boolean dirty) {
      prefs.edit()
          .putString("vault_json", json == null ? "" : json)
          .putBoolean("vault_dirty", dirty)
          .apply();
    }
    @JavascriptInterface public String getStoredVault() { return prefs.getString("vault_json", ""); }

    @JavascriptInterface public void storeRecoveryVault(String json, String reason) {
      prefs.edit()
          .putString("recovery_vault_json", json == null ? "" : json)
          .putString("recovery_vault_reason", reason == null ? "" : reason)
          .putLong("recovery_vault_timestamp", System.currentTimeMillis())
          .commit();
    }

    @JavascriptInterface public String getRecoveryVaultInfo() {
      try {
        String json = prefs.getString("recovery_vault_json", "");
        JSONObject result = new JSONObject()
            .put("ok", true)
            .put("present", !json.isEmpty())
            .put("reason", prefs.getString("recovery_vault_reason", ""))
            .put("timestamp", prefs.getLong("recovery_vault_timestamp", 0));
        if (!json.isEmpty()) {
          JSONObject vault = new JSONObject(json);
          JSONArray entries = vault.optJSONArray("entries");
          result.put("entries", entries == null ? 0 : entries.length());
        }
        return result.toString();
      } catch (Exception error) {
        return failure(error);
      }
    }

    @JavascriptInterface public void setLastServerSha256(String sha256, String serverId) {
      prefs.edit()
          .putString("last_server_sha256", sha256 == null ? "" : sha256)
          .putString("last_server_id", serverId == null ? "" : serverId)
          .apply();
    }
    @JavascriptInterface public void scheduleSync() { SyncScheduler.enqueueNow(MainActivity.this); }
    @JavascriptInterface public void saveServerConfig(String url, String token, String name) {
      prefs.edit().putString("server_url", url).putString("server_token", token)
          .putString("device_name", name).apply();
      SyncScheduler.enqueueNow(MainActivity.this);
    }
    @JavascriptInterface public String getServerConfig() {
      try {
        return new JSONObject().put("serverUrl", prefs.getString("server_url", ""))
            .put("token", prefs.getString("server_token", ""))
            .put("deviceName", prefs.getString("device_name", "AYN Thor")).toString();
      } catch (Exception error) { return "{}"; }
    }
  }

  private void scan(
      DocumentFile dir,
      String path,
      int depth,
      List<JSONObject> out,
      ScanStats stats
  ) throws Exception {
    if (depth > MAX_SCAN_DEPTH ||
        stats.visited >= MAX_SCAN_VISITS ||
        out.size() >= MAX_SCAN_RESULTS) return;

    stats.maxDepth = Math.max(stats.maxDepth, depth);
    DocumentFile[] children = dir.listFiles();
    List<DocumentFile> directories = new ArrayList<>();
    List<DocumentFile> files = new ArrayList<>();

    for (DocumentFile child : children) {
      if (child.isDirectory()) directories.add(child);
      else files.add(child);
    }

    Collections.sort(directories, new Comparator<DocumentFile>() {
      @Override public int compare(
          DocumentFile left,
          DocumentFile right
      ) {
        String leftName = left.getName() == null ? "" : left.getName();
        String rightName = right.getName() == null ? "" : right.getName();
        return Integer.compare(
            directoryPriority(leftName, path),
            directoryPriority(rightName, path)
        );
      }
    });

    for (DocumentFile file : files) {
      if (stats.visited >= MAX_SCAN_VISITS ||
          out.size() >= MAX_SCAN_RESULTS) return;
      stats.visited++;
      String name = file.getName() == null ? "unnamed" : file.getName();
      String relative = path.isEmpty() ? name : path + "/" + name;
      if (looksLikeSave(name, file.length(), relative)) {
        out.add(new JSONObject()
            .put("uri", file.getUri().toString())
            .put("name", name)
            .put("relativePath", relative)
            .put("size", file.length())
            .put("lastModified", file.lastModified())
            .put("kind", classify(name, relative))
            .put("label", saveLabel(name, relative)));
      } else {
        stats.ignored++;
      }
    }

    for (DocumentFile directory : directories) {
      if (stats.visited >= MAX_SCAN_VISITS ||
          out.size() >= MAX_SCAN_RESULTS) return;
      stats.visited++;
      String name = directory.getName() == null
          ? "unnamed"
          : directory.getName();
      if (name.equalsIgnoreCase("ThorVault Backups")) continue;
      String relative = path.isEmpty() ? name : path + "/" + name;
      scan(directory, relative, depth + 1, out, stats);
    }
  }

  private static int directoryPriority(
      String name,
      String parentPath
  ) {
    String value = (parentPath + "/" + name).toLowerCase(Locale.ROOT);
    if (value.contains("nintendo 3ds") ||
        value.contains("/title") ||
        value.contains("/data") ||
        value.contains("/sdmc") ||
        value.contains("/users")) return 0;
    if (value.contains("azahar") ||
        value.contains("citra") ||
        value.contains("melonds") ||
        value.contains("/saves") ||
        value.contains("/save")) return 1;
    if (value.contains("/3ds") ||
        value.contains("/nds") ||
        value.contains("/gba")) return 2;
    return 5;
  }

  private static boolean looksLikeSave(
      String name,
      long size,
      String relativePath
  ) {
    if (size < 16 * 1024 || size > MAX_FILE) return false;
    String n = name.toLowerCase(Locale.ROOT);
    String p = relativePath.toLowerCase(Locale.ROOT);

    if (n.equals("main")) return true;
    if (n.endsWith(".sav") ||
        n.endsWith(".dsv") ||
        n.endsWith(".srm")) return true;

    boolean saveNamed =
        n.equals("save.dat") ||
        n.equals("savedata.dat") ||
        n.equals("main.dat") ||
        n.equals("save.bin") ||
        n.equals("savedata.bin") ||
        n.equals("main.bin");

    boolean savePath =
        (p.contains("/save/") || p.contains("/saves/")) &&
        (n.contains("save") || n.startsWith("main."));

    return saveNamed || savePath;
  }

  private static String classify(String name, String path) {
    String n = name.toLowerCase(Locale.ROOT);
    String p = path.toLowerCase(Locale.ROOT);
    if (n.equals("main") &&
        (p.contains("azahar") ||
         p.contains("citra") ||
         p.contains("nintendo 3ds") ||
         p.contains("/title/00040000/"))) {
      return "Nintendo 3DS active main";
    }
    if (n.endsWith(".dsv"))
      return "DeSmuME Nintendo DS save";
    if (n.endsWith(".srm"))
      return "RetroArch battery save";
    if (n.endsWith(".sav"))
      return "GBA / Nintendo DS save";
    return "Potential emulator save";
  }

  private static String saveLabel(String name, String path) {
    String lowerPath = path.toLowerCase(Locale.ROOT);
    if (name.equalsIgnoreCase("main") &&
        lowerPath.contains("/title/00040000/")) {
      return "Nintendo 3DS main";
    }
    return name;
  }

  private byte[] read(Uri uri) throws Exception {
    try (InputStream in = getContentResolver().openInputStream(uri);
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (in == null) throw new IllegalStateException("Could not open file.");
      byte[] buffer = new byte[8192];
      int count, total = 0;
      while ((count = in.read(buffer)) >= 0) {
        total += count;
        if (total > MAX_FILE) throw new IllegalArgumentException("File is too large.");
        out.write(buffer, 0, count);
      }
      return out.toByteArray();
    }
  }

  private void write(Uri uri, byte[] data) throws Exception {
    try (OutputStream out = getContentResolver().openOutputStream(uri, "rwt")) {
      if (out == null) throw new IllegalStateException("Could not open file for writing.");
      out.write(data);
      out.flush();
    }
  }

  private String name(Uri uri) {
    try (android.database.Cursor c = getContentResolver().query(
        uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (i >= 0) return c.getString(i);
      }
    }
    return uri.getLastPathSegment() == null ? "pokemon-save" : uri.getLastPathSegment();
  }

  private static String sha(byte[] data) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
    StringBuilder text = new StringBuilder();
    for (byte b : hash) text.append(String.format(Locale.US, "%02x", b));
    return text.toString();
  }

  private static String failure(Exception error) {
    try { return new JSONObject().put("ok", false).put("error", error.getMessage()).toString(); }
    catch (Exception ignored) { return "{\"ok\":false,\"error\":\"Android operation failed.\"}"; }
  }
}
