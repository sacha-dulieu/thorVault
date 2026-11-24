package uk.snetwork.thorvault;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;

/** Full-screen details surface launched on the other logical display where Android permits it. */
public final class SecondaryScreenActivity extends Activity {
  public static final String EXTRA_POKEMON_JSON = "thorvault_secondary_pokemon_json";

  private static volatile WeakReference<SecondaryScreenActivity> active = new WeakReference<>(null);
  private static volatile int pendingDisplayId = Display.INVALID_DISPLAY;
  private static volatile boolean pendingSucceeded = false;
  private static volatile String pendingError = "";
  private static volatile CountDownLatch pendingLatch;

  private TextView eyebrow;
  private TextView title;
  private TextView subtitle;
  private TextView facts;
  private TextView moves;
  private TextView footer;
  private int expectedDisplayId = Display.INVALID_DISPLAY;

  static synchronized CountDownLatch prepareLaunch(int displayId) {
    pendingDisplayId = displayId;
    pendingSucceeded = false;
    pendingError = "";
    pendingLatch = new CountDownLatch(1);
    return pendingLatch;
  }

  static synchronized void reportLaunchResult(int displayId, boolean success, String error) {
    if (pendingDisplayId != Display.INVALID_DISPLAY && pendingDisplayId == displayId) {
      pendingSucceeded = success;
      pendingError = error == null ? "" : error;
      CountDownLatch latch = pendingLatch;
      if (latch != null) latch.countDown();
    }
  }

  static boolean wasLastLaunchSuccessful(int displayId) {
    return pendingDisplayId == displayId && pendingSucceeded;
  }

  static String getLastLaunchError() { return pendingError; }

  static int getActiveDisplayId() {
    SecondaryScreenActivity screen = active == null ? null : active.get();
    if (screen == null || screen.isFinishing()) return Display.INVALID_DISPLAY;
    return screen.actualDisplayId();
  }

  static boolean isActiveOn(int displayId) {
    return getActiveDisplayId() == displayId;
  }

  static void updateActiveOnDisplay(int displayId, String json) {
    SecondaryScreenActivity screen = active == null ? null : active.get();
    if (screen == null || screen.isFinishing() || screen.actualDisplayId() != displayId) return;
    screen.runOnUiThread(() -> {
      if (!screen.isFinishing()) screen.renderPokemon(json);
    });
  }

  static void closeActive() {
    SecondaryScreenActivity screen = active == null ? null : active.get();
    if (screen != null && !screen.isFinishing()) screen.runOnUiThread(screen::finish);
  }

