#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import mimetypes
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import threading
import uuid
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

SERVER_VERSION = "0.12.25"
API_VERSION = 1
MIN_CLIENT_API = 1
MAX_CLIENT_API = 1

ROOT = Path(__file__).resolve().parents[1]
APP_DIR = ROOT / "app"
DATA_DIR = Path(os.getenv("THORVAULT_DATA", str(ROOT / "data"))).resolve()
SNAPSHOT_DIR = DATA_DIR / "snapshots"
IMPORT_BACKUP_DIR = DATA_DIR / "import-backups"
GENERATED_SAVE_DIR = DATA_DIR / "generated-saves"
DB_PATH = DATA_DIR / "thorvault.sqlite3"
SERVER_ID_PATH = DATA_DIR / "server-id.txt"
ROM_DIR = Path(os.getenv("THORVAULT_ROMS", "/data/roms"))
SAVE_DIR = Path(os.getenv("THORVAULT_SAVES", "/data/saves"))
TOKEN = os.getenv("THORVAULT_TOKEN", "change-me")
CORS_ORIGINS = {
    value.strip()
    for value in os.getenv(
        "THORVAULT_CORS_ORIGINS",
        "https://appassets.androidplatform.net",
    ).split(",")
    if value.strip()
}
HOST = os.getenv("THORVAULT_HOST", "0.0.0.0")
PORT = int(os.getenv("THORVAULT_PORT", "8765"))
DOTNET = os.getenv("THORVAULT_DOTNET", "dotnet")
ENGINE_DLL = Path(os.getenv(
    "THORVAULT_ENGINE_DLL",
    str(ROOT / "engine" / "ThorVault.Engine" / "bin" / "Release" / "net10.0" / "ThorVault.Engine.dll"),
))
LOCK = threading.Lock()
MAX_BODY = 50 * 1024 * 1024
MAX_SAVE_BODY = 8 * 1024 * 1024

ROM_EXT = {".3ds",".cci",".cxi",".nds",".gba",".gb",".gbc",".xci",".nsp",".rvz",".iso",".chd"}
SAVE_EXT = {".sav",".dsv",".dat",".bin",".srm",".pk1",".pk2",".pk3",".pk4",".pk5",".pk6",".pk7",".pk8",".pk9",".pkm"}

def now():
    return datetime.now(timezone.utc).isoformat()

def server_id():
    if SERVER_ID_PATH.is_file():
        value = SERVER_ID_PATH.read_text(encoding="utf-8").strip()
        if value:
            return value
    value = str(uuid.uuid4())
    SERVER_ID_PATH.parent.mkdir(parents=True, exist_ok=True)
    temp = SERVER_ID_PATH.with_suffix(".tmp")
    temp.write_text(value, encoding="utf-8")
    os.replace(temp, SERVER_ID_PATH)
    return value

def snapshot_count():
    try:
        with LOCK, sqlite3.connect(DB_PATH) as db:
            return int(db.execute("SELECT COUNT(*) FROM snapshots").fetchone()[0])
    except sqlite3.Error:
        return 0

def latest_snapshot_meta():
    try:
        with LOCK, sqlite3.connect(DB_PATH) as db:
            row = db.execute("""
                SELECT id,device_id,device_name,revision,created_at,sha256,base_sha256
                FROM snapshots ORDER BY id DESC LIMIT 1
            """).fetchone()
        if not row:
            return None
        snapshot_id, device_id, device_name, revision, created_at, digest, base_sha256 = row
        return {
            "snapshotId": snapshot_id,
            "deviceId": device_id,
            "deviceName": device_name,
            "revision": revision,
            "createdAt": created_at,
            "sha256": digest,
            "baseSha256": base_sha256,
        }
    except sqlite3.Error:
        return None

