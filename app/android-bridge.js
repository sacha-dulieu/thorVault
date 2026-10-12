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
  var activeScanRequestId = null;
  var scanLocationUris = [];
  var scanLocationsStatus = [];
  var scanInProgress = false;

  window.thorVaultNativeScanResult = function (requestId, rawResult) {
    var pending = nativeScanRequests.get(String(requestId));
    if (!pending) return;
    nativeScanRequests.delete(String(requestId));
    window.clearTimeout(pending.timer);
    if (activeScanRequestId === String(requestId)) activeScanRequestId = null;
    pending.resolve(rawResult);
  };

  window.thorVaultNativeScanProgress = function (requestId, rawResult) {
    var pending = nativeScanRequests.get(String(requestId));
    if (!pending) return;
    try {
      pending.lastResult = JSON.parse(rawResult || "{}");
      if (typeof pending.onProgress === "function") pending.onProgress(pending.lastResult);
    } catch (error) { console.warn("Invalid ThorVault scan progress update.", error); }
    armScanTimeout(String(requestId), pending);
  };

  function armScanTimeout(requestId, pending) {
    window.clearTimeout(pending.timer);
    pending.timer = window.setTimeout(function () {
      if (!nativeScanRequests.has(requestId)) return;
      nativeScanRequests.delete(requestId);
      if (activeScanRequestId === requestId) activeScanRequestId = null;
      try {
        if (window.ThorVaultNative && typeof window.ThorVaultNative.cancelGamesScan === "function")
          window.ThorVaultNative.cancelGamesScan(requestId);
      } catch (_error) {}
      var error = new Error("No scan progress was received for 2 minutes. Partial results have been retained; try scanning fewer or smaller folders.");
      error.partialResult = pending.lastResult || null;
      pending.reject(error);
    }, 120000);
  }

  function requestNativeScan(selectedUris, onProgress) {
    return new Promise(function (resolve, reject) {
      var requestId = "scan-" + Date.now() + "-" + Math.random().toString(16).slice(2);
      var pending = {resolve:resolve, reject:reject, timer:null, lastResult:null, onProgress:onProgress};
      activeScanRequestId = requestId;
      nativeScanRequests.set(requestId, pending);
      armScanTimeout(requestId, pending);
      try { window.ThorVaultNative.scanGamesAsync(requestId, JSON.stringify(selectedUris || [])); }
      catch (error) {
        window.clearTimeout(pending.timer);
        nativeScanRequests.delete(requestId);
        activeScanRequestId = null;
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
  var lastScanCacheAt = 0;
  try {
    var cachedScan = JSON.parse(localStorage.getItem("thorvault-android-scan-results-v1") || "null");
    if (cachedScan && Array.isArray(cachedScan.games)) lastScanResult = cachedScan;
  } catch (_error) { lastScanResult = null; }

  function cacheScanSnapshot(result, force) {
    if (!result || !Array.isArray(result.games)) return;
    var now = Date.now();
    if (!force && now - lastScanCacheAt < 2500) return;
    try {
      localStorage.setItem("thorvault-android-scan-results-v1", JSON.stringify(result));
      lastScanCacheAt = now;
    } catch (error) {
      console.warn("ThorVault could not cache scan results; current-session results remain available.", error);
    }
  }
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

  window.thorVaultHandleShoulderTab = function (direction) {
    var tabs = Array.prototype.slice.call(document.querySelectorAll("nav .tab"));
    if (!tabs.length) return;
    var current = tabs.findIndex(function (tab) { return tab.classList.contains("active-tab"); });
    if (current < 0) current = 0;
    var delta = direction === "previous" ? -1 : 1;
    var next = (current + delta + tabs.length) % tabs.length;
    tabs[next].click();
    try { tabs[next].focus({preventScroll:true}); } catch (_error) { tabs[next].focus(); }
  };

  window.thorVaultMoveControllerFocus = function (direction) {
    var modal = document.querySelector("dialog[open]");
    var view = document.querySelector(".view.active-view");
    var selectors = "button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex='0']";
    var candidates = [];
    if (!modal) candidates = candidates.concat(Array.prototype.slice.call(document.querySelectorAll("nav .tab")));
    if (modal) candidates = candidates.concat(Array.prototype.slice.call(modal.querySelectorAll(selectors)));
    else if (view) candidates = candidates.concat(Array.prototype.slice.call(view.querySelectorAll(selectors)));
    candidates = candidates.filter(function (element, index, all) {
      if (all.indexOf(element) !== index) return false;
      var rect = element.getBoundingClientRect();
      var style = window.getComputedStyle(element);
      return rect.width > 0 && rect.height > 0 && style.display !== "none" && style.visibility !== "hidden";
    });
    if (!candidates.length) return;
    var current = document.activeElement;
    var currentIndex = candidates.indexOf(current);
    if (currentIndex < 0) {
      var first = candidates[0];
      try { first.focus({preventScroll:true}); } catch (_error) { first.focus(); }
      if (typeof first.scrollIntoView === "function") first.scrollIntoView({block:"nearest", inline:"nearest"});
      return;
    }
    var rect = current.getBoundingClientRect();
    var cx = rect.left + rect.width / 2;
    var cy = rect.top + rect.height / 2;
    var best = null;
    var bestScore = Infinity;
    candidates.forEach(function (candidate) {
      if (candidate === current) return;
      var r = candidate.getBoundingClientRect();
      var x = r.left + r.width / 2;
      var y = r.top + r.height / 2;
      var primary, secondary;
      if (direction === "left") { if (x >= cx - 1) return; primary = cx - x; secondary = Math.abs(cy - y); }
      else if (direction === "right") { if (x <= cx + 1) return; primary = x - cx; secondary = Math.abs(cy - y); }
      else if (direction === "up") { if (y >= cy - 1) return; primary = cy - y; secondary = Math.abs(cx - x); }
      else { if (y <= cy + 1) return; primary = y - cy; secondary = Math.abs(cx - x); }
      var score = primary + secondary * 2.25;
      if (score < bestScore) { bestScore = score; best = candidate; }
    });
    if (!best) {
      // Wrap at the edge to the furthest visible control in the requested direction.
      candidates.forEach(function (candidate) {
        if (candidate === current) return;
        var r = candidate.getBoundingClientRect();
        var x = r.left + r.width / 2;
        var y = r.top + r.height / 2;
        var score = direction === "left" ? x : direction === "right" ? -x : direction === "up" ? y : -y;
        if (score < bestScore) { bestScore = score; best = candidate; }
      });
    }
    if (best) {
      try { best.focus({preventScroll:true}); } catch (_error) { best.focus(); }
      if (typeof best.scrollIntoView === "function") best.scrollIntoView({block:"nearest", inline:"nearest"});
    }
  };

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
      : "Selected. Open it to parse locally with the bundled Pokémon engine.";
    if (activeSaveChangedExternally)
      defaultState = "The emulator save changed on device. Reload is required to see the newest party/boxes.";
    document.querySelector("#androidActiveGameState").textContent =
      (message || defaultState) +
      (activeSaveSha256 ? " · file SHA " + activeSaveSha256.slice(0, 12) + "…" : "");
  }

  function renderFolderDiagnostics(status) {
    var diagnostics = document.querySelector("#androidFolderDiagnosticsText");
    if (!diagnostics) return;
    if (!status) { diagnostics.textContent = "No Android folder information yet."; return; }
    var locations = Array.isArray(status.locations) ? status.locations : [];
    if (locations.length) {
      diagnostics.textContent = "Stored scan locations: " + locations.length + "\n\n" + locations.map(function (location, index) {
        return (index + 1) + ". " + (location.folderName || "Unknown folder") +
          "\n   URI: " + (location.uri || location.folderUri || "none") +
          "\n   Status: " + (location.status || "unknown") +
          "\n   Permission: " + (location.permissionMode || "unknown") +
          "\n   Persistent read: " + Boolean(location.persistedRead) +
          "\n   Persistent write: " + Boolean(location.persistedWrite) +
          "\n   Readable: " + Boolean(location.readable) +
          "\n   Writable: " + Boolean(location.writable);
      }).join("\n\n") + (status.error ? "\n\nMessage: " + status.error : "");
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

  function parseStoredScanUris() {
    try { return JSON.parse(localStorage.getItem("thorvault-android-scan-location-uris") || "[]"); }
    catch (_error) { return []; }
  }

  function selectedLocationUris() {
    if (localStorage.getItem("thorvault-android-scan-locations-configured") !== "1") return scanLocationUris.slice();
    return parseStoredScanUris().filter(function (uri) { return scanLocationUris.indexOf(uri) >= 0; });
  }

  function persistLocationSelection(uris) {
    localStorage.setItem("thorvault-android-scan-locations-configured", "1");
    localStorage.setItem("thorvault-android-scan-location-uris", JSON.stringify(uris));
    updateLocationSelectionSummary();
  }

  function updateLocationSelectionSummary() {
    var summary = document.querySelector("#androidScanLocationSummary");
    if (summary) summary.textContent = selectedLocationUris().length + " of " + scanLocationUris.length + " locations selected for scanning.";
  }

  function renderScanLocations(status) {
    var list = document.querySelector("#androidFolderLocations");
    if (!list) return;
    var locations = (status && status.locations) || [];
    if (!locations.length && status && status.folderUri && status.folderUri !== "null") {
      locations = [{uri:status.folderUri, folderUri:status.folderUri, folderName:status.folderName,
        readable:status.readable, writable:status.writable, persistedRead:status.persistedRead,
        persistedWrite:status.persistedWrite, permissionMode:status.permissionMode}];
    }
    var previousUris = [];
    try { previousUris = JSON.parse(localStorage.getItem("thorvault-android-scan-known-uris") || "[]"); }
    catch (_error) { previousUris = scanLocationUris.slice(); }
    scanLocationsStatus = locations;
    scanLocationUris = locations.map(function (location) { return String(location.uri || location.folderUri || ""); }).filter(Boolean);
    var configured = localStorage.getItem("thorvault-android-scan-locations-configured") === "1";
    var selected = configured ? parseStoredScanUris().filter(function (uri) { return scanLocationUris.indexOf(uri) >= 0; }) : scanLocationUris.slice();
    if (configured) scanLocationUris.forEach(function (uri) {
      if (previousUris.indexOf(uri) < 0 && selected.indexOf(uri) < 0) selected.push(uri);
    });
    localStorage.setItem("thorvault-android-scan-location-uris", JSON.stringify(selected));
    localStorage.setItem("thorvault-android-scan-known-uris", JSON.stringify(scanLocationUris));

    if (!locations.length) {
      list.innerHTML = "<p class='empty'>No scan locations yet. Add the ROMs folder and any emulator save folders.</p>";
      updateLocationSelectionSummary();
      return;
    }
    list.innerHTML = locations.map(function (location, index) {
      var uri = String(location.uri || location.folderUri || "");
      var safeId = "scan-location-" + index;
      var readable = Boolean(location.readable);
      return "<div class='android-location-row'><label class='android-location-select' for='" + safeId +
        "'><input type='checkbox' class='android-location-checkbox' id='" + safeId + "' data-scan-location='" + html(uri) + "' " +
        (selected.indexOf(uri) >= 0 ? "checked " : "") + (readable ? "" : "disabled ") + "/>" +
        "<span><strong>" + html(location.folderName || location.name || "Selected folder") + "</strong><small>" +
        (readable ? (location.writable ? "Readable and writable" : "Read-only") : "Unavailable — reselect this folder") +
        " · " + (location.persistedRead ? "remembered permission" : "temporary permission") +
        "</small></span></label><button type='button' class='android-location-remove' data-remove-location='" + html(uri) + "'>Remove</button></div>";
    }).join("");
    document.querySelectorAll("[data-scan-location]").forEach(function (checkbox) {
      checkbox.onchange = function () {
        var uri = checkbox.getAttribute("data-scan-location");
        var current = selectedLocationUris().filter(function (value) { return value !== uri; });
        if (checkbox.checked) current.push(uri);
        persistLocationSelection(current);
      };
    });
    document.querySelectorAll("[data-remove-location]").forEach(function (button) {
      button.onclick = function () {
        var uri = button.getAttribute("data-remove-location");
        var location = scanLocationsStatus.find(function (entry) { return String(entry.uri || entry.folderUri || "") === uri; });
        if (!window.confirm("Remove " + ((location && location.folderName) || "this scan location") + " from ThorVault? Existing files will not be deleted.")) return;
        try {
          parse(window.ThorVaultNative.forgetGameLocation(uri), "Could not remove the scan location.");
          persistLocationSelection(selectedLocationUris().filter(function (value) { return value !== uri; }));
          var fresh = parse(window.ThorVaultNative.getFolderStatus(), "Could not refresh scan locations.");
          renderScanLocations(fresh);
          renderFolderDiagnostics(fresh);
          if (lastScanResult && Array.isArray(lastScanResult.games)) {
            lastScanResult.games = lastScanResult.games.filter(function (game) { return String(game.treeUri || "") !== uri; });
            lastScanResult.folderStatus = fresh;
            lastScanResult.locations = fresh.locations || [];
            cacheScanSnapshot(lastScanResult, true);
            showGames(lastScanResult);
          }
          setFolderPanel("good", "Scan location removed", "ThorVault forgot this folder permission. No save, backup, or ROM files were deleted.");
        } catch (error) { window.alert(error.message); }
      };
    });
    updateLocationSelectionSummary();
  }

  function renderScanProgress(result) {
    var activity = document.querySelector("#androidScanActivity");
    var label = document.querySelector("#androidScanProgressLabel");
    var meta = document.querySelector("#androidScanProgressMeta");
    var progress = document.querySelector("#androidScanProgressBar");
    var completed = (result.completedUris || []).length;
    var total = Number(result.locationCount || (result.selectedUris || []).length || 0);
    if (label) label.textContent = result.currentLocation ? "Scanning " + result.currentLocation : "Preparing scan…";
    if (meta) meta.textContent = "Folder " + Math.min(Number(result.locationIndex || 1), total || 1) + " of " + total +
      " · " + Number(result.visitedCount || 0).toLocaleString() + " entries checked · " + (result.games || []).length +
      " candidates found · " + completed + " folders completed";
    if (progress) {
      progress.max = Math.max(1, total);
      if (result.done) progress.value = total ? Math.min(completed, total) : 1;
      else progress.removeAttribute("value");
      progress.setAttribute("aria-valuetext", completed + " of " + total + " folders completed");
    }
    if (activity) activity.classList.remove("hidden");
  }

  function mergedScanSnapshot(incoming, finalResult) {
    incoming = incoming || {games:[]};
    var previous = lastScanResult && Array.isArray(lastScanResult.games) ? lastScanResult.games : [];
    var completed = new Set(finalResult ? (incoming.completedUris || []) : []);
    var scanned = new Set(finalResult ? (incoming.scannedUris || []) : []);
    var selected = new Set(incoming.selectedUris || []);
    var map = new Map();
    previous.forEach(function (game, index) {
      var treeUri = String(game.treeUri || "");
      if (!finalResult || !completed.has(treeUri)) {
        var retained = Object.assign({}, game);
        if (finalResult && !selected.has(treeUri)) retained.scanStatus = "not-rescanned";
        else if (finalResult && scanned.has(treeUri) && !completed.has(treeUri)) retained.scanStatus = "previous-result-retained";
        map.set(String(game.uri || game.displayPath || ("cached-" + index)), retained);
      }
    });
    (incoming.games || []).forEach(function (game, index) {
      map.set(String(game.uri || game.displayPath || ("new-" + index)), game);
    });
    var merged = Object.assign({}, lastScanResult || {}, incoming, {games:Array.from(map.values())});
    var hashes = Object.create(null);
    merged.games.forEach(function (game) { if (game.sha256) hashes[game.sha256] = (hashes[game.sha256] || 0) + 1; });
    merged.duplicateGroups = Object.keys(hashes).filter(function (hash) { return hashes[hash] > 1; }).length;
    return merged;
  }

  function finishScanStatus(result, merged) {
    var errors = (result.errors || []).length;
    var partials = (result.partialUris || []).length;
    var note = Number(result.visitedCount || 0).toLocaleString() + " entries checked across " + (result.scannedUris || []).length +
      " selected location(s); " + (merged.games || []).length + " save candidates retained.";
    if (merged.duplicateGroups) note += " " + merged.duplicateGroups + " exact duplicate group(s) found; none were deleted.";
    if (result.scanLimitReached) note += " A safety limit was reached; partial results were retained. Select a smaller folder or fewer locations.";
    if (result.cancelled) note += " Scan cancelled; partial results were retained.";
    if (errors) note += " " + errors + " location(s) could not be scanned; previous results were retained.";
    if (partials && !result.cancelled) note += " " + partials + " location(s) have partial results.";
    var problem = errors || partials || result.scanLimitReached || result.cancelled || scanLocationsStatus.some(function (location) { return !location.readable; });
    setFolderPanel(problem ? "warn" : "good", result.cancelled ? "Scan cancelled" : (problem ? "Scan completed with partial results" : "Scan complete"), note);
  }

  function getDuplicateCounts(games) {
    var counts = Object.create(null);
    (games || []).forEach(function (game) { if (game.sha256) counts[game.sha256] = (counts[game.sha256] || 0) + 1; });
    return counts;
  }

  function requestCancelScan() {
    if (!activeScanRequestId || !window.ThorVaultNative || typeof window.ThorVaultNative.cancelGamesScan !== "function") return;
    window.ThorVaultNative.cancelGamesScan(activeScanRequestId);
    var button = document.querySelector("#androidCancelScan");
    if (button) { button.disabled = true; button.textContent = "Stopping…"; }
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
    var locations = folderStatus.locations || result.locations || [];
    var games = (result.games || []).slice().sort(function (a, b) {
      if (a.uri === selectedUri) return -1;
      if (b.uri === selectedUri) return 1;
      return String(a.displayPath || a.relativePath || a.name).localeCompare(String(b.displayPath || b.relativePath || b.name));
    });
    var duplicateCounts = getDuplicateCounts(games);
    var duplicateGroups = Object.keys(duplicateCounts).filter(function (hash) { return duplicateCounts[hash] > 1; }).length;
    count.textContent = games.length + " found" + (duplicateGroups ? " · " + duplicateGroups + " duplicate group(s)" : "");
    if (!locations.length && !result.folderUri) {
      status.className = "shared-vault-status warn";
      status.innerHTML = "<strong>No scan locations saved.</strong><p class='meta'>Add the ROMs folder and any emulator save folders, then choose which locations to scan.</p>";
      list.innerHTML = "<p class='empty'>No Android save candidates scanned.</p>";
      return;
    }
    var readableCount = locations.filter(function (location) { return Boolean(location.readable); }).length;
    if (!scanInProgress && (result.done || result.error)) {
      if (result.error && !games.length) {
        status.className = "shared-vault-status warn";
        status.innerHTML = "<strong>Scan needs attention</strong><p class='meta'>" + html(result.error) + "</p>";
      } else {
        status.className = "shared-vault-status good";
        status.innerHTML = "<strong>" + html(readableCount + " of " + locations.length + " scan location(s) available") + "</strong><p class='meta'>" +
          html(duplicateGroups + " exact duplicate group(s) detected. ThorVault keeps all copies until you decide what to do.") + "</p>";
      }
    }
    if (!games.length) {
      list.innerHTML = "<p class='empty'>No save candidates found in the completed scan yet. Check other emulator save directories or Android folder permissions.</p>";
      renderActiveGame();
      return;
    }
    list.innerHTML = games.map(function (game) {
      var selected = game.uri === selectedUri;
      var duplicates = game.sha256 ? Number(duplicateCounts[game.sha256] || 0) : 0;
      var gameName = game.detectedGame && game.detectedGame !== "Unidentified Pokémon save candidate"
        ? game.detectedGame : (detectedGameLabel(game) || game.label || game.name || "Save candidate");
      var details = [game.system || "Unknown system", game.emulator || "Unknown emulator", game.confidence ? game.confidence + " confidence" : "candidate", Number(game.size || 0).toLocaleString() + " bytes"];
      var displayPath = game.displayPath || ((game.rootName ? game.rootName + "/" : "") + (game.relativePath || game.name || ""));
      var duplicateNotice = duplicates > 1
        ? "<p class='android-duplicate-warning'>Exact content duplicate · " + duplicates + " copies detected. No files were deleted.</p>"
        : (game.fingerprintStatus === "skipped-size-or-budget" ? "<p class='meta'>Content fingerprint skipped for this file; duplicates may not be detected.</p>" : "");
      if (game.scanStatus === "not-rescanned") duplicateNotice += "<p class='meta'>Kept from an earlier scan · this folder was not selected this run.</p>";
      else if (game.scanStatus === "previous-result-retained") duplicateNotice += "<p class='meta'>Earlier result retained · this folder was not fully scanned this run.</p>";
      return "<article class='android-game-card " + (selected ? "selected" : "") + "'><div><div class='android-game-card-title'><strong>" + html(gameName) +
        "</strong>" + (selected ? "<span class='android-game-status'>" + (activeSaveChangedExternally ? "Changed" : "Active") + "</span>" : "") +
        "</div><p class='meta'>" + html(details.join(" · ")) + (game.lastModified ? " · modified " + new Date(Number(game.lastModified)).toLocaleString() : "") + "</p>" +
        "<code class='meta'>" + html(displayPath) + "</code>" + duplicateNotice + "</div><div class='android-game-card-actions'><button class='primary' data-native-uri='" + html(game.uri) +
        "' data-native-name='" + html(game.name) + "'>" + (selected ? "Reload" : "Open") + "</button></div></article>";
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
          }).finally(function () { button.disabled = false; button.textContent = originalText; });
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

  async function scan() {
    if (scanInProgress) return null;
    scanInProgress = true;
    var button = document.querySelector("#androidScanGames");
    var activity = document.querySelector("#androidScanActivity");
    var cancelButton = document.querySelector("#androidCancelScan");
    var originalText = button ? button.textContent : "Scan selected folders";
    var selectedUris = selectedLocationUris();
    if (!selectedUris.length) {
      scanInProgress = false;
      setFolderPanel("warn", "No scan locations selected", "Tick at least one remembered folder above, then scan again.");
      if (activity) activity.classList.add("hidden");
      return null;
    }
    if (button) { button.disabled = true; button.innerHTML = '<span class="mini-spinner" aria-hidden="true"></span> Scanning…'; }
    if (cancelButton) { cancelButton.disabled = false; cancelButton.textContent = "Cancel scan"; }
    if (activity) activity.classList.remove("hidden");
    setFolderPanel("warn", "Scanning selected locations…", "Progress updates and any discovered partial results will be kept if you cancel or a folder reaches a safety limit.");
    try {
      var lastScanUiRenderAt = 0;
      var raw = await requestNativeScan(selectedUris, function (progress) {
        renderScanProgress(progress);
        var partial = mergedScanSnapshot(progress, false);
        lastScanResult = partial;
        cacheScanSnapshot(partial, false);
        if (Date.now() - lastScanUiRenderAt >= 2000) {
          showGames(partial);
          lastScanUiRenderAt = Date.now();
        }
        setFolderPanel("warn", "Scanning " + (progress.currentLocation || "selected folders") + "…",
          Number(progress.visitedCount || 0).toLocaleString() + " entries checked · " + partial.games.length + " candidates found. Results are available while scanning.");
      });
      var result = parse(raw, "Could not scan the selected Android folders.");
      var merged = mergedScanSnapshot(result, true);
      lastScanResult = merged;
      cacheScanSnapshot(merged, true);
      showGames(merged);
      finishScanStatus(result, merged);
      await checkActiveSaveForChanges({autoReload:true, userInitiated:true});
      return merged;
    } catch (error) {
      if (error.partialResult) {
        var partialResult = mergedScanSnapshot(error.partialResult, false);
        lastScanResult = partialResult;
        cacheScanSnapshot(partialResult, true);
        showGames(partialResult);
      }
      setFolderPanel("warn", "Scan stopped; partial results retained", error.message);
      throw error;
    } finally {
      scanInProgress = false;
      if (button) { button.disabled = false; button.textContent = originalText || "Scan selected folders"; }
      if (cancelButton) { cancelButton.disabled = false; cancelButton.textContent = "Cancel scan"; }
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
      "Reading " + pendingName + " locally with the bundled Pokémon engine…"
    );

    var result = await appApi().loadAndroidSave(file, uri, fallbackName);

    // Commit the new active selection only after local parsing and Box Manager accept it.
    // A failed switch keeps the previous save active.
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
      refreshLastApplyInfo();
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
        renderScanLocations(status);
        if (status.locations && status.locations.length) {
          setFolderPanel("good", "Scan locations remembered", status.locations.length + " folder(s) saved. Choose the locations, then tap Scan selected folders when ready.");
        } else if (!status.folderUri) setFolderPanel("warn", "No scan locations saved", status.error || "Add a folder to begin.");
      }
    } catch (error) {
      setFolderPanel("bad", "Folder permission update failed", error.message);
      window.alert("Folder permission update failed: " + error.message);
    }
  };

  document.querySelector("#androidGamePanel").classList.remove("hidden");
  var gamesTab = document.querySelector('.tab[data-view="games"]');
  if (gamesTab) gamesTab.addEventListener("click", function () {
    if (lastScanResult) showGames(lastScanResult);
    renderActiveGame();
  });
  document.querySelector("#androidGrantFolder").onclick = function () { window.ThorVaultNative.chooseGameFolder(); };
  document.querySelector("#androidScanGames").onclick = function () {
    scan().catch(function (error) { if (!error.partialResult) window.alert(error.message); });
  };
  var cancelScanButton = document.querySelector("#androidCancelScan");
  if (cancelScanButton) cancelScanButton.onclick = requestCancelScan;
  var selectAllLocationsButton = document.querySelector("#androidSelectAllFolders");
  if (selectAllLocationsButton) selectAllLocationsButton.onclick = function () {
    persistLocationSelection(scanLocationUris.slice());
    renderScanLocations({locations:scanLocationsStatus});
  };
  var selectNoLocationsButton = document.querySelector("#androidSelectNoFolders");
  if (selectNoLocationsButton) selectNoLocationsButton.onclick = function () {
    persistLocationSelection([]);
    renderScanLocations({locations:scanLocationsStatus});
  };
  document.querySelector("#androidForgetFolder").onclick = function () {
    if (!window.confirm("Forget all remembered scan locations? This only removes ThorVault's Android folder permissions; no ROMs, saves, or backups will be deleted.")) return;
    try {
      parse(window.ThorVaultNative.forgetAllGameFolders(), "Could not forget the scan locations.");
      localStorage.setItem("thorvault-android-scan-locations-configured", "1");
      localStorage.setItem("thorvault-android-scan-location-uris", "[]");
      localStorage.setItem("thorvault-android-scan-known-uris", "[]");
      var emptyStatus = {locations:[], folderUri:null, folderName:null, status:"none", permissionMode:"none", persistedRead:false, persistedWrite:false, readable:false, writable:false};
      renderScanLocations(emptyStatus);
      renderFolderDiagnostics(emptyStatus);
      lastScanResult = {games:[], locations:[], folderUri:null, done:true};
      cacheScanSnapshot(lastScanResult, true);
      document.querySelector("#androidGameList").innerHTML = "<p class='empty'>No scan locations saved.</p>";
      document.querySelector("#androidGameCount").textContent = "0 found";
      setFolderPanel("warn", "Scan locations forgotten", "Add a folder to scan Pokémon saves again.");
    } catch (error) { setFolderPanel("bad", "Could not forget locations", error.message); window.alert(error.message); }
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
    var initialFolderStatus = parse(window.ThorVaultNative.getFolderStatus(), "Could not read Android folder status.");
    renderFolderDiagnostics(initialFolderStatus);
    renderScanLocations(initialFolderStatus);
    if (lastScanResult && Array.isArray(lastScanResult.games)) {
      var rememberedUris = scanLocationUris.slice();
      lastScanResult.games = lastScanResult.games.filter(function (game) { return !game.treeUri || rememberedUris.indexOf(String(game.treeUri)) >= 0; });
      lastScanResult.folderStatus = initialFolderStatus;
      lastScanResult.locations = initialFolderStatus.locations || [];
      showGames(lastScanResult);
    }
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