  static void closeIfActiveOn(int displayId) {
    SecondaryScreenActivity screen = active == null ? null : active.get();
    if (screen != null && !screen.isFinishing() && screen.actualDisplayId() == displayId)
      screen.runOnUiThread(screen::finish);
  }

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    expectedDisplayId = getIntent().getIntExtra(
        "thorvault_expected_display_id", Display.INVALID_DISPLAY);
    buildLayout();
    renderPokemon(getIntent().getStringExtra(EXTRA_POKEMON_JSON));
    getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN |
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    expectedDisplayId = intent.getIntExtra("thorvault_expected_display_id", Display.INVALID_DISPLAY);
    renderPokemon(intent.getStringExtra(EXTRA_POKEMON_JSON));
  }

  @Override protected void onResume() {
    super.onResume();
    int actual = actualDisplayId();
    if (expectedDisplayId != Display.INVALID_DISPLAY && expectedDisplayId != actual) {
      reportLaunchResult(expectedDisplayId, false,
          "Android opened the secondary screen on display " + actual +
          " instead of requested display " + expectedDisplayId + ".");
      finish();
      return;
    }
    active = new WeakReference<>(this);
    reportLaunchResult(actual, true, "");
    if (actual != Display.INVALID_DISPLAY) {
      getSharedPreferences(VaultSyncWorker.PREFS, Context.MODE_PRIVATE).edit()
          .putString("dual_screen_status", "Dual-screen mode active. Pokémon details are on display " + actual + ".")
          .apply();
    }
  }

  @Override protected void onDestroy() {
    SecondaryScreenActivity screen = active == null ? null : active.get();
    if (screen == this) active = new WeakReference<>(null);
    super.onDestroy();
  }

  private int actualDisplayId() {
    if (Build.VERSION.SDK_INT < 30) return Display.DEFAULT_DISPLAY;
    try {
      Display display = getDisplay();
      return display == null ? Display.DEFAULT_DISPLAY : display.getDisplayId();
    } catch (Exception ignored) {
      return Display.DEFAULT_DISPLAY;
    }
  }

  private void buildLayout() {
    int pad = Math.max(16, Math.round(getResources().getDisplayMetrics().density * 22));
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(Color.rgb(8, 12, 24));

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER_VERTICAL);
    root.setPadding(pad, pad, pad, pad);
    scroll.addView(root, new ScrollView.LayoutParams(-1, -1));

    eyebrow = new TextView(this);
    eyebrow.setText("THORVAULT  ·  POKÉMON DETAILS");
    eyebrow.setTextColor(Color.rgb(72, 223, 177));
    eyebrow.setTextSize(12);
    eyebrow.setLetterSpacing(0.08f);
    eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    root.addView(eyebrow, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(pad, pad, pad, pad);
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(Color.rgb(17, 24, 44));
    shape.setCornerRadius(pad * 0.9f);
    shape.setStroke(1, Color.rgb(43, 54, 85));
    card.setBackground(shape);
    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
    cardParams.topMargin = Math.max(10, pad / 2);
    root.addView(card, cardParams);

    title = new TextView(this);
    title.setTextColor(Color.WHITE);
    title.setTextSize(27);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    card.addView(title, new LinearLayout.LayoutParams(-1, -2));

    subtitle = new TextView(this);
    subtitle.setTextColor(Color.rgb(72, 223, 177));
    subtitle.setTextSize(16);
    LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
    subParams.topMargin = pad / 5;
    card.addView(subtitle, subParams);

    facts = new TextView(this);
    facts.setTextColor(Color.rgb(218, 225, 245));
    facts.setTextSize(15);
    facts.setLineSpacing(4f, 1f);
    LinearLayout.LayoutParams factsParams = new LinearLayout.LayoutParams(-1, -2);
    factsParams.topMargin = pad / 2;
    card.addView(facts, factsParams);

    moves = new TextView(this);
    moves.setTextColor(Color.rgb(158, 169, 198));
    moves.setTextSize(14);
    moves.setLineSpacing(3f, 1f);
    LinearLayout.LayoutParams movesParams = new LinearLayout.LayoutParams(-1, -2);
    movesParams.topMargin = pad / 3;
    card.addView(moves, movesParams);

    footer = new TextView(this);
    footer.setTextColor(Color.rgb(120, 133, 163));
    footer.setTextSize(12);
    footer.setText("Select a Pokémon in the main ThorVault screen to show its details here.");
    LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
    footerParams.topMargin = pad / 2;
    root.addView(footer, footerParams);

    setContentView(scroll);
  }

  private void renderPokemon(String json) {
    JSONObject data;
    try { data = new JSONObject(json == null ? "{}" : json); }
    catch (Exception ignored) { data = new JSONObject(); }
    String nickname = data.optString("nickname", "");
    if (nickname.isEmpty() || "null".equals(nickname)) nickname = data.optString("species", "");
    if (nickname.isEmpty() || "null".equals(nickname)) {
      title.setText("Select a Pokémon");
      subtitle.setText("Dual-screen mode active");
      facts.setText("Choose any Pokémon on the main screen. Its details will appear here while you browse your boxes.");
      moves.setText("Pokémon moves will appear here.");
      footer.setText("Display " + actualDisplayId() + " · Waiting for a selection");
      return;
    }

    String species = data.optString("species", "Pokémon");
    String level = data.optString("level", "?");
    String shiny = data.optBoolean("shiny", false) ? "Shiny" : "Normal";
    title.setText(nickname);
    subtitle.setText(species + "   ·   Lv. " + level + "   ·   " + shiny);

    StringBuilder lines = new StringBuilder();
    addFact(lines, "Nature", data.optString("nature", "Unknown"));
    addFact(lines, "Ability", data.optString("abilityName", data.optString("abilityId", "Unknown")));
    addFact(lines, "Held item", data.optString("heldItemName", data.optString("heldItemId", "None")));
    addFact(lines, "Gender", data.optString("gender", "Unknown"));
    addFact(lines, "Origin", data.optString("originGame", "Unknown"));
    addFact(lines, "Trainer", data.optString("originalTrainer", "Unknown"));
    addFact(lines, "Location", "Box " + data.optInt("box", 1) + " · Slot " + (data.optInt("slot", 0) + 1));
    facts.setText(lines.toString());

    JSONArray moveArray = data.optJSONArray("moveNames");
    if (moveArray == null) moveArray = data.optJSONArray("moves");
    StringBuilder moveText = new StringBuilder("MOVES\n");
    if (moveArray != null) {
      for (int i = 0; i < moveArray.length(); i++) {
        String move = moveArray.optString(i, "");
        if (!move.isEmpty() && !"0".equals(move) && !"null".equals(move)) {
          if (moveText.length() > 6) moveText.append("  ·  ");
          moveText.append(move);
        }
      }
    }
    if (moveText.length() <= 6) moveText.append("No move data available");
    moves.setText(moveText.toString());
    footer.setText("Display " + actualDisplayId() + " · Select another Pokémon to update this panel");
  }

  private static void addFact(StringBuilder target, String label, String value) {
    if (value == null || value.isEmpty() || "null".equals(value)) value = "Unknown";
    if (target.length() > 0) target.append('\n');
    target.append(label).append("   ").append(value);
  }
}