def init_storage():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    SNAPSHOT_DIR.mkdir(parents=True, exist_ok=True)
    IMPORT_BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    GENERATED_SAVE_DIR.mkdir(parents=True, exist_ok=True)
    with LOCK, sqlite3.connect(DB_PATH) as db:
        db.execute("""
        CREATE TABLE IF NOT EXISTS snapshots(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          device_id TEXT NOT NULL,
          device_name TEXT NOT NULL,
          revision INTEGER NOT NULL,
          created_at TEXT NOT NULL,
          sha256 TEXT NOT NULL,
          file_path TEXT NOT NULL
        )""")
        columns = {
            row[1] for row in db.execute("PRAGMA table_info(snapshots)").fetchall()
        }
        if "base_sha256" not in columns:
            db.execute("ALTER TABLE snapshots ADD COLUMN base_sha256 TEXT")
        db.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_sha ON snapshots(sha256)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_device ON snapshots(device_id,id DESC)")
        db.execute("""
        CREATE TABLE IF NOT EXISTS clients(
          device_id TEXT PRIMARY KEY,
          device_name TEXT NOT NULL,
          client_version TEXT NOT NULL,
          api_version INTEGER NOT NULL,
          platform TEXT NOT NULL,
          last_seen TEXT NOT NULL,
          last_ip TEXT,
          user_agent TEXT
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS idx_clients_seen ON clients(last_seen DESC)")
        db.execute("""
        CREATE TABLE IF NOT EXISTS save_backups(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          sha256 TEXT NOT NULL,
          parent_sha256 TEXT,
          kind TEXT NOT NULL,
          game TEXT,
          family TEXT,
          trainer TEXT,
          filename TEXT NOT NULL,
          created_at TEXT NOT NULL,
          file_path TEXT NOT NULL,
          byte_length INTEGER NOT NULL,
          checksums_valid INTEGER,
          UNIQUE(sha256, kind)
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS idx_save_backups_created ON save_backups(id DESC)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_save_backups_hash ON save_backups(sha256)")
        db.commit()


def record_save_backup(
    *,
    sha256,
    kind,
    filename,
    file_path,
    byte_length,
    game="",
    family="",
    trainer="",
    parent_sha256=None,
    checksums_valid=None,
):
    created_at = now()
    with LOCK, sqlite3.connect(DB_PATH) as db:
        db.execute(
            """
            INSERT OR IGNORE INTO save_backups(
              sha256,parent_sha256,kind,game,family,trainer,filename,
              created_at,file_path,byte_length,checksums_valid
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                str(sha256),
                str(parent_sha256) if parent_sha256 else None,
                str(kind),
                str(game or ""),
                str(family or ""),
                str(trainer or ""),
                safe_filename(filename),
                created_at,
                str(Path(file_path).resolve()),
                int(byte_length),
                None if checksums_valid is None else int(bool(checksums_valid)),
            ),
        )
        db.commit()




def recommended_save_filename(filename, generation):
    if int(generation or 0) >= 6:
        return "main"
    cleaned = safe_filename(filename or "pokemon.sav")
    if cleaned.lower().endswith((".sav", ".dsv")):
        return cleaned
    return cleaned + ".sav"


def normalized_payload_format(value):
    cleaned = str(value or "").strip().lower().lstrip(".")
    if cleaned not in {"pk3", "pk4", "pk5", "pk6", "pk7"}:
        raise ValueError("payloadFormat must be pk3, pk4, pk5, pk6, or pk7.")
    return cleaned


def safe_device_id(value):
    cleaned = "".join(c for c in str(value) if c.isalnum() or c in "-_")
    if not cleaned or cleaned != value or len(cleaned) > 100:
        raise ValueError("Invalid device ID.")
    return cleaned

def safe_filename(value):
    name = Path(str(value or "main")).name
    cleaned = "".join(c for c in name if c.isalnum() or c in "._-")
    return cleaned[:150] or "main"

def scan(root, extensions, include_main=False):
    if not root.is_dir():
        return []
    result = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() not in extensions and not (include_main and path.name == "main"):
            continue
        try:
            info = path.stat()
            result.append({
                "path": str(path.relative_to(root)),
                "size": info.st_size,
                "modifiedAt": datetime.fromtimestamp(info.st_mtime, timezone.utc).isoformat()
            })
        except OSError:
            pass
        if len(result) >= 5000:
            break
    return sorted(result, key=lambda x: x["path"].lower())

def engine_status():
    dotnet_path = shutil.which(DOTNET)
    return {
        "ready": bool(dotnet_path and ENGINE_DLL.is_file()),
        "dotnet": dotnet_path,
        "engineDll": str(ENGINE_DLL),
        "engineExists": ENGINE_DLL.is_file(),
    }

class Handler(SimpleHTTPRequestHandler):
    server_version = "ThorVault/0.12.25"

    def translate_path(self, request_path):
        relative = urlparse(request_path).path.lstrip("/") or "index.html"
        target = (APP_DIR / relative).resolve()
        if target != APP_DIR and APP_DIR not in target.parents:
            target = APP_DIR / "index.html"
        return str(target if target.exists() else APP_DIR / "index.html")

    def end_headers(self):
        origin = self.headers.get("Origin", "")
        if origin and ("*" in CORS_ORIGINS or origin in CORS_ORIGINS):
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header(
                "Access-Control-Allow-Headers",
                "Content-Type, X-ThorVault-Token, X-ThorVault-Client-Version, "
                "X-ThorVault-Api-Version, X-ThorVault-Device-Id, "
                "X-ThorVault-Device-Name, X-ThorVault-Platform",
            )
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Max-Age", "600")
            self.send_header("Access-Control-Allow-Private-Network", "true")
            self.send_header(
                "Access-Control-Expose-Headers",
                "X-ThorVault-Server-Version, X-ThorVault-Api-Version",
            )
        self.send_header("X-ThorVault-Server-Version", SERVER_VERSION)
        self.send_header("X-ThorVault-Api-Version", str(API_VERSION))
        self.send_header("Cross-Origin-Resource-Policy", "cross-origin")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cache-Control", "no-store" if self.path.startswith("/api/") else "public, max-age=120")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/health":
            return self.send_json({
                "ok": True,
                "version": SERVER_VERSION,
                "serverVersion": SERVER_VERSION,
                "apiVersion": API_VERSION,
                "minClientApi": MIN_CLIENT_API,
                "maxClientApi": MAX_CLIENT_API,
                "serverId": server_id(),
                "snapshotCount": snapshot_count(),
                "latestSnapshot": latest_snapshot_meta(),
                "time": now(),
            })
        if parsed.path == "/api/engine/status":
            return self.send_json(engine_status())
        if parsed.path == "/api/catalog":
            if not self.authorized(): return
            return self.send_json({"roms": scan(ROM_DIR, ROM_EXT), "saves": scan(SAVE_DIR, SAVE_EXT, True)})
        if parsed.path == "/api/snapshots/devices":
            if not self.authorized(): return
            return self.snapshot_devices()
        if parsed.path == "/api/clients":
            if not self.authorized(): return
            return self.list_clients()
        if parsed.path == "/api/snapshots/latest":
            if not self.authorized(): return
            return self.latest(parse_qs(parsed.query))
        if parsed.path == "/api/snapshots/latest-any":
            if not self.authorized(): return
            return self.latest_any()
        if parsed.path == "/api/snapshots/by-sha":
            if not self.authorized(): return
            return self.snapshot_by_sha(parse_qs(parsed.query))
        if parsed.path == "/api/save-backups":
            if not self.authorized(): return
            return self.list_save_backups(parse_qs(parsed.query))
        if parsed.path == "/api/save-backups/file":
            if not self.authorized(): return
            return self.get_save_backup(parse_qs(parsed.query))
        return super().do_GET()

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/snapshots":
            if not self.authorized(): return
            return self.create_snapshot()
        if parsed.path == "/api/clients/register":
            if not self.authorized(): return
            return self.register_client()
        if parsed.path in {"/api/oras/import", "/api/gen6/import", "/api/save/import"}:
            if not self.authorized(): return
            return self.import_supported_save(parse_qs(parsed.query))
        if parsed.path in {"/api/oras/withdraw", "/api/gen6/write", "/api/save/write"}:
            if not self.authorized(): return
            return self.write_supported_save()
        if parsed.path == "/api/save/clear":
            if not self.authorized(): return
            return self.clear_save_slot()
        if parsed.path in {"/api/gen6/compatibility", "/api/save/compatibility"}:
            if not self.authorized(): return
            return self.check_save_compatibility()
        if parsed.path == "/api/pokemon/inspect":
            if not self.authorized(): return
            return self.inspect_pokemon()
        if parsed.path == "/api/pokemon/convert-pk6-to-pk7":
            if not self.authorized(): return
            return self.convert_pk6_to_pk7()
        self.send_error(HTTPStatus.NOT_FOUND)

    def authorized(self):
        if not TOKEN or self.headers.get("X-ThorVault-Token", "") != TOKEN:
            self.send_json({"error": "Unauthorized"}, HTTPStatus.UNAUTHORIZED)
            return False
        return True

    def register_client(self):
        try:
            value = self.read_json()
            device_id = safe_device_id(str(value.get("deviceId", "")))
            device_name = str(value.get("deviceName", "Unknown device")).strip()[:120] or "Unknown device"
            client_version = str(value.get("clientVersion", "unknown")).strip()[:40] or "unknown"
            api_version = int(value.get("apiVersion", 0))
            platform = str(value.get("platform", "unknown")).strip()[:60] or "unknown"
            seen = now()
            with LOCK, sqlite3.connect(DB_PATH) as db:
                db.execute(
                    """
                    INSERT INTO clients(
                      device_id,device_name,client_version,api_version,platform,
                      last_seen,last_ip,user_agent
                    ) VALUES(?,?,?,?,?,?,?,?)
                    ON CONFLICT(device_id) DO UPDATE SET
                      device_name=excluded.device_name,
                      client_version=excluded.client_version,
                      api_version=excluded.api_version,
                      platform=excluded.platform,
                      last_seen=excluded.last_seen,
                      last_ip=excluded.last_ip,
                      user_agent=excluded.user_agent
                    """,
                    (device_id, device_name, client_version, api_version, platform, seen,
                     self.client_address[0] if self.client_address else "",
                     self.headers.get("User-Agent", "")[:250]),
                )
                db.commit()
            self.send_json({
                "ok": True, "deviceId": device_id, "serverVersion": SERVER_VERSION,
                "apiVersion": API_VERSION, "lastSeen": seen,
            }, HTTPStatus.CREATED)
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def list_clients(self):
        with LOCK, sqlite3.connect(DB_PATH) as db:
            rows = db.execute(
                """
                SELECT device_id,device_name,client_version,api_version,platform,last_seen
                FROM clients ORDER BY last_seen DESC LIMIT 100
                """
            ).fetchall()
        self.send_json({
            "serverVersion": SERVER_VERSION, "apiVersion": API_VERSION,
            "clients": [
                {"deviceId": r[0], "deviceName": r[1], "clientVersion": r[2],
                 "apiVersion": r[3], "platform": r[4], "lastSeen": r[5]}
                for r in rows
            ],
        })

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY:
            raise ValueError("Invalid request body size.")
        return json.loads(self.rfile.read(length))

    def read_binary(self, maximum):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            raise ValueError("No save file was uploaded.")
        if length > maximum:
            raise ValueError(f"Save upload exceeds the {maximum // (1024 * 1024)} MB limit.")
        data = self.rfile.read(length)
        if len(data) != length:
            raise ValueError("The save upload ended unexpectedly.")
        return data


    def import_supported_save(self, query):
        status = engine_status()
        if not status["ready"]:
            return self.send_json({
                "error": "The ThorVault multi-generation engine is not built yet.",
                "setup": "Run ./scripts/setup-engine-mac.sh, then restart ThorVault.",
                "engine": status,
            }, HTTPStatus.SERVICE_UNAVAILABLE)

        try:
            data = self.read_binary(MAX_SAVE_BODY)
            filename = safe_filename(query.get("filename", ["main"])[0])
            digest = hashlib.sha256(data).hexdigest()

            backup_folder = IMPORT_BACKUP_DIR / digest
            backup_folder.mkdir(parents=True, exist_ok=True)
            backup_path = backup_folder / filename
            backup_created = not backup_path.exists()
            if backup_created:
                temp_path = backup_path.with_suffix(backup_path.suffix + ".tmp")
                temp_path.write_bytes(data)
                os.replace(temp_path, backup_path)

            completed = subprocess.run(
                [DOTNET, str(ENGINE_DLL), "import-save", str(backup_path)],
                capture_output=True,
                text=True,
                timeout=45,
                check=False,
            )

            stdout = completed.stdout.strip()
            if not stdout:
                raise RuntimeError(completed.stderr.strip() or "The Pokémon engine produced no output.")

            result = json.loads(stdout.splitlines()[-1])
            if completed.returncode != 0 or not result.get("ok"):
                message = result.get("error") or completed.stderr.strip() or "The save could not be read."
                return self.send_json({
                    "error": message,
                    "engineErrorType": result.get("errorType"),
                    "saveBackup": {
                        "sha256": digest,
                        "created": backup_created,
                        "path": str(backup_path),
                    },
                }, HTTPStatus.UNPROCESSABLE_ENTITY)

            result["sourceFilename"] = filename
            result["downloadFilename"] = recommended_save_filename(
                filename,
                (result.get("save") or {}).get("generation"),
            )
            result["saveBackup"] = {
                "sha256": digest,
                "created": backup_created,
                "path": str(backup_path),
            }
            result["importedAt"] = now()
            save_info = result.get("save") or {}
            record_save_backup(
                sha256=digest,
                kind="original",
                filename=filename,
                file_path=backup_path,
                byte_length=len(data),
                game=save_info.get("game", ""),
                family=save_info.get("family", ""),
                trainer=save_info.get("trainer", ""),
                checksums_valid=save_info.get("checksumsValid"),
            )
            self.send_json(result)
        except subprocess.TimeoutExpired:
            self.send_json({"error": "The multi-generation engine timed out while reading the save."}, HTTPStatus.GATEWAY_TIMEOUT)
        except (ValueError, RuntimeError, json.JSONDecodeError, OSError) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def write_supported_save(self):
        status = engine_status()
        if not status["ready"]:
            return self.send_json({
                "error": "The ThorVault multi-generation engine is not built yet.",
                "setup": "Run ./scripts/setup-engine-mac.sh, then restart ThorVault.",
                "engine": status,
            }, HTTPStatus.SERVICE_UNAVAILABLE)

        try:
            body = self.read_json()
            filename = safe_filename(body.get("filename") or "main")
            payload_format = normalized_payload_format(body.get("payloadFormat") or "pk6")
            save_b64 = body.get("saveBase64")
            pokemon_b64 = body.get("pokemonBase64")
            box = int(body.get("box"))
            slot = int(body.get("slot"))

            if not isinstance(save_b64, str) or not isinstance(pokemon_b64, str):
                raise ValueError("Both saveBase64 and pokemonBase64 are required.")

            import base64
            try:
                save_data = base64.b64decode(save_b64, validate=True)
                pokemon_data = base64.b64decode(pokemon_b64, validate=True)
            except Exception as exc:
                raise ValueError("The save or Pokémon payload is not valid Base64.") from exc

            if not save_data or len(save_data) > MAX_SAVE_BODY:
                raise ValueError("The destination save is empty or too large.")
            if len(pokemon_data) < 70 or len(pokemon_data) > 512:
                raise ValueError("The stored Pokémon payload has an unexpected size.")

            original_hash = hashlib.sha256(save_data).hexdigest()
            pokemon_hash = hashlib.sha256(pokemon_data).hexdigest()

            backup_folder = IMPORT_BACKUP_DIR / original_hash
            backup_folder.mkdir(parents=True, exist_ok=True)
            original_path = backup_folder / filename
            backup_created = not original_path.exists()
            if backup_created:
                temp_original = original_path.with_suffix(original_path.suffix + ".tmp")
                temp_original.write_bytes(save_data)
                os.replace(temp_original, original_path)

            timestamp = now().replace(":", "-").replace("+", "_")
            generated_folder = GENERATED_SAVE_DIR / original_hash
            generated_folder.mkdir(parents=True, exist_ok=True)
            pokemon_path = generated_folder / f"{timestamp}-{pokemon_hash[:12]}.{payload_format}"
            output_path = generated_folder / f"{timestamp}-box{box + 1}-slot{slot + 1}-main"
            pokemon_path.write_bytes(pokemon_data)

            completed = subprocess.run(
                [
                    DOTNET,
                    str(ENGINE_DLL),
                    "write-save",
                    str(original_path),
                    str(pokemon_path),
                    payload_format,
                    str(box),
                    str(slot),
                    str(output_path),
                ],
                capture_output=True,
                text=True,
                timeout=45,
                check=False,
            )

            stdout = completed.stdout.strip()
            if not stdout:
                raise RuntimeError(completed.stderr.strip() or "The Pokémon engine produced no output.")

            result = json.loads(stdout.splitlines()[-1])
            if completed.returncode != 0 or not result.get("ok"):
                message = result.get("error") or completed.stderr.strip() or "The modified save could not be created."
                return self.send_json({
                    "error": message,
                    "engineErrorType": result.get("errorType"),
                    "originalBackup": {
                        "sha256": original_hash,
                        "created": backup_created,
                    },
                }, HTTPStatus.UNPROCESSABLE_ENTITY)

            modified_data = output_path.read_bytes()
            result["originalBackup"] = {
                "sha256": original_hash,
                "created": backup_created,
            }
            result["generatedAt"] = now()
            modified_info = result.get("modifiedSave") or {}
            download_filename = recommended_save_filename(
                filename,
                modified_info.get("generation"),
            )
            result["downloadFilename"] = download_filename
            result["modifiedSaveBase64"] = base64.b64encode(modified_data).decode("ascii")
            record_save_backup(
                sha256=modified_info.get("sha256") or hashlib.sha256(modified_data).hexdigest(),
                parent_sha256=original_hash,
                kind="generated",
                filename=download_filename,
                file_path=output_path,
                byte_length=len(modified_data),
                game=modified_info.get("game", ""),
                family=modified_info.get("family", ""),
                trainer=modified_info.get("trainer", ""),
                checksums_valid=modified_info.get("checksumsValid"),
            )
            self.send_json(result)
        except subprocess.TimeoutExpired:
            self.send_json({"error": "The multi-generation engine timed out while creating the modified save."}, HTTPStatus.GATEWAY_TIMEOUT)
        except (ValueError, RuntimeError, json.JSONDecodeError, OSError) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def check_save_compatibility(self):
        status = engine_status()
        if not status["ready"]:
            return self.send_json({
                "error": "The ThorVault multi-generation engine is not built yet.",
                "setup": "Run ./scripts/setup-engine-mac.sh, then restart ThorVault.",
                "engine": status,
            }, HTTPStatus.SERVICE_UNAVAILABLE)

        try:
            body = self.read_json()
            payload_format = normalized_payload_format(body.get("payloadFormat") or "pk6")
            save_b64 = body.get("saveBase64")
            pokemon_b64 = body.get("pokemonBase64")
            if not isinstance(save_b64, str) or not isinstance(pokemon_b64, str):
                raise ValueError("Both saveBase64 and pokemonBase64 are required.")

            import base64
            try:
                save_data = base64.b64decode(save_b64, validate=True)
                pokemon_data = base64.b64decode(pokemon_b64, validate=True)
            except Exception as exc:
                raise ValueError("The save or Pokémon payload is not valid Base64.") from exc

            if not save_data or len(save_data) > MAX_SAVE_BODY:
                raise ValueError("The destination save is empty or too large.")
            if len(pokemon_data) < 70 or len(pokemon_data) > 512:
                raise ValueError("The stored Pokémon payload has an unexpected size.")

            with tempfile.TemporaryDirectory(prefix="thorvault-3ds-check-") as temp_dir:
                temp = Path(temp_dir)
                save_path = temp / "main"
                pokemon_path = temp / f"pokemon.{payload_format}"
                save_path.write_bytes(save_data)
                pokemon_path.write_bytes(pokemon_data)

                completed = subprocess.run(
                    [
                        DOTNET,
                        str(ENGINE_DLL),
                        "check-save",
                        str(save_path),
                        str(pokemon_path),
                        payload_format,
                    ],
                    capture_output=True,
                    text=True,
                    timeout=45,
                    check=False,
                )

            stdout = completed.stdout.strip()
            if not stdout:
                raise RuntimeError(completed.stderr.strip() or "The Pokémon engine produced no output.")

            result = json.loads(stdout.splitlines()[-1])
            if completed.returncode != 0 or not result.get("ok"):
                message = result.get("error") or completed.stderr.strip() or "Compatibility analysis failed."
                return self.send_json({
                    "error": message,
                    "engineErrorType": result.get("errorType"),
                }, HTTPStatus.UNPROCESSABLE_ENTITY)

            self.send_json(result)
        except subprocess.TimeoutExpired:
            self.send_json({"error": "The save compatibility check timed out."}, HTTPStatus.GATEWAY_TIMEOUT)
        except (ValueError, RuntimeError, json.JSONDecodeError, OSError) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def inspect_pokemon(self):
        status = engine_status()
        if not status["ready"]:
            return self.send_json({
                "error": "The ThorVault multi-generation engine is not built yet.",
                "setup": "Run ./scripts/setup-engine-mac.sh, then restart ThorVault.",
                "engine": status,
            }, HTTPStatus.SERVICE_UNAVAILABLE)

        try:
            body = self.read_json()
            payload_format = normalized_payload_format(body.get("payloadFormat") or "pk6")
            pokemon_b64 = body.get("pokemonBase64")
            if not isinstance(pokemon_b64, str):
                raise ValueError("pokemonBase64 is required.")

            import base64
            try:
                pokemon_data = base64.b64decode(pokemon_b64, validate=True)
            except Exception as exc:
                raise ValueError("The Pokémon payload is not valid Base64.") from exc

            if len(pokemon_data) < 70 or len(pokemon_data) > 512:
                raise ValueError("The stored Pokémon payload has an unexpected size.")

            with tempfile.TemporaryDirectory(prefix="thorvault-pokemon-inspect-") as temp_dir:
                pokemon_path = Path(temp_dir) / f"pokemon.{payload_format}"
                pokemon_path.write_bytes(pokemon_data)
                completed = subprocess.run(
                    [
                        DOTNET,
                        str(ENGINE_DLL),
                        "inspect-pokemon",
                        str(pokemon_path),
                        payload_format,
                    ],
                    capture_output=True,
                    text=True,
                    timeout=45,
                    check=False,
                )

            stdout = completed.stdout.strip()
            if not stdout:
                raise RuntimeError(completed.stderr.strip() or "The Pokémon engine produced no output.")

            result = json.loads(stdout.splitlines()[-1])
            if completed.returncode != 0 or not result.get("ok"):
                message = result.get("error") or completed.stderr.strip() or "The Pokémon payload could not be inspected."
                return self.send_json({
                    "error": message,
                    "engineErrorType": result.get("errorType"),
                }, HTTPStatus.UNPROCESSABLE_ENTITY)

            self.send_json(result)
        except subprocess.TimeoutExpired:
            self.send_json({"error": "The Pokémon metadata inspection timed out."}, HTTPStatus.GATEWAY_TIMEOUT)
        except (ValueError, RuntimeError, json.JSONDecodeError, OSError) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)



    def clear_save_slot(self):
        status = engine_status()
        if not status["ready"]:
            return self.send_json({
                "error": "The ThorVault multi-generation engine is not built yet.",
                "setup": "Run ./scripts/setup-engine-mac.sh, then restart ThorVault.",
                "engine": status,
            }, HTTPStatus.SERVICE_UNAVAILABLE)

        try:
            body = self.read_json()
            filename = safe_filename(body.get("filename") or "main")
            save_b64 = body.get("saveBase64")
            box = int(body.get("box"))
            slot = int(body.get("slot"))
            expected_hash = str(body.get("expectedPokemonSha256") or "").strip().lower()

            if not isinstance(save_b64, str):
                raise ValueError("saveBase64 is required.")
            if not re.fullmatch(r"[0-9a-f]{64}", expected_hash):
                raise ValueError("expectedPokemonSha256 must be a full SHA-256 hash.")

            import base64
            try:
                save_data = base64.b64decode(save_b64, validate=True)
            except Exception as exc:
                raise ValueError("The save payload is not valid Base64.") from exc

            if not save_data or len(save_data) > MAX_SAVE_BODY:
                raise ValueError("The destination save is empty or too large.")

            original_hash = hashlib.sha256(save_data).hexdigest()
            backup_folder = IMPORT_BACKUP_DIR / original_hash
            backup_folder.mkdir(parents=True, exist_ok=True)
            original_path = backup_folder / filename
            backup_created = not original_path.exists()
            if backup_created:
                temp_original = original_path.with_suffix(original_path.suffix + ".tmp")
                temp_original.write_bytes(save_data)
                os.replace(temp_original, original_path)

            timestamp = now().replace(":", "-").replace("+", "_")
            generated_folder = GENERATED_SAVE_DIR / original_hash
            generated_folder.mkdir(parents=True, exist_ok=True)
            output_path = generated_folder / f"{timestamp}-clear-box{box + 1}-slot{slot + 1}-main"

            completed = subprocess.run(
                [
                    DOTNET,
                    str(ENGINE_DLL),
                    "clear-box-slot",
                    str(original_path),
                    str(box),
                    str(slot),
                    expected_hash,
                    str(output_path),
                ],
                capture_output=True,
                text=True,
                timeout=45,
                check=False,
            )

            stdout = completed.stdout.strip()
            if not stdout:
                raise RuntimeError(completed.stderr.strip() or "The Pokémon engine produced no output.")

            result = json.loads(stdout.splitlines()[-1])
            if completed.returncode != 0 or not result.get("ok"):
                message = result.get("error") or completed.stderr.strip() or "The source slot could not be cleared."
                return self.send_json({
                    "error": message,
                    "engineErrorType": result.get("errorType"),
                    "originalBackup": {
                        "sha256": original_hash,
                        "created": backup_created,
                    },
                }, HTTPStatus.UNPROCESSABLE_ENTITY)

            modified_data = output_path.read_bytes()
            result["originalBackup"] = {
                "sha256": original_hash,
                "created": backup_created,
            }
            result["generatedAt"] = now()
            result["downloadFilename"] = "main"
            result["modifiedSaveBase64"] = base64.b64encode(modified_data).decode("ascii")

            modified_info = result.get("modifiedSave") or {}
            record_save_backup(
                sha256=modified_info.get("sha256") or hashlib.sha256(modified_data).hexdigest(),
                parent_sha256=original_hash,
                kind="generated-true-move-source-clear",
                filename="main",
                file_path=output_path,
                byte_length=len(modified_data),
                game=modified_info.get("game", ""),
                family=modified_info.get("family", ""),
                trainer=modified_info.get("trainer", ""),
                checksums_valid=modified_info.get("checksumsValid"),
            )
            self.send_json(result)
        except subprocess.TimeoutExpired:
            self.send_json({"error": "The true-move source clear timed out."}, HTTPStatus.GATEWAY_TIMEOUT)
        except (ValueError, RuntimeError, json.JSONDecodeError, OSError) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def convert_pk6_to_pk7(self):
        status = engine_status()
        if not status["ready"]:
            return self.send_json({
                "error": "The ThorVault multi-generation engine is not built yet.",
                "setup": "Run ./scripts/setup-engine-mac.sh, then restart ThorVault.",
                "engine": status,
            }, HTTPStatus.SERVICE_UNAVAILABLE)
        try:
            body = self.read_json()
            save_b64 = body.get("saveBase64")
            pokemon_b64 = body.get("pokemonBase64")
            if not isinstance(save_b64, str) or not isinstance(pokemon_b64, str):
                raise ValueError("Both saveBase64 and pokemonBase64 are required.")
            import base64
            try:
                save_data = base64.b64decode(save_b64, validate=True)
                pokemon_data = base64.b64decode(pokemon_b64, validate=True)
            except Exception as exc:
                raise ValueError("The save or PK6 payload is not valid Base64.") from exc
            if not save_data or len(save_data) > MAX_SAVE_BODY:
                raise ValueError("The destination save is empty or too large.")
            if len(pokemon_data) < 70 or len(pokemon_data) > 512:
                raise ValueError("The PK6 payload has an unexpected size.")
            with tempfile.TemporaryDirectory(prefix="thorvault-pk6-pk7-") as temp_dir:
                temp = Path(temp_dir)
                save_path = temp / "main"
                pokemon_path = temp / "source.pk6"
                save_path.write_bytes(save_data)
                pokemon_path.write_bytes(pokemon_data)
                completed = subprocess.run(
                    [DOTNET, str(ENGINE_DLL), "convert-pk6-to-pk7", str(save_path), str(pokemon_path)],
                    capture_output=True, text=True, timeout=45, check=False,
                )
            stdout = completed.stdout.strip()
            if not stdout:
                raise RuntimeError(completed.stderr.strip() or "The Pokémon engine produced no output.")
            result = json.loads(stdout.splitlines()[-1])
            if completed.returncode != 0 or not result.get("ok"):
                message = result.get("error") or completed.stderr.strip() or "PK6 → PK7 conversion failed."
                return self.send_json({"error": message, "engineErrorType": result.get("errorType")}, HTTPStatus.UNPROCESSABLE_ENTITY)
            self.send_json(result)
        except subprocess.TimeoutExpired:
            self.send_json({"error": "PK6 → PK7 conversion timed out."}, HTTPStatus.GATEWAY_TIMEOUT)
        except (ValueError, RuntimeError, json.JSONDecodeError, OSError) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def list_save_backups(self, query):
        try:
            limit = int(query.get("limit", ["100"])[0])
            limit = max(1, min(limit, 500))
            requested_hash = str(query.get("sha256", [""])[0]).strip().lower()

            sql = """
                SELECT id,sha256,parent_sha256,kind,game,family,trainer,filename,
                       created_at,file_path,byte_length,checksums_valid
                FROM save_backups
            """
            params = []
            if requested_hash:
                sql += " WHERE sha256=?"
                params.append(requested_hash)
            sql += " ORDER BY id DESC LIMIT ?"
            params.append(limit)

            with LOCK, sqlite3.connect(DB_PATH) as db:
                rows = db.execute(sql, params).fetchall()

            backups = []
            for row in rows:
                (
                    backup_id,
                    digest,
                    parent_digest,
                    kind,
                    game,
                    family,
                    trainer,
                    filename,
                    created_at,
                    file_path,
                    byte_length,
                    checksums_valid,
                ) = row
                path = Path(file_path)
                if not path.is_file():
                    continue
                backups.append({
                    "id": backup_id,
                    "sha256": digest,
                    "parentSha256": parent_digest,
                    "kind": kind,
                    "game": game,
                    "family": family,
                    "trainer": trainer,
                    "filename": filename,
                    "createdAt": created_at,
                    "byteLength": byte_length,
                    "checksumsValid": None if checksums_valid is None else bool(checksums_valid),
                })
            self.send_json({"backups": backups})
        except ValueError as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def get_save_backup(self, query):
        try:
            import base64

            backup_id = int(query.get("id", ["0"])[0])
            if backup_id <= 0:
                raise ValueError("A valid backup ID is required.")

            with LOCK, sqlite3.connect(DB_PATH) as db:
                row = db.execute(
                    """
                    SELECT id,sha256,parent_sha256,kind,game,family,trainer,filename,
                           created_at,file_path,byte_length,checksums_valid
                    FROM save_backups WHERE id=?
                    """,
                    (backup_id,),
                ).fetchone()

            if not row:
                return self.send_json({"error": "Save backup not found."}, HTTPStatus.NOT_FOUND)

            (
                stored_id,
                digest,
                parent_digest,
                kind,
                game,
                family,
                trainer,
                filename,
                created_at,
                file_path,
                byte_length,
                checksums_valid,
            ) = row
            path = Path(file_path)
            payload = path.read_bytes()
            actual_hash = hashlib.sha256(payload).hexdigest()
            if actual_hash != digest:
                return self.send_json(
                    {"error": "Save backup integrity check failed."},
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                )

            self.send_json({
                "id": stored_id,
                "sha256": digest,
                "parentSha256": parent_digest,
                "kind": kind,
                "game": game,
                "family": family,
                "trainer": trainer,
                "filename": filename,
                "createdAt": created_at,
                "byteLength": byte_length,
                "checksumsValid": None if checksums_valid is None else bool(checksums_valid),
                "saveBase64": base64.b64encode(payload).decode("ascii"),
            })
        except (ValueError, OSError) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def create_snapshot(self):
        try:
            body = self.read_json()
            device_id = safe_device_id(body["deviceId"])
            device_name = str(body.get("deviceName") or "Unknown")[:200]
            revision = int(body.get("revision", 0))
            vault = body["vault"]
            base_sha256 = str(body.get("baseSha256") or "").strip().lower()
            force = bool(body.get("force", False))
            if not isinstance(vault, dict):
                raise ValueError("Vault must be an object.")

            payload = json.dumps(
                vault,
                ensure_ascii=False,
                sort_keys=True,
                indent=2,
            ).encode()
            digest = hashlib.sha256(payload).hexdigest()

            with LOCK, sqlite3.connect(DB_PATH) as db:
                latest = db.execute("""
                    SELECT id,device_id,device_name,created_at,revision,sha256,file_path,base_sha256
                    FROM snapshots ORDER BY id DESC LIMIT 1
                """).fetchone()

            if latest:
                (
                    latest_id,
                    latest_device_id,
                    latest_device_name,
                    latest_created,
                    latest_revision,
                    latest_sha,
                    latest_path,
                    latest_base_sha,
                ) = latest

                if latest_sha == digest:
                    return self.send_json({
                        "ok": True,
                        "snapshotId": latest_id,
                        "createdAt": latest_created,
                        "revision": latest_revision,
                        "sha256": latest_sha,
                        "baseSha256": latest_base_sha,
                        "deduplicated": True,
                    }, HTTPStatus.OK)

                if not force and base_sha256 and latest_sha != base_sha256:
                    latest_payload = Path(latest_path).read_bytes()
                    if hashlib.sha256(latest_payload).hexdigest() != latest_sha:
                        return self.send_json(
                            {"error": "Latest shared Vault snapshot integrity check failed."},
                            HTTPStatus.INTERNAL_SERVER_ERROR,
                        )
                    return self.send_json({
                        "error": "The shared Vault changed since this client last synchronized.",
                        "conflict": True,
                        "latest": {
                            "snapshotId": latest_id,
                            "deviceId": latest_device_id,
                            "deviceName": latest_device_name,
                            "createdAt": latest_created,
                            "revision": latest_revision,
                            "sha256": latest_sha,
                            "baseSha256": latest_base_sha,
                            "vault": json.loads(latest_payload),
                        },
                    }, HTTPStatus.CONFLICT)

            created = now()
            folder = SNAPSHOT_DIR / device_id
            folder.mkdir(parents=True, exist_ok=True)
            filename = (
                created.replace(":", "-").replace("+", "_") +
                f"-r{revision}-{digest[:12]}.json"
            )
            final = folder / filename
            temp = final.with_suffix(".tmp")
            temp.write_bytes(payload)
            os.replace(temp, final)

            with LOCK, sqlite3.connect(DB_PATH) as db:
                cursor = db.execute(
                    """
                    INSERT INTO snapshots(
                        device_id,device_name,revision,created_at,sha256,file_path,base_sha256
                    ) VALUES(?,?,?,?,?,?,?)
                    """,
                    (
                        device_id,
                        device_name,
                        revision,
                        created,
                        digest,
                        str(final),
                        base_sha256 or None,
                    ),
                )
                snapshot_id = int(cursor.lastrowid)
                db.commit()

            self.send_json({
                "ok": True,
                "snapshotId": snapshot_id,
                "createdAt": created,
                "revision": revision,
                "sha256": digest,
                "baseSha256": base_sha256 or None,
                "deduplicated": False,
            }, HTTPStatus.CREATED)
        except (
            KeyError,
            ValueError,
            json.JSONDecodeError,
            OSError,
            sqlite3.Error,
        ) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def snapshot_devices(self):
        with LOCK, sqlite3.connect(DB_PATH) as db:
            rows = db.execute(
                "SELECT device_id,device_name,revision,created_at FROM snapshots ORDER BY id DESC"
            ).fetchall()
        devices = []
        seen = set()
        for device_id, device_name, revision, created_at in rows:
            if device_id in seen:
                continue
            seen.add(device_id)
            devices.append({
                "deviceId": device_id,
                "deviceName": device_name,
                "revision": revision,
                "createdAt": created_at
            })
        self.send_json({"devices": devices})


    def latest_any(self):
        try:
            with LOCK, sqlite3.connect(DB_PATH) as db:
                row = db.execute(
                    """
                    SELECT id,device_id,device_name,created_at,revision,sha256,file_path,base_sha256
                    FROM snapshots
                    ORDER BY id DESC
                    LIMIT 1
                    """
                ).fetchone()

            if not row:
                return self.send_json(
                    {"error": "No shared Vault snapshot found."},
                    HTTPStatus.NOT_FOUND,
                )

            snapshot_id, device_id, device_name, created, revision, digest, file_path, base_sha256 = row
            payload = Path(file_path).read_bytes()
            if hashlib.sha256(payload).hexdigest() != digest:
                return self.send_json(
                    {"error": "Shared Vault snapshot integrity check failed."},
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                )

            self.send_json({
                "snapshotId": snapshot_id,
                "deviceId": device_id,
                "deviceName": device_name,
                "createdAt": created,
                "revision": revision,
                "sha256": digest,
                "baseSha256": base_sha256,
                "vault": json.loads(payload),
            })
        except (OSError, json.JSONDecodeError) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def snapshot_by_sha(self, query):
        try:
            digest = str(query.get("sha", [""])[0] or "").strip().lower()
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                return self.send_json(
                    {"error": "A valid snapshot SHA-256 is required."},
                    HTTPStatus.BAD_REQUEST,
                )

            with LOCK, sqlite3.connect(DB_PATH) as db:
                row = db.execute(
                    """
                    SELECT id,device_id,device_name,created_at,revision,sha256,file_path,base_sha256
                    FROM snapshots
                    WHERE sha256=?
                    ORDER BY id DESC
                    LIMIT 1
                    """,
                    (digest,),
                ).fetchone()

            if not row:
                return self.send_json(
                    {"error": "Shared Vault base snapshot was not found."},
                    HTTPStatus.NOT_FOUND,
                )

            snapshot_id, device_id, device_name, created, revision, stored_sha, file_path, base_sha256 = row
            payload = Path(file_path).read_bytes()
            if hashlib.sha256(payload).hexdigest() != stored_sha:
                return self.send_json(
                    {"error": "Shared Vault base snapshot integrity check failed."},
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                )

            self.send_json({
                "snapshotId": snapshot_id,
                "deviceId": device_id,
                "deviceName": device_name,
                "createdAt": created,
                "revision": revision,
                "sha256": stored_sha,
                "baseSha256": base_sha256,
                "vault": json.loads(payload),
            })
        except (OSError, json.JSONDecodeError, sqlite3.Error) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def latest(self, query):
        try:
            device_id = safe_device_id(query.get("deviceId", [""])[0])
            with LOCK, sqlite3.connect(DB_PATH) as db:
                row = db.execute(
                    "SELECT created_at,revision,sha256,file_path FROM snapshots WHERE device_id=? ORDER BY id DESC LIMIT 1",
                    (device_id,)
                ).fetchone()
            if not row:
                return self.send_json({"error": "No snapshot found."}, HTTPStatus.NOT_FOUND)
            created, revision, digest, file_path = row
            payload = Path(file_path).read_bytes()
            if hashlib.sha256(payload).hexdigest() != digest:
                return self.send_json({"error": "Snapshot integrity check failed."}, HTTPStatus.INTERNAL_SERVER_ERROR)
            self.send_json({"createdAt": created, "revision": revision, "sha256": digest, "vault": json.loads(payload)})
        except (ValueError, OSError, json.JSONDecodeError) as exc:
            self.send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)

    def send_json(self, value, status=HTTPStatus.OK):
        payload = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        sys.stdout.write(f"[{now()}] {self.address_string()} {fmt % args}\n")

if __name__ == "__main__":
    init_storage()
    mimetypes.add_type("application/manifest+json", ".webmanifest")
    print(f"ThorVault: http://{HOST}:{PORT}")
    print(f"Data: {DATA_DIR}")
    status = engine_status()
    print(f"Gen 6 engine: {'ready' if status['ready'] else 'not built'}")
    if not status["ready"]:
        print("Run ./scripts/setup-engine-mac.sh to enable real Gen 6 save imports.")
    if TOKEN == "change-me":
        print("WARNING: default token is active.")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
