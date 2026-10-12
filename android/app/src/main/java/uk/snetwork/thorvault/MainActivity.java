package uk.snetwork.thorvault;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.view.KeyEvent;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends ComponentActivity {
  private static final long MAX_FILE = 16L * 1024L * 1024L;
  private static final long MAX_SCAN_FINGERPRINT_BYTES = 256L * 1024L * 1024L;
  private static final long MAX_SCAN_HASH_FILE_BYTES = 4L * 1024L * 1024L;
  private static final int MAX_SCAN_DEPTH = 24;
  private static final int MAX_SCAN_VISITS = 50000;
  private static final int MAX_SCAN_RESULTS = 1000;

  private static final class ScanStats {
    int visited = 0;
    int ignored = 0;
    int maxDepth = 0;
  }

  private static final class ScanRun {
    String requestId = "";
    AtomicBoolean cancelled;
    final List<JSONObject> games = new ArrayList<>();
    final List<String> selectedUris = new ArrayList<>();
    final List<String> scannedUris = new ArrayList<>();
    final List<String> completedUris = new ArrayList<>();
    final List<String> failedUris = new ArrayList<>();
    final List<String> partialUris = new ArrayList<>();
    final List<JSONObject> errors = new ArrayList<>();
    int totalVisited = 0;
    int totalIgnored = 0;
    int maxDepth = 0;
    int locationIndex = 0;
    int locationCount = 0;
    String currentLocation = "";
    long fingerprintBytes = 0;
    long lastProgressAt = 0;
    int lastProgressVisits = 0;
    boolean scanLimitReached = false;
    ScanRun() {}
    ScanRun(String requestId, AtomicBoolean cancelled) {
      this.requestId = requestId == null ? "" : requestId;
      this.cancelled = cancelled;
    }
  }

  private static final class BackupRecord {
    final String name;
    final String uri;
    final String sha256;
    BackupRecord(String name, String uri, String sha256) {
      this.name = name;
      this.uri = uri;
      this.sha256 = sha256;
    }
  }

  private WebView webView;
  private SharedPreferences prefs;
  private ActivityResultLauncher<Uri> folderPicker;
  private final Map<String, AtomicBoolean> activeScans = new ConcurrentHashMap<>();

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

  @Override public boolean dispatchKeyEvent(KeyEvent event) {
    if (event != null && event.getAction() == KeyEvent.ACTION_DOWN && webView != null) {
      int code = event.getKeyCode();
      if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
        String direction = code == KeyEvent.KEYCODE_BUTTON_L1 ? "previous" : "next";
        webView.evaluateJavascript(
            "if(window.thorVaultHandleShoulderTab){window.thorVaultHandleShoulderTab(" +
            JSONObject.quote(direction) + ");}", null
        );
        return true;
      }
      if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
          code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
        String direction = code == KeyEvent.KEYCODE_DPAD_UP ? "up" :
            (code == KeyEvent.KEYCODE_DPAD_DOWN ? "down" :
            (code == KeyEvent.KEYCODE_DPAD_LEFT ? "left" : "right"));
        webView.evaluateJavascript(
            "if(window.thorVaultMoveControllerFocus){window.thorVaultMoveControllerFocus(" +
            JSONObject.quote(direction) + ");}", null
        );
        return true;
      }
    }
    return super.dispatchKeyEvent(event);
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

  private List<String> getStoredGameTreeUris() {
    LinkedHashSet<String> values = new LinkedHashSet<>();
    String raw = prefs.getString("game_tree_uris", "");
    if (!raw.isEmpty()) {
      try {
        JSONArray array = new JSONArray(raw);
        for (int i = 0; i < array.length(); i++) {
          String value = array.optString(i, "");
          if (!value.isEmpty()) values.add(value);
        }
      } catch (Exception ignored) {
        // Migrate from the legacy single-folder preference below.
      }
    }
    String legacy = prefs.getString("game_tree_uri", "");
    if (!legacy.isEmpty()) values.add(legacy);
    return new ArrayList<>(values);
  }

  private void saveGameTreeUris(List<String> uris) {
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    if (uris != null) {
      for (String uri : uris) if (uri != null && !uri.isEmpty()) unique.add(uri);
    }
    JSONArray json = new JSONArray();
    for (String uri : unique) json.put(uri);
    List<String> stored = new ArrayList<>(unique);
    String primary = stored.isEmpty() ? "" : stored.get(stored.size() - 1);
    prefs.edit().putString("game_tree_uris", json.toString())
        .putString("game_tree_uri", primary).commit();
  }

  private void releaseTreePermission(Uri uri) {
    int flags = 0;
    if (hasPersistedReadPermission(uri)) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
    if (hasPersistedWritePermission(uri)) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    if (flags != 0) {
      try { getContentResolver().releasePersistableUriPermission(uri, flags); }
      catch (SecurityException ignored) { /* Provider may have removed the grant already. */ }
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
    boolean readable = root != null && root.exists() && root.canRead();
    List<String> uris = getStoredGameTreeUris();
    if (!uris.contains(uri.toString())) uris.add(uri.toString());
    saveGameTreeUris(uris);
    boolean committed = prefs.edit()
        .putString("game_tree_status", readable ? "granted" : "unreadable")
        .putString("game_tree_permission_mode", permissionMode)
        .putInt("game_tree_persisted_flags", persistedFlags)
        .putString("game_tree_error", readable ? warning :
            "The selected folder was returned, but Android reports that it is not readable.")
        .commit();
    if (!committed) {
      prefs.edit().putString("game_tree_status", "storage-error")
          .putString("game_tree_error", "ThorVault could not save the selected folder URI.").apply();
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
      List<String> uris = getStoredGameTreeUris();
      JSONArray locations = new JSONArray();
      JSONObject primary = null;
      String preferred = prefs.getString("game_tree_uri", "");
      for (String value : uris) {
        Uri uri = Uri.parse(value);
        DocumentFile root = DocumentFile.fromTreeUri(this, uri);
        boolean exists = root != null && root.exists();
        boolean readable = exists && root.canRead();
        boolean writable = exists && root.canWrite();
        boolean persistedRead = hasPersistedReadPermission(uri);
        boolean persistedWrite = hasPersistedWritePermission(uri);
        JSONObject location = new JSONObject()
            .put("uri", value).put("folderUri", value)
            .put("folderName", root == null ? "Unknown folder" : root.getName())
            .put("exists", exists).put("readable", readable).put("writable", writable)
            .put("persistedRead", persistedRead).put("persistedWrite", persistedWrite)
            .put("permissionMode", persistedWrite ? "read-write" : (persistedRead ? "read-only" : "temporary"))
            .put("status", readable ? "available" : "unavailable")
            .put("error", readable ? "" : "Folder is unavailable or its Android permission was revoked.");
        locations.put(location);
        if (primary == null || value.equals(preferred)) primary = location;
      }
      JSONObject result = new JSONObject()
          .put("ok", true).put("stored", !uris.isEmpty()).put("locationCount", uris.size())
          .put("locations", locations).put("status", uris.isEmpty() ? "none" : "stored")
          .put("permissionMode", primary == null ? "none" : primary.optString("permissionMode", "none"))
          .put("error", prefs.getString("game_tree_error", ""));
      if (primary == null) {
        result.put("folderUri", JSONObject.NULL).put("folderName", JSONObject.NULL)
            .put("persistedRead", false).put("persistedWrite", false)
            .put("readable", false).put("writable", false).put("exists", false);
      } else {
        result.put("folderUri", primary.optString("folderUri", ""))
            .put("folderName", primary.optString("folderName", ""))
            .put("persistedRead", primary.optBoolean("persistedRead", false))
            .put("persistedWrite", primary.optBoolean("persistedWrite", false))
            .put("readable", primary.optBoolean("readable", false))
            .put("writable", primary.optBoolean("writable", false))
            .put("exists", primary.optBoolean("exists", false));
      }
      return result.toString();
    } catch (Exception error) { return failure(error); }
  }

  private final class Bridge {
    @JavascriptInterface public boolean isAndroidApp() { return true; }

    @JavascriptInterface public void chooseGameFolder() {
      runOnUiThread(() -> {
        String current = prefs.getString("game_tree_uri", "");
        folderPicker.launch(current.isEmpty() ? null : Uri.parse(current));
      });
    }

    @JavascriptInterface public String getFolderStatus() { return folderStatusJson(); }

    @JavascriptInterface public String forgetGameLocation(String uriText) {
      try {
        if (uriText == null || uriText.isEmpty()) throw new IllegalArgumentException("Select a scan location to remove.");
        List<String> uris = getStoredGameTreeUris();
        if (!uris.remove(uriText)) return new JSONObject().put("ok", true).put("removed", false).toString();
        releaseTreePermission(Uri.parse(uriText));
        saveGameTreeUris(uris);
        prefs.edit().putString("game_tree_status", uris.isEmpty() ? "none" : "stored")
            .putString("game_tree_error", "").apply();
        notifyFolderChanged();
        return new JSONObject().put("ok", true).put("removed", true).toString();
      } catch (Exception error) { return failure(error); }
    }

    @JavascriptInterface public String forgetAllGameFolders() {
      try {
        List<String> uris = getStoredGameTreeUris();
        for (String value : uris) releaseTreePermission(Uri.parse(value));
        saveGameTreeUris(new ArrayList<String>());
        prefs.edit().putString("game_tree_status", "none").putString("game_tree_error", "")
            .putString("game_tree_permission_mode", "none").putInt("game_tree_persisted_flags", 0).commit();
        notifyFolderChanged();
        return new JSONObject().put("ok", true).toString();
      } catch (Exception error) { return failure(error); }
    }

    @JavascriptInterface public String forgetGameFolder() { return forgetAllGameFolders(); }

    @JavascriptInterface public String scanGames() {
      return MainActivity.this.scanGamesJson(null, null);
    }

    @JavascriptInterface public void scanGamesAsync(String requestId, String selectedUrisJson) {
      AtomicBoolean cancelled = new AtomicBoolean(false);
      activeScans.put(requestId, cancelled);
      new Thread(() -> {
        String result;
        try {
          result = MainActivity.this.scanGamesJson(selectedUrisJson, new ScanRun(requestId, cancelled));
        } catch (Exception error) { result = failure(error); }
        finally { activeScans.remove(requestId); }
        final String completedResult = result;
        webView.post(() -> webView.evaluateJavascript(
            "if(window.thorVaultNativeScanResult){window.thorVaultNativeScanResult(" +
            JSONObject.quote(requestId) + "," + JSONObject.quote(completedResult) + ");}", null
        ));
      }, "ThorVault-Scan-" + requestId).start();
    }

    @JavascriptInterface public void cancelGamesScan(String requestId) {
      AtomicBoolean cancelled = activeScans.get(requestId);
      if (cancelled != null) cancelled.set(true);
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

        Uri treeUri = findTreeForDocument(target);
        if (treeUri == null)
          throw new IllegalStateException("The active save is not inside a remembered scan location. Add its parent folder again before applying.");
        DocumentFile root = DocumentFile.fromTreeUri(MainActivity.this, treeUri);
        if (root == null || !root.canWrite())
          throw new IllegalStateException("Granted folder is not writable.");

        DocumentFile backups = root.findFile("ThorVault Backups");
        if (backups == null)
          backups = root.createDirectory("ThorVault Backups");
        if (backups == null)
          throw new IllegalStateException("Could not create backup folder.");

        BackupRecord backupRecord = createVerifiedBackup(backups, target, original, "pre-apply-original");
        String backupName = backupRecord.name;
        String backupSha = backupRecord.sha256;
        boolean backupRecordSaved = prefs.edit()
            .putString("last_apply_target_uri", uriText)
            .putString("last_apply_target_name", name(target))
            .putString("last_apply_backup_uri", backupRecord.uri)
            .putString("last_apply_backup_name", backupName)
            .putString("last_apply_original_sha256", originalSha)
            .putString("last_apply_written_sha256", "")
            .putLong("last_apply_byte_length", original.length)
            .putLong("last_apply_timestamp", 0L)
            .commit();
        if (!backupRecordSaved)
          throw new IllegalStateException("The verified backup was created, but ThorVault could not save its recovery record. No save changes were applied. Backup: " + backupName);

        String actualSha;
        try {
          write(target, replacement);
          byte[] written = read(target);
          actualSha = sha(written);
          if (!expectedSha.equals(actualSha) || written.length != replacement.length)
            throw new IllegalStateException("The written save did not match the requested bytes.");
        } catch (Exception writeError) {
          try {
            write(target, original);
            byte[] rollback = read(target);
            if (rollback.length != original.length || !originalSha.equals(sha(rollback)))
              throw new IllegalStateException("The restored bytes did not match the original save.");
          } catch (Exception rollbackError) {
            throw new IllegalStateException(
                "Apply failed and automatic rollback could not be verified. Keep the verified backup: " + backupName,
                rollbackError
            );
          }
          throw new IllegalStateException(
              "Apply failed; the original save was restored and verified. Backup: " + backupName,
              writeError
          );
        }

        long appliedAt = System.currentTimeMillis();
        prefs.edit()
            .putString("last_apply_target_uri", uriText)
            .putString("last_apply_target_name", name(target))
            .putString("last_apply_backup_uri", backupRecord.uri)
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
            .put("backupUri", backupRecord.uri)
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

        Uri treeUri = findTreeForDocument(target);
        if (treeUri == null)
          throw new IllegalStateException("The target save's parent folder permission is no longer available. Re-add that scan location before restoring.");
        DocumentFile root = DocumentFile.fromTreeUri(MainActivity.this, treeUri);
        if (root == null || !root.canWrite())
          throw new IllegalStateException("The active save's scan location is not writable.");
        DocumentFile backups = root.findFile("ThorVault Backups");
        if (backups == null) backups = root.createDirectory("ThorVault Backups");
        if (backups == null) throw new IllegalStateException("Could not access backup folder.");

        BackupRecord beforeRestore = createVerifiedBackup(backups, target, current, "pre-restore-current");
        String beforeRestoreName = beforeRestore.name;
        String preRestoreSha = beforeRestore.sha256;

        byte[] restored;
        String restoredSha;
        try {
          write(target, backupData);
          restored = read(target);
          restoredSha = sha(restored);
          if (!backupSha.equals(restoredSha) || restored.length != backupData.length)
            throw new IllegalStateException("The restored bytes did not match the selected backup.");
        } catch (Exception restoreError) {
          try {
            write(target, current);
            byte[] rollback = read(target);
            if (rollback.length != current.length || !currentSha.equals(sha(rollback)))
              throw new IllegalStateException("The pre-restore bytes did not match the save that was present before restore.");
          } catch (Exception rollbackError) {
            throw new IllegalStateException(
                "Restore failed and automatic rollback could not be verified. Keep the pre-restore safety backup: " + beforeRestoreName,
                rollbackError
            );
          }
          throw new IllegalStateException(
              "Restore failed; the save that was present before restore was restored and verified. Safety backup: " + beforeRestoreName,
              restoreError
          );
        }

        long restoredAt = System.currentTimeMillis();
        prefs.edit()
            .putLong("last_restore_timestamp", restoredAt)
            .putString("last_restore_sha256", restoredSha)
            .putString("last_restore_safety_backup_name", beforeRestoreName)
            .remove("last_apply_target_uri").remove("last_apply_target_name")
            .remove("last_apply_backup_uri").remove("last_apply_backup_name")
            .remove("last_apply_original_sha256").remove("last_apply_written_sha256")
            .remove("last_apply_byte_length").remove("last_apply_timestamp")
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

  private String scanGamesJson(String selectedUrisJson, ScanRun suppliedRun) {
    ScanRun run = suppliedRun == null ? new ScanRun() : suppliedRun;
    try {
      List<String> selected = new ArrayList<>();
      if (selectedUrisJson == null || selectedUrisJson.trim().isEmpty()) {
        selected.addAll(getStoredGameTreeUris());
      } else {
        JSONArray requested = new JSONArray(selectedUrisJson);
        Set<String> allowed = new HashSet<>(getStoredGameTreeUris());
        for (int i = 0; i < requested.length(); i++) {
          String uri = requested.optString(i, "");
          if (!uri.isEmpty() && allowed.contains(uri) && !selected.contains(uri)) selected.add(uri);
        }
      }
      run.selectedUris.clear();
      run.selectedUris.addAll(selected);
      run.locationCount = selected.size();
      if (selected.isEmpty())
        return buildScanResult(run, true).put("error", "Select at least one remembered folder to scan.").toString();

      for (int i = 0; i < selected.size(); i++) {
        if (run.cancelled != null && run.cancelled.get()) break;
        String treeText = selected.get(i);
        run.locationIndex = i + 1;
        run.scannedUris.add(treeText);
        Uri treeUri = Uri.parse(treeText);
        try {
          DocumentFile root = DocumentFile.fromTreeUri(MainActivity.this, treeUri);
          run.currentLocation = root == null || root.getName() == null ? "Selected folder " + (i + 1) : root.getName();
          publishScanProgress(run, true);
          if (root == null || !root.exists() || !root.canRead()) {
            run.failedUris.add(treeText);
            run.errors.add(new JSONObject().put("uri", treeText).put("folderName", run.currentLocation)
                .put("message", "This location is unavailable or its Android permission was revoked."));
            publishScanProgress(run, true);
            continue;
          }
          ScanStats stats = new ScanStats();
          scan(root, "", 0, run.games, stats, run, treeText);
          run.maxDepth = Math.max(run.maxDepth, stats.maxDepth);
          boolean cancelled = run.cancelled != null && run.cancelled.get();
          boolean limit = stats.visited >= MAX_SCAN_VISITS || run.games.size() >= MAX_SCAN_RESULTS;
          if (limit) run.scanLimitReached = true;
          if (cancelled || limit) {
            if (!run.partialUris.contains(treeText)) run.partialUris.add(treeText);
          } else if (!run.completedUris.contains(treeText)) run.completedUris.add(treeText);
          publishScanProgress(run, true);
          if (run.games.size() >= MAX_SCAN_RESULTS) break;
        } catch (Exception error) {
          run.failedUris.add(treeText);
          run.errors.add(new JSONObject().put("uri", treeText).put("folderName", run.currentLocation)
              .put("message", error.getMessage() == null ? "Folder scan failed." : error.getMessage()));
          publishScanProgress(run, true);
        }
      }
      if (run.cancelled != null && run.cancelled.get() && run.locationIndex > 0) {
        String currentUri = selected.get(Math.min(run.locationIndex - 1, selected.size() - 1));
        if (run.scannedUris.contains(currentUri) && !run.completedUris.contains(currentUri) &&
            !run.failedUris.contains(currentUri) && !run.partialUris.contains(currentUri)) run.partialUris.add(currentUri);
      }
      return buildScanResult(run, true).toString();
    } catch (Exception error) { return failure(error); }
  }

  private JSONObject buildScanResult(ScanRun run, boolean done) {
    try {
      JSONObject status = new JSONObject(folderStatusJson());
      JSONArray selected = new JSONArray();
      JSONArray scanned = new JSONArray();
      JSONArray completed = new JSONArray();
      JSONArray failed = new JSONArray();
      JSONArray partial = new JSONArray();
      JSONArray errors = new JSONArray();
      for (String item : run.selectedUris) selected.put(item);
      for (String item : run.scannedUris) scanned.put(item);
      for (String item : run.completedUris) completed.put(item);
      for (String item : run.failedUris) failed.put(item);
      for (String item : run.partialUris) partial.put(item);
      for (JSONObject item : run.errors) errors.put(item);
      return new JSONObject().put("ok", true).put("done", done)
          .put("cancelled", run.cancelled != null && run.cancelled.get())
          .put("folderStatus", status).put("locations", status.optJSONArray("locations"))
          .put("folderUri", status.opt("folderUri")).put("folderName", status.opt("folderName"))
          .put("persistedRead", status.optBoolean("persistedRead", false))
          .put("persistedWrite", status.optBoolean("persistedWrite", false))
          .put("readable", status.optBoolean("readable", false)).put("writable", status.optBoolean("writable", false))
          .put("permissionMode", status.optString("permissionMode", "none"))
          .put("games", new JSONArray(run.games)).put("selectedUris", selected).put("scannedUris", scanned)
          .put("completedUris", completed).put("failedUris", failed).put("partialUris", partial).put("errors", errors)
          .put("currentLocation", run.currentLocation).put("locationIndex", run.locationIndex)
          .put("locationCount", run.locationCount).put("visitedCount", run.totalVisited)
          .put("ignoredCount", run.totalIgnored).put("maxDepthReached", run.maxDepth)
          .put("scanLimitReached", run.scanLimitReached).put("fingerprintBytes", run.fingerprintBytes);
    } catch (Exception ignored) {
      JSONObject fallback = new JSONObject();
      try { fallback.put("ok", false).put("error", "Could not build scan result."); } catch (Exception ignoredAgain) {}
      return fallback;
    }
  }

  private void publishScanProgress(ScanRun run, boolean force) {
    if (run == null || run.requestId.isEmpty() || webView == null) return;
    long now = System.currentTimeMillis();
    if (!force && now - run.lastProgressAt < 1000 && run.totalVisited - run.lastProgressVisits < 1000) return;
    run.lastProgressAt = now;
    run.lastProgressVisits = run.totalVisited;
    String payload = buildScanResult(run, false).toString();
    webView.post(() -> webView.evaluateJavascript(
        "if(window.thorVaultNativeScanProgress){window.thorVaultNativeScanProgress(" +
        JSONObject.quote(run.requestId) + "," + JSONObject.quote(payload) + ");}", null
    ));
  }

  private void scan(DocumentFile dir, String path, int depth, List<JSONObject> out,
      ScanStats stats, ScanRun run, String treeUri) throws Exception {
    if ((run.cancelled != null && run.cancelled.get()) || depth > MAX_SCAN_DEPTH ||
        stats.visited >= MAX_SCAN_VISITS || out.size() >= MAX_SCAN_RESULTS) return;
    stats.maxDepth = Math.max(stats.maxDepth, depth);
    DocumentFile[] children = dir.listFiles();
    List<DocumentFile> directories = new ArrayList<>();
    List<DocumentFile> files = new ArrayList<>();
    for (DocumentFile child : children) {
      if (child.isDirectory()) directories.add(child); else files.add(child);
    }
    Collections.sort(directories, new Comparator<DocumentFile>() {
      @Override public int compare(DocumentFile left, DocumentFile right) {
        String leftName = left.getName() == null ? "" : left.getName();
        String rightName = right.getName() == null ? "" : right.getName();
        return Integer.compare(directoryPriority(leftName, path), directoryPriority(rightName, path));
      }
    });
    for (DocumentFile file : files) {
      if ((run.cancelled != null && run.cancelled.get()) || stats.visited >= MAX_SCAN_VISITS || out.size() >= MAX_SCAN_RESULTS) return;
      stats.visited++;
      run.totalVisited++;
      String name = file.getName() == null ? "unnamed" : file.getName();
      String relative = path.isEmpty() ? name : path + "/" + name;
      String identificationPath = (run.currentLocation == null || run.currentLocation.isEmpty() ? "" : run.currentLocation + "/") + relative;
      long size = file.length();
      if (looksLikeSave(name, size, identificationPath)) {
        JSONObject candidate = new JSONObject().put("uri", file.getUri().toString()).put("treeUri", treeUri)
            .put("rootName", run.currentLocation).put("name", name).put("relativePath", relative)
            .put("displayPath", identificationPath).put("size", size).put("lastModified", file.lastModified())
            .put("kind", classify(name, identificationPath)).put("label", saveLabel(name, identificationPath))
            .put("emulator", detectEmulator(name, identificationPath)).put("system", detectSystem(name, size, identificationPath))
            .put("detectedGame", detectGame(name, identificationPath)).put("confidence", saveConfidence(name, identificationPath));
        if (size > 0 && size <= MAX_SCAN_HASH_FILE_BYTES && run.fingerprintBytes + size <= MAX_SCAN_FINGERPRINT_BYTES) {
          run.fingerprintBytes += size;
          try { candidate.put("sha256", sha(read(file.getUri()))).put("fingerprintStatus", "verified"); }
          catch (Exception fingerprintError) { candidate.put("fingerprintStatus", "unavailable"); }
        } else candidate.put("fingerprintStatus", "skipped-size-or-budget");
        out.add(candidate);
      } else { stats.ignored++; run.totalIgnored++; }
      publishScanProgress(run, false);
    }
    for (DocumentFile directory : directories) {
      if ((run.cancelled != null && run.cancelled.get()) || stats.visited >= MAX_SCAN_VISITS || out.size() >= MAX_SCAN_RESULTS) return;
      String name = directory.getName() == null ? "unnamed" : directory.getName();
      if (isIgnoredDirectory(name)) { stats.ignored++; run.totalIgnored++; continue; }
      stats.visited++;
      run.totalVisited++;
      String relative = path.isEmpty() ? name : path + "/" + name;
      publishScanProgress(run, false);
      scan(directory, relative, depth + 1, out, stats, run, treeUri);
    }
  }

  private static boolean isIgnoredDirectory(String name) {
    String value = name == null ? "" : name.toLowerCase(Locale.ROOT);
    return value.equals("thorvault backups") || value.equals("cache") || value.equals("caches") ||
        value.equals("shadercache") || value.equals("shader cache") || value.equals("states") ||
        value.equals("savestates") || value.equals("save states") || value.equals("screenshots") ||
        value.equals("thumbnails") || value.equals("covers") || value.equals("artwork") ||
        value.equals("cheats") || value.equals("logs") || value.equals("crash reports") || value.equals(".git");
  }

  private static int directoryPriority(String name, String parentPath) {
    String value = (parentPath + "/" + name).toLowerCase(Locale.ROOT);
    if (value.contains("nintendo 3ds") || value.contains("/title") || value.contains("/data") || value.contains("/sdmc") || value.contains("/users")) return 0;
    if (value.contains("azahar") || value.contains("citra") || value.contains("melonds") || value.contains("/saves") || value.contains("/save")) return 1;
    if (value.contains("/3ds") || value.contains("/nds") || value.contains("/gba")) return 2;
    return 5;
  }

  private static boolean looksLikeSave(String name, long size, String relativePath) {
    if (size < 16 * 1024 || size > MAX_FILE) return false;
    String n = name.toLowerCase(Locale.ROOT);
    String p = relativePath.toLowerCase(Locale.ROOT);
    if (n.endsWith(".bak") || n.endsWith(".backup") || n.endsWith(".old") || n.endsWith(".tmp") || n.endsWith(".state") || n.endsWith(".savestate")) return false;
    if (n.equals("main")) return p.contains("/title/") || p.contains("/save/") || p.contains("/saves/") || p.contains("azahar") || p.contains("citra") || p.contains("nintendo 3ds") || p.contains("melonds") || p.contains("melon ds") || p.contains("00040000") || p.contains("00000001") || p.contains("sdmc");
    if (n.endsWith(".sav") || n.endsWith(".dsv") || n.endsWith(".srm")) return true;
    boolean saveNamed = n.equals("save.dat") || n.equals("savedata.dat") || n.equals("main.dat") || n.equals("save.bin") || n.equals("savedata.bin") || n.equals("main.bin") || n.equals("pokemon.sav");
    boolean savePath = (p.contains("/save/") || p.contains("/saves/") || p.contains("/battery/")) && (n.contains("save") || n.startsWith("main.") || n.endsWith(".bin") || n.endsWith(".dat"));
    return saveNamed || savePath;
  }

  private static String detectEmulator(String name, String path) {
    String p = ((path == null ? "" : path) + "/" + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
    if (p.contains("azahar") || p.contains("citra") || p.contains("nintendo 3ds") || p.contains("/title/00040000/") || p.contains("sdmc")) return "Azahar / Citra";
    if (p.contains("melonds") || p.contains("melon ds")) return "melonDS";
    if (p.contains("desmume")) return "DeSmuME";
    if (p.contains("drastic")) return "DraStic";
    if (p.contains("mgba") || p.contains("m-gba")) return "mGBA";
    if (p.contains("retroarch") || p.endsWith(".srm")) return "RetroArch-compatible";
    if (p.contains("myboy") || p.contains("my boy")) return "My Boy!";
    if (p.contains("pizzaboy") || p.contains("pizza boy")) return "Pizza Boy";
    if (p.contains("john gba") || p.contains("johngba")) return "John GBA";
    if (p.contains("visualboyadvance") || p.contains("visual boy advance")) return "VisualBoyAdvance";
    if (p.contains("ryujinx")) return "Ryujinx";
    if (p.contains("eden")) return "Eden";
    if (p.contains("yuzu")) return "yuzu-compatible";
    if (p.contains("citron")) return "Citron";
    return "Unknown / exported save";
  }

  private static String detectSystem(String name, long size, String path) {
    String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
    String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
    if (n.equals("main") && (p.contains("nintendo 3ds") || p.contains("azahar") || p.contains("citra") || p.contains("/title/00040000/") || p.contains("00040000") || p.contains("sdmc"))) return "Nintendo 3DS";
    if (p.contains("/3ds/") || p.contains("/3ds saves/")) return "Nintendo 3DS";
    if (p.contains("/nds/") || p.contains("/ds/") || p.contains("melonds") || p.contains("drastic") || p.contains("desmume")) return "Nintendo DS";
    if (p.contains("/gba/") || p.contains("game boy advance") || p.contains("gameboyadvance") || p.contains("mgba") || p.contains("myboy") || p.contains("pizzaboy")) return "Game Boy Advance";
    if (n.endsWith(".srm")) return "RetroArch battery save (system inferred from path)";
    if (n.endsWith(".dsv")) return "Nintendo DS";
    if (n.endsWith(".sav") && (size == 32768 || size == 65536 || size == 131072)) return "Likely GBA (size-based hint)";
    if (n.endsWith(".sav")) return "GBA / Nintendo DS / exported save";
    return "Unknown system";
  }

  private static String detectGame(String name, String path) {
    String p = ((path == null ? "" : path) + "/" + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
    String[][] known = {
      {"0011c400", "Pokémon Omega Ruby"}, {"0011c500", "Pokémon Alpha Sapphire"},
      {"0004000000055e00", "Pokémon X"}, {"0004000000055f00", "Pokémon Y"},
      {"00040000001b5100", "Pokémon Sun"}, {"00040000001b5000", "Pokémon Moon"},
      {"00040000001b5200", "Pokémon Ultra Sun"}, {"00040000001b5300", "Pokémon Ultra Moon"},
      {"omega ruby", "Pokémon Omega Ruby"}, {"alpha sapphire", "Pokémon Alpha Sapphire"},
      {"ultra sun", "Pokémon Ultra Sun"}, {"ultra moon", "Pokémon Ultra Moon"},
      {"white 2", "Pokémon White 2"}, {"black 2", "Pokémon Black 2"},
      {"white version", "Pokémon White"}, {"black version", "Pokémon Black"},
      {"platinum", "Pokémon Platinum"}, {"heartgold", "Pokémon HeartGold"},
      {"soulsilver", "Pokémon SoulSilver"}, {"fire red", "Pokémon FireRed"},
      {"leaf green", "Pokémon LeafGreen"}, {"emerald version", "Pokémon Emerald"},
      {"crystal version", "Pokémon Crystal"}, {"gold version", "Pokémon Gold"},
      {"silver version", "Pokémon Silver"}, {"yellow version", "Pokémon Yellow"}
    };
    for (String[] entry : known) if (p.contains(entry[0])) return entry[1];
    String[][] tokenGames = {
      {"emerald", "Pokémon Emerald"}, {"crystal", "Pokémon Crystal"},
      {"sapphire", "Pokémon Sapphire"}, {"ruby", "Pokémon Ruby"},
      {"gold", "Pokémon Gold"}, {"silver", "Pokémon Silver"},
      {"yellow", "Pokémon Yellow"}, {"red", "Pokémon Red"}, {"blue", "Pokémon Blue"}
    };
    for (String[] entry : tokenGames) {
      if (p.matches("(?s).*(?:^|[^a-z])" + entry[0] + "(?:$|[^a-z]).*")) return entry[1];
    }
    return "Unidentified Pokémon save candidate";
  }

  private static String saveConfidence(String name, String path) {
    String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
    String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
    if (n.endsWith(".sav") || n.endsWith(".dsv") || n.endsWith(".srm")) return "high";
    if (n.equals("main") && (p.contains("/title/00040000/") || p.contains("nintendo 3ds") || p.contains("azahar") || p.contains("citra") || p.contains("00040000"))) return "high";
    if (n.equals("save.dat") || n.equals("savedata.dat") || n.equals("main.dat") || n.equals("save.bin") || n.equals("savedata.bin") || n.equals("main.bin")) return "medium";
    return "possible";
  }

  private static String classify(String name, String path) {
    String n = name.toLowerCase(Locale.ROOT);
    String p = path.toLowerCase(Locale.ROOT);
    if (n.equals("main") && (p.contains("azahar") || p.contains("citra") || p.contains("nintendo 3ds") || p.contains("/title/00040000/") || p.contains("00040000"))) return "Nintendo 3DS active main";
    if (n.endsWith(".dsv")) return "Nintendo DS battery save (.dsv)";
    if (n.endsWith(".srm")) return "RetroArch battery save (.srm)";
    if (n.endsWith(".sav")) return "Battery save (.sav)";
    if (n.endsWith(".dat")) return "Named save data (.dat)";
    if (n.endsWith(".bin")) return "Save-path binary candidate (.bin)";
    return "Possible emulator save";
  }

  private static String saveLabel(String name, String path) {
    String lowerPath = path.toLowerCase(Locale.ROOT);
    if (name.equalsIgnoreCase("main") && (lowerPath.contains("/title/00040000/") || lowerPath.contains("00040000"))) return "Nintendo 3DS main";
    return name;
  }

  private Uri findTreeForDocument(Uri documentUri) {
    if (documentUri == null) return null;
    String targetTreeId = "";
    try { targetTreeId = DocumentsContract.getTreeDocumentId(documentUri); } catch (Exception ignored) {}
    String targetText = documentUri.toString();
    Uri best = null;
    int bestLength = -1;
    for (String location : getStoredGameTreeUris()) {
      try {
        Uri tree = Uri.parse(location);
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        boolean matches = !targetTreeId.isEmpty() && targetTreeId.equals(treeId);
        if (!matches) matches = targetText.startsWith(location + "/document/") || targetText.equals(location);
        if (matches && location.length() > bestLength) { best = tree; bestLength = location.length(); }
      } catch (Exception ignored) {}
    }
    return best;
  }

  private BackupRecord createVerifiedBackup(DocumentFile backups, Uri target, byte[] contents, String kind) throws Exception {
    String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
    String safeTargetName = name(target).replaceAll("[^A-Za-z0-9._-]+", "-");
    String unique = UUID.randomUUID().toString().substring(0, 8);
    String backupName = stamp + "-" + safeTargetName + "-" + unique + ".bak";
    DocumentFile backup = backups.createFile("application/octet-stream", backupName);
    if (backup == null) throw new IllegalStateException("Could not create backup " + backupName + ".");
    String expectedSha = sha(contents);
    byte[] check;
    String actualSha;
    try {
      write(backup.getUri(), contents);
      check = read(backup.getUri());
      actualSha = sha(check);
      if (check.length != contents.length || !expectedSha.equals(actualSha))
        throw new IllegalStateException("Backup bytes failed length or SHA-256 verification.");
    } catch (Exception backupError) {
      try { backup.delete(); } catch (Exception ignored) {}
      throw new IllegalStateException("Backup verification failed. The active save was not modified.", backupError);
    }
    String manifestName = backupName + ".json";
    DocumentFile manifest = backups.createFile("application/json", manifestName);
    if (manifest == null) throw new IllegalStateException("The backup data is verified but its manifest could not be created. No save changes were applied; keep " + backupName + ".");
    JSONObject metadata = new JSONObject().put("format", "ThorVaultBackup").put("formatVersion", 1)
        .put("kind", kind).put("createdAt", System.currentTimeMillis()).put("targetName", name(target))
        .put("targetUri", target.toString()).put("backupName", backupName).put("backupUri", backup.getUri().toString())
        .put("byteLength", contents.length).put("sha256", expectedSha).put("verified", true);
    try {
      write(manifest.getUri(), metadata.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      JSONObject verify = new JSONObject(new String(read(manifest.getUri()), java.nio.charset.StandardCharsets.UTF_8));
      if (!expectedSha.equals(verify.optString("sha256", "")) || !backupName.equals(verify.optString("backupName", ""))) throw new IllegalStateException("Backup manifest verification failed.");
    } catch (Exception manifestError) {
      try { manifest.delete(); } catch (Exception ignored) {}
      throw new IllegalStateException("The backup file is verified, but its manifest could not be verified. No save changes were applied; keep " + backupName + ".", manifestError);
    }
    return new BackupRecord(backupName, backup.getUri().toString(), actualSha);
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
