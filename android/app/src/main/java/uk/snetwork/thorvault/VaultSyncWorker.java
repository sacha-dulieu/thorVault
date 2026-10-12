package uk.snetwork.thorvault;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class VaultSyncWorker extends Worker {
  static final String PREFS = "thorvault_native";

  public VaultSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
  }

  @NonNull @Override public Result doWork() {
    SharedPreferences p =
        getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String server = trim(p.getString("server_url", ""));
    String token = p.getString("server_token", "");
    String vaultText = p.getString("vault_json", "");

    if (server.isEmpty() || token.isEmpty() || vaultText.isEmpty()) {
      status(p, "idle", "Waiting for server settings and a local Vault.");
      return Result.success();
    }

    try {
      HttpResult healthResult = request("GET", server + "/api/health", token, null);
      if (healthResult.code != 200) {
        status(p, "error", "Could not check the ThorVault server revision.");
        return Result.retry();
      }

      JSONObject health = new JSONObject(healthResult.body);
      JSONObject latest = health.optJSONObject("latestSnapshot");
      String latestSha = latest == null ? "" : latest.optString("sha256", "");
      String latestServerId = health.optString("serverId", "");
      String baseSha = p.getString("last_server_sha256", "");
      String baseServerId = p.getString("last_server_id", "");
      boolean dirty = p.getBoolean("vault_dirty", false);

      if (!baseServerId.isEmpty() &&
          !latestServerId.isEmpty() &&
          !baseServerId.equals(latestServerId)) {
        status(
            p,
            "reconcile",
            "A different ThorVault server is configured. Open ThorVault to pair safely."
        );
        return Result.success();
      }

      if (!dirty) {
        if (!baseSha.isEmpty() &&
            !latestSha.isEmpty() &&
            !baseSha.equals(latestSha)) {
          status(
              p,
              "remote-newer",
              "The shared Vault is newer. ThorVault will fast-forward when the app opens."
          );
        } else {
          status(p, "synced", "No local Vault changes are waiting.");
        }
        return Result.success();
      }

      // Background upload is safe only when the server is still exactly on the
      // client's common base. If the server moved too, foreground JS performs
      // the full three-way comparison before deciding whether a conflict exists.
      if (!baseSha.isEmpty() &&
          !latestSha.isEmpty() &&
          !baseSha.equals(latestSha)) {
        status(
            p,
            "reconcile",
            "Local and server state both need comparison. Open ThorVault to reconcile safely."
        );
        return Result.success();
      }

      JSONObject vault = new JSONObject(vaultText);
      JSONObject body = new JSONObject();
      body.put("deviceId", p.getString("device_id", "android-device"));
      body.put("deviceName", p.getString("device_name", Build.MODEL));
      body.put("revision", vault.optInt("revision", 0));
      body.put("vault", vault);
      if (!baseSha.isEmpty()) body.put("baseSha256", baseSha);

      HttpResult result =
          request("POST", server + "/api/snapshots", token, body.toString());

      if (result.code == 200 || result.code == 201) {
        JSONObject response = new JSONObject(result.body);
        p.edit()
            .putBoolean("vault_dirty", false)
            .putString("last_server_sha256", response.optString("sha256", ""))
            .putString("last_server_id", latestServerId)
            .putString("last_sync_at", response.optString("createdAt", ""))
            .putString("sync_state", "synced")
            .putString("sync_message", "Vault synchronized in the background.")
            .apply();
        return Result.success();
      }

      if (result.code == 409) {
        status(
            p,
            "reconcile",
            "The server changed during background sync. Open ThorVault for a safe three-way comparison."
        );
        return Result.success();
      }

      if (result.code == 401) {
        status(p, "error", "The server rejected the Android token.");
        return Result.failure();
      }

      status(p, "error", "Sync returned HTTP " + result.code + ".");
      return Result.retry();
    } catch (Exception error) {
      status(p, "error", "Sync queued for retry: " + error.getMessage());
      return Result.retry();
    }
  }

  private static String trim(String text) {
    String value = text == null ? "" : text.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static void status(SharedPreferences p, String state, String message) {
    p.edit().putString("sync_state", state).putString("sync_message", message).apply();
  }

  private static HttpResult request(String method, String address, String token, String json)
      throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
    c.setRequestMethod(method);
    c.setConnectTimeout(5000);
    c.setReadTimeout(10000);
    c.setRequestProperty("X-ThorVault-Token", token);
    c.setRequestProperty("X-ThorVault-Client-Version", "0.12.23");
    c.setRequestProperty("X-ThorVault-Api-Version", "1");
    c.setRequestProperty("X-ThorVault-Platform", "android-worker");
    c.setRequestProperty("Accept", "application/json");
    if (json != null) {
      byte[] data = json.getBytes(StandardCharsets.UTF_8);
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json");
      c.setFixedLengthStreamingMode(data.length);
      try (OutputStream out = c.getOutputStream()) { out.write(data); }
    }
    int code = c.getResponseCode();
    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
    String body = in == null ? "" : read(in);
    c.disconnect();
    return new HttpResult(code, body);
  }

  private static String read(InputStream input) throws Exception {
    try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static final class HttpResult {
    final int code;
    final String body;
    HttpResult(int code, String body) { this.code = code; this.body = body; }
  }
}
