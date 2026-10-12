(function () {
  "use strict";
  var androidInitialized = false;

  function nativeAvailable() {
    try {
      return Boolean(
        window.__THORVAULT_ANDROID__ ||
        /ThorVaultAndroid\//.test(navigator.userAgent || "") ||
        (window.ThorVaultNative &&
          (typeof window.ThorVaultNative.isAndroidApp !== "function" ||
           window.ThorVaultNative.isAndroidApp() === true))
      );
    } catch (_error) {
      return Boolean(window.ThorVaultNative || window.__THORVAULT_ANDROID__);
    }
  }

  function appApi() {
    if (!window.ThorVaultApp)
      throw new Error("ThorVault's Android app API is not ready.");
    return window.ThorVaultApp;
  }

  function html(value) {
    if (window.ThorVaultApp &&
        typeof window.ThorVaultApp.escapeHtml === "function")
      return window.ThorVaultApp.escapeHtml(value);
    var text = value === null || value === undefined ? "" : String(value);
    return text.replace(/[&<>"']/g, function (character) {
      return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[character];
    });
  }

  function setSaveOpenOverlay(visible, title, message) {
    var overlay = document.querySelector("#saveOpenOverlay");
    if (!overlay) return;
    overlay.classList.toggle("hidden", !visible);
    if (title) document.querySelector("#saveOpenTitle").textContent = title;
    if (message) document.querySelector("#saveOpenMessage").textContent = message;
  }

  function setFolderPanel(kind, title, message) {
    var panel = document.querySelector("#androidFolderStatus");
    if (!panel) return;
    panel.className = "shared-vault-status " + kind;
    panel.innerHTML =
      "<strong>" + html(title) + "</strong>" +
      "<p class='meta'>" + html(message || "") + "</p>";
  }

  var nativeScanRequests = new Map();
  var saveFingerprintRequests = new Map();

  window.thorVaultNativeScanResult = function (requestId, rawResult) {
    var pending = nativeScanRequests.get(String(requestId));
    if (!pending) return;
    nativeScanRequests.delete(String(requestId));
    window.clearTimeout(pending.timer);
    pending.resolve(rawResult);
  };

  function requestNativeScan() {
    return new Promise(function (resolve, reject) {
      var requestId =
        "scan-" + Date.now() + "-" + Math.random().toString(16).slice(2);
      var timer = window.setTimeout(function () {
        nativeScanRequests.delete(requestId);
        reject(new Error("The Android folder scan timed out."));
      }, 90000);
      nativeScanRequests.set(requestId, {
        resolve:resolve,
        reject:reject,
        timer:timer
      });
      try {
        window.ThorVaultNative.scanGamesAsync(requestId);
      } catch (error) {
        window.clearTimeout(timer);
        nativeScanRequests.delete(requestId);
        reject(error);
      }
    });
  }

  window.thorVaultNativeSaveFingerprintResult = function (requestId, rawResult) {
    var pending = saveFingerprintRequests.get(String(requestId));
    if (!pending) return;
    saveFingerprintRequests.delete(String(requestId));
    window.clearTimeout(pending.timer);
    pending.resolve(rawResult);
  };

  function requestSaveFingerprint(uri) {
    return new Promise(function (resolve, reject) {
      var requestId =
        "save-fingerprint-" + Date.now() + "-" + Math.random().toString(16).slice(2);
      var timer = window.setTimeout(function () {
        saveFingerprintRequests.delete(requestId);
        reject(new Error("Reading the active save fingerprint timed out."));
      }, 30000);
      saveFingerprintRequests.set(requestId, {resolve:resolve, reject:reject, timer:timer});
      try {
        window.ThorVaultNative.inspectSaveFileAsync(requestId, uri);
      } catch (error) {
        window.clearTimeout(timer);
        saveFingerprintRequests.delete(requestId);
        reject(error);
      }
    });
  }

  window.initializeThorVaultAndroid = function () {
    if (androidInitialized || !nativeAvailable() || !window.ThorVaultNative) return false;
    androidInitialized = true;
    window.isAndroidShell = true;
    document.body.classList.add("android-shell", "thorvault-native");
    var badge = document.querySelector("#androidBuildBadge");
    if (badge) {
      badge.classList.remove("hidden");
      badge.textContent = "Android 0.12.22";
    }

  var tabDesign = {
    vault: ["◫", "Vault"],
    oras: ["⇄", "Boxes"],
    games: ["●", "Games"],
    backup: ["☁", "Sync"],
    settings: ["⚙", "Setup"]
  };
  document.querySelectorAll("nav .tab").forEach(function (button) {
    var design = tabDesign[button.getAttribute("data-view")];
    if (!design) return;
    button.setAttribute("aria-label", design[1]);
    button.setAttribute("title", design[1]);
    button.innerHTML =
      '<span class="android-tab-icon" aria-hidden="true">' + design[0] + '</span>' +
      '<span class="visually-hidden">' + design[1] + '</span>';
  });
  var nativeNav = document.querySelector("nav");
  if (nativeNav) {
    nativeNav.style.display = "grid";
    nativeNav.style.gridTemplateColumns = "repeat(5,minmax(0,1fr))";
    nativeNav.style.width = "100vw";
    nativeNav.style.maxWidth = "100vw";
    nativeNav.style.gap = "0";
    nativeNav.style.overflow = "hidden";
  }
  document.querySelectorAll("nav .tab").forEach(function (button) {
    button.style.width = "100%";
    button.style.maxWidth = "none";
    button.style.minWidth = "0";
    button.style.fontSize = "0";
    button.style.padding = ".2rem 0";
  });
  document.querySelectorAll(".web-game-panel").forEach(function (panel) {
    panel.style.display = "none";
  });
  var nativeGamesPanel = document.querySelector("#androidGamePanel");
  if (nativeGamesPanel) nativeGamesPanel.style.display = "block";

  var selectedUri = localStorage.getItem("thorvault-android-active-uri") || null;
  var selectedName = localStorage.getItem("thorvault-android-active-name") || "";
  var selectedGame = localStorage.getItem("thorvault-android-active-game") || "";
  var lastScanResult = null;
  var lastApplyInfo = null;
  var activeSaveSha256 = localStorage.getItem("thorvault-android-active-sha256") || "";
  var activeSaveLastModified = Number(localStorage.getItem("thorvault-android-active-last-modified") || 0);
  var activeSaveChangedExternally = false;

  function parse(raw, fallback) {
    var value;
    try { value = JSON.parse(raw || "{}"); }
    catch (_error) { throw new Error(fallback || "Android returned invalid data."); }
    if (value.ok === false)
      throw new Error(value.error || fallback || "Android operation failed.");
    return value;
  }

  function switchView(viewName) {
    document.querySelectorAll(".tab").forEach(function (tab) {
      tab.classList.toggle("active-tab", tab.getAttribute("data-view") === viewName);
    });
    document.querySelectorAll(".view").forEach(function (view) {
      view.classList.toggle("active-view", view.id === viewName);
    });
    window.scrollTo({top:0, behavior:"smooth"});
  }

  function renderActiveGame(message) {
    var panel = document.querySelector("#androidActiveGame");
    var loaded = {loaded:false, hasWorkingSave:false};
    try {
      if (window.ThorVaultApp)
        loaded = window.ThorVaultApp.getLoadedSaveState();
    } catch (_error) {}

    if (!selectedUri) {
      panel.classList.add("hidden");
      return;
    }
    panel.classList.remove("hidden");
    document.querySelector("#androidActiveGameTitle").textContent =
      selectedGame || loaded.game || selectedName || "Selected Pokémon save";
    document.querySelector("#androidActiveGamePath").textContent =
      selectedName || loaded.filename || selectedUri;
    var defaultState = loaded.loaded
      ? "Loaded and ready in Box Manager."
      : "Selected. Open it while connected to the server.";
    if (activeSaveChangedExternally)
      defaultState = "The emulator save changed on device. Reload is required to see the newest party/boxes.";
    document.querySelector("#androidActiveGameState").textContent =
      (message || defaultState) +
      (activeSaveSha256 ? " · file SHA " + activeSaveSha256.slice(0, 12) + "…" : "");
  }

  function renderFolderDiagnostics(status) {
    var diagnostics = document.querySelector("#androidFolderDiagnosticsText");
    if (!diagnostics) return;
    if (!status) {
      diagnostics.textContent = "No Android folder information yet.";
      return;
    }
    diagnostics.textContent = [
      "Stored URI: " + (status.folderUri || "none"),
      "Folder: " + (status.folderName || "unknown"),
      "Status: " + (status.status || "unknown"),
      "Permission mode: " + (status.permissionMode || "unknown"),
      "Persistent read: " + Boolean(status.persistedRead),
      "Persistent write: " + Boolean(status.persistedWrite),
      "Readable now: " + Boolean(status.readable),
      "Writable now: " + Boolean(status.writable),
      status.error ? "Message: " + status.error : ""
    ].filter(Boolean).join("\n");
  }

  
  function detectedGameLabel(game) {
    var text = String(
      (game && (game.relativePath || game.uri || game.name)) || ""
    ).toLowerCase();

    var ids = [
      ["0011c400","Pokémon Omega Ruby"],
      ["0011c500","Pokémon Alpha Sapphire"],
      ["000400000011c400","Pokémon Omega Ruby"],
      ["000400000011c500","Pokémon Alpha Sapphire"],
      ["0004000000055e00","Pokémon X"],
      ["0004000000055f00","Pokémon Y"],
      ["00040000001b5100","Pokémon Sun"],
      ["00040000001b5000","Pokémon Moon"],
      ["00040000001b5200","Pokémon Ultra Sun"],
      ["00040000001b5300","Pokémon Ultra Moon"]
    ];

    for (var i=0;i<ids.length;i++) {
      if (text.indexOf(ids[i][0]) >= 0) return ids[i][1];
    }

    var lowerName = String((game && (game.name || game.label)) || "").toLowerCase();
    var names = [
      ["omega ruby","Pokémon Omega Ruby"],
      ["alpha sapphire","Pokémon Alpha Sapphire"],
      ["ultra sun","Pokémon Ultra Sun"],
      ["ultra moon","Pokémon Ultra Moon"],
      ["white 2","Pokémon White 2"],
      ["black 2","Pokémon Black 2"],
      ["platinum","Pokémon Platinum"],
      ["heartgold","Pokémon HeartGold"],
      ["soulsilver","Pokémon SoulSilver"]
    ];
    for (var j=0;j<names.length;j++) {
      if (lowerName.indexOf(names[j][0]) >= 0) return names[j][1];
    }
    return null;
  }

function showGames(result) {
    lastScanResult = result || lastScanResult;
    result = result || lastScanResult || {games:[]};
    var status = document.querySelector("#androidFolderStatus");
    var list = document.querySelector("#androidGameList");
    var count = document.querySelector("#androidGameCount");
    var folderStatus = result.folderStatus || result;
    renderFolderDiagnostics(folderStatus);
    var games = (result.games || []).slice().sort(function (a, b) {
      if (a.uri === selectedUri) return -1;
      if (b.uri === selectedUri) return 1;
      return String(a.relativePath || a.name).localeCompare(String(b.relativePath || b.name));
    });
    count.textContent = games.length + " found";

    if (!result.folderUri) {
      status.className = "shared-vault-status warn";
      status.innerHTML =
        "<strong>No folder URI stored.</strong>" +
        "<p class='meta'>" +
        html(result.error || "Choose a folder and approve Android's permission prompt.") +
        "</p>";
      list.innerHTML = "<p class='empty'>No Android save candidates scanned.</p>";
    
      return;
    }

    if (result.error || result.readable === false) {
      status.className = "shared-vault-status bad";
      status.innerHTML =
        "<strong>Folder selected, but unavailable</strong>" +
        "<p class='meta'>" +
        html(result.error || "Android cannot read the stored folder permission.") +
        "</p>";
      list.innerHTML = "<p class='empty'>Fix or forget this folder, then choose it again.</p>";
      renderActiveGame();
      return;
    }

    status.className = "shared-vault-status good";
    status.innerHTML =
      "<strong>Automatic discovery enabled</strong>" +
      "<p class='meta'>" + html(result.folderName || result.folderUri) +
      " · " + (result.persistedRead ? "persistent access" : "current-session access") +
      (result.writable ? " · writable" : " · read-only") +
      (result.visitedCount !== undefined
        ? " · checked " + Number(result.visitedCount) + " items"
        : "") + "</p>";

    if (!games.length) {
      list.innerHTML =
        "<p class='empty'>No main, .sav, .dsv, .dat or .bin candidates were found in this folder.</p>";
      renderActiveGame();
      return;
    }

    list.innerHTML = games.map(function (game) {
      var selected = game.uri === selectedUri;
      return "<article class='android-game-card " + (selected ? "selected" : "") + "'>" +
        "<div><div class='android-game-card-title'><strong>" +
        html(selected && selectedGame
          ? selectedGame
          : (detectedGameLabel(game) || game.label || game.name)) +
        "</strong>" + (selected ? "<span class='android-game-status'>" + (activeSaveChangedExternally ? "Changed" : "Active") + "</span>" : "") +
        "</div><p class='meta'>" + html(detectedGameLabel(game) || game.kind || "Save candidate") +
        " · " + Number(game.size || 0) + " bytes" + (game.lastModified ? " · modified " + new Date(Number(game.lastModified)).toLocaleString() : "") + "</p>" +
        "<code class='meta'>" + html(game.relativePath || game.name) +
        "</code></div><div class='android-game-card-actions'>" +
        "<button class='primary' data-native-uri='" + html(game.uri) +
        "' data-native-name='" + html(game.name) + "'>" +
        (selected ? "Reload" : "Open") + "</button></div></article>";
    }).join("");

    document.querySelectorAll("[data-native-uri]").forEach(function (button) {
      button.onclick = function () {
        var originalText = button.textContent;
        button.disabled = true;
        button.textContent = "Opening…";
        setSaveOpenOverlay(true, "Opening Pokémon save…", button.getAttribute("data-native-name") || "Reading Android save");
        loadSave(button.getAttribute("data-native-uri"), button.getAttribute("data-native-name"))
          .catch(function (error) {
            setSaveOpenOverlay(false);
            renderActiveGame("Open failed: " + error.message);
            setFolderPanel("bad", "Could not open save", error.message);
            window.alert(error.message);
          }).finally(function () {
            button.disabled = false;
            button.textContent = originalText;
          });
      };
    });
    renderActiveGame();
  }

  async function checkActiveSaveForChanges(options) {
    options = options || {};
    if (!selectedUri || !window.ThorVaultNative ||
        typeof window.ThorVaultNative.inspectSaveFileAsync !== "function") return false;

    var raw = await requestSaveFingerprint(selectedUri);
    var fingerprint = parse(raw, "Could not inspect the active Android save.");
    var currentSha = String(fingerprint.sha256 || "");
    if (!currentSha) return false;

    if (!activeSaveSha256) {
      activeSaveSha256 = currentSha;
      activeSaveLastModified = Number(fingerprint.lastModified || 0);
      localStorage.setItem("thorvault-android-active-sha256", activeSaveSha256);
      localStorage.setItem("thorvault-android-active-last-modified", String(activeSaveLastModified));
      return false;
    }

    if (currentSha === activeSaveSha256) {
      activeSaveChangedExternally = false;
      if (options.userInitiated) {
        renderActiveGame(
          "No save-file change detected. If you just progressed in the emulator, use the game's Save command and close/pause the emulator so it flushes the battery save."
        );
      }
      if (lastScanResult) showGames(lastScanResult);
      return false;
    }

    activeSaveChangedExternally = true;
    if (lastScanResult) showGames(lastScanResult);
    var loaded = appApi().getLoadedSaveState();
    var pending = Number(loaded.pendingChanges || 0);
    if (pending > 0) {
      renderActiveGame(
        "The emulator save changed on device, but ThorVault has " + pending +
        " pending Box Manager change(s). Use Reload only if you want to discard those pending changes."
      );
      return true;
    }

    if (options.autoReload !== false) {
      renderActiveGame("New emulator save detected. Reloading from device…");
      await loadSave(selectedUri, selectedName || fingerprint.name || "main", {externalRefresh:true});
      appApi().notify((selectedGame || "Game") + " changed on device and was reloaded.");
      return true;
    }

    renderActiveGame("The emulator save changed on device. Tap Reload to read the newest save.");
    return true;
  }

  var scanInProgress = false;

  async function scan() {
    if (scanInProgress) return null;
    scanInProgress = true;

    var button = document.querySelector("#androidScanGames");
    var activity = document.querySelector("#androidScanActivity");
    var originalText = button ? button.textContent : "Rescan";

    if (button) {
      button.disabled = true;
      button.innerHTML =
        '<span class="mini-spinner" aria-hidden="true"></span> Rescanning…';
    }
    if (activity) activity.classList.remove("hidden");

    setFolderPanel(
      "warn",
      "Scanning granted folder…",
      "Checking emulator save folders, including deeply nested Azahar title data."
    );

    try {
      var raw = await requestNativeScan();
      var result = parse(raw, "Could not scan the granted Android folder.");
      showGames(result);
      await checkActiveSaveForChanges({autoReload:true, userInitiated:true});
      return result;
    } catch (error) {
      setFolderPanel("bad", "Folder scan failed", error.message);
      throw error;
    } finally {
      scanInProgress = false;
      if (button) {
        button.disabled = false;
        button.textContent = originalText || "Rescan";
      }
      if (activity) activity.classList.add("hidden");
    }
  }

  async function loadSave(uri, fallbackName, options) {
    options = options || {};
    var pendingName = fallbackName || "main";

    var file = parse(
      window.ThorVaultNative.readSave(uri),
      "Could not read this Android save."
    );
    pendingName = file.name || fallbackName || "main";

    setSaveOpenOverlay(
      true,
      "Opening Pokémon save…",
      "Uploading " + pendingName + " to " +
        (appApi().getSettings().serverUrl || "the configured server") + "…"
    );

    var result = await appApi().loadAndroidSave(file, uri, fallbackName);

    // Commit the new active selection only after the server has parsed the save
    // and Box Manager has accepted it. A failed switch keeps the previous save active.
    selectedUri = uri;
    selectedName = result.filename || file.name || fallbackName || "main";
    selectedGame = result.game || selectedName;
    activeSaveSha256 = String(file.sha256 || "");
    activeSaveLastModified = Number(file.lastModified || 0);
    activeSaveChangedExternally = false;

    localStorage.setItem("thorvault-android-active-uri", selectedUri);
    localStorage.setItem("thorvault-android-active-name", selectedName);
    localStorage.setItem("thorvault-android-active-game", selectedGame);
    localStorage.setItem("thorvault-android-active-sha256", activeSaveSha256);
    localStorage.setItem("thorvault-android-active-last-modified", String(activeSaveLastModified));
    try { await window.ThorVaultApp.persistCurrentSaveSession(); }
    catch (sessionError) { console.warn("Could not persist Android save identity for offline reopen.", sessionError); }

    // Re-render the cached discovery result immediately so only the newly
    // opened file receives the Active badge and Reload button.
    if (lastScanResult) showGames(lastScanResult);
    renderActiveGame("Loaded and ready in Box Manager.");
    refreshLastApplyInfo();

    setSaveOpenOverlay(false);
    appApi().openBoxManager();
    appApi().notify(
      selectedGame +
      " opened. Move Pokémon between the game and Vault boxes."
    );
  }


  function shortHash(value) {
    value = String(value || "");
    return value ? value.slice(0, 12) + "…" : "not available";
  }

  function formatBytes(value) {
    var bytes = Number(value || 0);
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KiB";
    return (bytes / (1024 * 1024)).toFixed(2) + " MiB";
  }

  function refreshLastApplyInfo() {
    var restoreButton = document.querySelector("#androidRestoreLastBackup");
    if (!restoreButton ||
        !window.ThorVaultNative ||
        typeof window.ThorVaultNative.getLastApplyInfo !== "function") return;

    try {
      var info = parse(
        window.ThorVaultNative.getLastApplyInfo(),
        "Could not inspect the latest device backup."
      );
      lastApplyInfo = info.present ? info : null;
      var matchesActive =
        lastApplyInfo &&
        lastApplyInfo.targetUri === selectedUri &&
        lastApplyInfo.backupReadable !== false;
      restoreButton.classList.toggle("hidden", !matchesActive);
      if (matchesActive) {
        restoreButton.textContent =
          "Restore backup · " + (lastApplyInfo.backupName || "latest");
      }
    } catch (error) {
      lastApplyInfo = null;
      restoreButton.classList.add("hidden");
      console.warn("Could not refresh last Apply backup", error);
    }
  }

  function showApplyResult(result, mode) {
    var dialog = document.querySelector("#androidApplyResultDialog");
    var title = document.querySelector("#androidApplyResultTitle");
    var body = document.querySelector("#androidApplyResultBody");
    var restoreButton = document.querySelector("#androidApplyResultRestore");

    if (!dialog || !title || !body) return;

    if (mode === "restore") {
      title.textContent = "Original backup restored";
      body.innerHTML =
        "<div class='apply-result-success'><strong>Restore verified</strong>" +
        "<p class='meta'>The recorded original backup was written back to the active save and verified.</p></div>" +
        "<div class='apply-result-grid'>" +
          "<div class='apply-result-card'><span class='meta'>Restored file</span><strong>" +
            html(result.targetName || selectedName || "save") + "</strong></div>" +
          "<div class='apply-result-card'><span class='meta'>Restored SHA-256</span><code>" +
            html(result.restoredSha256 || "") + "</code></div>" +
          "<div class='apply-result-card'><span class='meta'>Source backup</span><strong>" +
            html(result.restoredBackupName || "") + "</strong></div>" +
          "<div class='apply-result-card'><span class='meta'>Safety copy of replaced save</span><strong>" +
            html(result.preRestoreBackupName || "") + "</strong></div>" +
        "</div>";
      if (restoreButton) restoreButton.classList.add("hidden");
    } else {
      title.textContent = "Save applied and verified";
      body.innerHTML =
        "<div class='apply-result-success'><strong>Safe write completed</strong>" +
        "<p class='meta'>ThorVault verified the backup before writing, then verified the save after writing.</p></div>" +
        "<div class='apply-result-grid'>" +
          "<div class='apply-result-card'><span class='meta'>Device save</span><strong>" +
            html(result.targetName || selectedName || "save") + "</strong><span class='meta'>" +
            html(formatBytes(result.byteLength)) + "</span></div>" +
          "<div class='apply-result-card'><span class='meta'>Backup created</span><strong>" +
            html(result.backupName || "") + "</strong></div>" +
          "<div class='apply-result-card'><span class='meta'>Original SHA-256</span><code>" +
            html(result.originalSha256 || "") + "</code></div>" +
          "<div class='apply-result-card'><span class='meta'>Written SHA-256</span><code>" +
            html(result.writtenSha256 || "") + "</code></div>" +
        "</div>" +
        "<p class='meta'>Close the emulator before Apply. After this result, reopen the emulator and verify the Pokémon in-game.</p>";
      if (restoreButton) restoreButton.classList.remove("hidden");
    }

    if (typeof dialog.showModal === "function") dialog.showModal();
    else dialog.setAttribute("open", "open");
  }

  function closeApplyResult() {
    var dialog = document.querySelector("#androidApplyResultDialog");
    if (!dialog) return;
    if (typeof dialog.close === "function") {
      try { dialog.close(); return; } catch (_error) {}
    }
    dialog.removeAttribute("open");
  }

  function restoreLastBackup() {
    refreshLastApplyInfo();
    if (!lastApplyInfo || lastApplyInfo.targetUri !== selectedUri)
      throw new Error("No verified backup is recorded for the active save.");

    if (!window.confirm(
      "Restore the original save from " + lastApplyInfo.backupName +
      "? ThorVault will first back up the save currently on the device, then verify the restored SHA-256."
    )) return;

    renderActiveGame("Creating a pre-restore safety copy and restoring the original save…");
    var result = parse(
      window.ThorVaultNative.restoreLastAppliedBackup(),
      "Android could not restore the device backup."
    );
    renderActiveGame(
      "Original backup restored and verified. Safety copy: " +
      result.preRestoreBackupName
    );
    refreshLastApplyInfo();
    showApplyResult(result, "restore");
    appApi().notify("Original device save restored and verified.");
  }

  function apply() {
    var workingSave = appApi().getWorkingSaveBase64();
    if (!selectedUri || !workingSave)
      throw new Error("Load a discovered save and generate a working save first.");

    if (!window.confirm(
      "Close the emulator before continuing.\n\n" +
      "ThorVault will:\n" +
      "1. create and verify an original-save backup,\n" +
      "2. write the working save,\n" +
      "3. read it back and verify SHA-256.\n\n" +
      "Continue?"
    )) return;

    var applyButton = document.querySelector("#androidApplyFromManager");
    var originalText = applyButton ? applyButton.textContent : "";
    if (applyButton) {
      applyButton.disabled = true;
      applyButton.textContent = "Backing up & applying…";
    }

    try {
      renderActiveGame("Verifying backup, writing save and checking SHA-256…");
      var result = parse(
        window.ThorVaultNative.applySave(selectedUri, workingSave),
        "Android could not back up and apply the save."
      );
      renderActiveGame(
        "Applied and verified. Backup: " + result.backupName +
        " · SHA " + shortHash(result.writtenSha256)
      );
      refreshLastApplyInfo();
      showApplyResult(result, "apply");
      appApi().notify("Save applied, backed up and verified.");
    } finally {
      if (applyButton) {
        applyButton.disabled = false;
        applyButton.textContent = originalText || "Back up & Apply to device";
      }
    }
  }

  window.thorVaultNativeFolderChanged = function (rawStatus) {
    try {
      if (rawStatus) {
        var status = typeof rawStatus === "string" ? JSON.parse(rawStatus) : rawStatus;
        renderFolderDiagnostics(status);
        if (status.folderUri && status.readable) {
          setFolderPanel(
            "good",
            "Folder permission stored",
            (status.folderName || "Selected folder") +
              (status.writable ? " · writable" : " · read-only")
          );
        } else if (!status.folderUri) {
          setFolderPanel("warn", "No folder URI stored", status.error || "Choose a folder.");
        }
      }
      scan().catch(function (error) {
        console.warn("Folder rescan after permission change failed", error);
      });
    } catch (error) {
      setFolderPanel("bad", "Folder permission update failed", error.message);
      window.alert("Folder permission update failed: " + error.message);
    }
  };

  document.querySelector("#androidGamePanel").classList.remove("hidden");

  var gamesTab = document.querySelector('.tab[data-view="games"]');
  if (gamesTab) {
    gamesTab.addEventListener("click", function () {
      if (lastScanResult) showGames(lastScanResult);
      renderActiveGame();
    });
  }
  document.querySelector("#androidGrantFolder").onclick = function () {
    window.ThorVaultNative.chooseGameFolder();
  };
  document.querySelector("#androidScanGames").onclick = function () {
    scan().catch(function (error) {
      window.alert(error.message);
    });
  };
  document.querySelector("#androidForgetFolder").onclick = function () {
    if (!window.confirm("Forget the currently granted Android folder?")) return;
    try {
      parse(
        window.ThorVaultNative.forgetGameFolder(),
        "Could not forget the folder."
      );
      setFolderPanel(
        "warn",
        "Folder forgotten",
        "Choose a folder again to re-enable automatic discovery."
      );
      renderFolderDiagnostics({
        folderUri:null,
        folderName:null,
        status:"none",
        permissionMode:"none",
        persistedRead:false,
        persistedWrite:false,
        readable:false,
        writable:false
      });
      document.querySelector("#androidGameList").innerHTML =
        "<p class='empty'>No Android save candidates scanned.</p>";
      document.querySelector("#androidGameCount").textContent = "0 found";
      window.setTimeout(function () {
        scan().catch(function (error) {
          console.warn("Post-forget scan failed", error);
        });
      }, 50);
    } catch (error) {
      setFolderPanel("bad", "Could not forget folder", error.message);
      window.alert(error.message);
    }
  };

  var applyFromManagerButton = document.querySelector("#androidApplyFromManager");
  if (applyFromManagerButton) {
    applyFromManagerButton.onclick = function () {
      try { apply(); }
      catch (error) {
        renderActiveGame("Apply failed: " + error.message);
        window.alert("Apply failed: " + error.message);
      }
    };
  }

  var restoreLastBackupButton = document.querySelector("#androidRestoreLastBackup");
  if (restoreLastBackupButton) {
    restoreLastBackupButton.onclick = function () {
      try { restoreLastBackup(); }
      catch (error) {
        renderActiveGame("Restore failed: " + error.message);
        window.alert("Restore failed: " + error.message);
      }
    };
  }

  var applyResultDone = document.querySelector("#androidApplyResultDone");
  if (applyResultDone) applyResultDone.onclick = closeApplyResult;
  var closeApplyResultButton = document.querySelector("#closeAndroidApplyResult");
  if (closeApplyResultButton) closeApplyResultButton.onclick = closeApplyResult;
  var applyResultRestore = document.querySelector("#androidApplyResultRestore");
  if (applyResultRestore) {
    applyResultRestore.onclick = function () {
      closeApplyResult();
      try { restoreLastBackup(); }
      catch (error) {
        renderActiveGame("Restore failed: " + error.message);
        window.alert("Restore failed: " + error.message);
      }
    };
  }

  window.addEventListener("thorvaultappresumed", function () {
    checkActiveSaveForChanges({autoReload:true, userInitiated:false})
      .catch(function (error) { console.warn("Active save refresh on resume failed", error); });
  });

  renderActiveGame();
  try {
    renderFolderDiagnostics(parse(
      window.ThorVaultNative.getFolderStatus(),
      "Could not read Android folder status."
    ));
    scan().catch(function (error) {
      console.warn("Initial Android scan failed", error);
    });
  }
  catch (error) { console.warn("Initial Android setup failed", error); }
    return true;
  };

  function attemptAndroidInitialization() {
    if (window.initializeThorVaultAndroid()) return;
    window.setTimeout(function () {
      if (!androidInitialized) window.initializeThorVaultAndroid();
    }, 250);
    window.setTimeout(function () {
      if (!androidInitialized) window.initializeThorVaultAndroid();
    }, 1000);
  }

  if (document.readyState === "loading")
    document.addEventListener("DOMContentLoaded", attemptAndroidInitialization, {once:true});
  else
    attemptAndroidInitialization();
})();
