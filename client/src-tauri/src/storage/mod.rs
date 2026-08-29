use rusqlite::{params, Connection, Result as SqlResult};
use serde_json::Value;
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use base64::Engine;

/// 敏感设置值（API 密钥等）在 SQLite 落盘前用 Windows DPAPI 加密，读取时透明解密。
/// 格式：`dpapi:<base64>`。加密仅绑定当前 Windows 用户 + 本机，备份导出时解密为明文
/// 以便跨机迁移，导入时重新加密。
const DPAPI_PREFIX: &str = "dpapi:";

/// 判断某设置 key 是否属于敏感凭据。规则与前端 lib/sanitize.ts 的脱敏名单对齐，
/// 并覆盖实际包含凭据的容器（cloudAi.profiles / cloudAsr.profiles 等供应商 profile 数组）。
pub(crate) fn is_sensitive_key(key: &str) -> bool {
    let k = key.to_ascii_lowercase().replace(['-', '_'], "");
    // 供应商 profile 数组内部含 apiKey/accessToken 等凭据
    if k.ends_with(".profiles") {
        return true;
    }
    [
        "apikey",
        "consolekey",
        "accesskey",
        "authkey",
        "accesstoken",
        "secret",
        "password",
        "credential",
        "appid",
        "token",
    ]
    .iter()
    .any(|p| k.contains(p))
}

#[cfg(target_os = "windows")]
fn dpapi_protect(plain: &[u8]) -> Option<Vec<u8>> {
    use windows::Win32::Foundation::{LocalFree, HLOCAL};
    use windows::Win32::Security::Cryptography::{
        CryptProtectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    let input = CRYPT_INTEGER_BLOB {
        cbData: plain.len() as u32,
        pbData: plain.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let rc = unsafe {
        CryptProtectData(
            &input,
            windows::core::PCWSTR::null(),
            None,
            None,
            None,
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
    };
    if rc.is_err() {
        return None;
    }
    let bytes = unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe {
        let _ = LocalFree(HLOCAL(output.pbData.cast()));
    }
    Some(bytes)
}

#[cfg(target_os = "windows")]
fn dpapi_unprotect(cipher: &[u8]) -> Option<Vec<u8>> {
    use windows::Win32::Foundation::{LocalFree, HLOCAL};
    use windows::Win32::Security::Cryptography::{
        CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    let input = CRYPT_INTEGER_BLOB {
        cbData: cipher.len() as u32,
        pbData: cipher.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let rc = unsafe {
        CryptUnprotectData(&input, None, None, None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut output)
    };
    if rc.is_err() {
        return None;
    }
    let bytes = unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe {
        let _ = LocalFree(HLOCAL(output.pbData.cast()));
    }
    Some(bytes)
}

/// 敏感 key 写入前加密。**DPAPI 失败时不落明文**：返回 Err，由调用方中止写入。
/// 非 Windows 平台上敏感 key 一律拒绝写入（本地自用版仅支持 Windows）。
fn maybe_encrypt_value(key: &str, json_str: String) -> Result<String, String> {
    if !is_sensitive_key(key) {
        return Ok(json_str);
    }
    #[cfg(test)]
    {
        // 测试专用：强制加密失败，用于验证 fail-closed 行为（不落明文、不返回明文）。
        if FORCE_DPAPI_FAIL.load(std::sync::atomic::Ordering::SeqCst) {
            return Err("DPAPI encryption forced to fail (test hook)".to_string());
        }
    }
    #[cfg(target_os = "windows")]
    {
        return dpapi_protect(json_str.as_bytes())
            .map(|enc| format!("{DPAPI_PREFIX}{}", base64::engine::general_purpose::STANDARD.encode(enc)))
            .ok_or_else(|| format!("DPAPI encryption failed for secret key '{}'", key));
    }
    #[cfg(not(target_os = "windows"))]
    Err(format!("DPAPI is unavailable on this platform; refusing to store secret '{}' in plaintext", key))
}

/// 读取时对 `dpapi:` 前缀的值透明解密。解密失败返回 Err（不把密文当明文返回），
/// 由调用方记录日志并按不可用处理，绝不静默降级。
fn maybe_decrypt_value(key: &str, json_str: String) -> Result<String, String> {
    if !json_str.starts_with(DPAPI_PREFIX) {
        return Ok(json_str);
    }
    #[cfg(target_os = "windows")]
    {
        if let Ok(enc) = base64::engine::general_purpose::STANDARD.decode(&json_str[DPAPI_PREFIX.len()..]) {
            if let Some(plain) = dpapi_unprotect(&enc) {
                if let Ok(text) = String::from_utf8(plain) {
                    return Ok(text);
                }
            }
        }
    }
    Err(format!("unable to decrypt DPAPI-protected value for secret key '{}'", key))
}

fn secret_err(msg: String) -> rusqlite::Error {
    rusqlite::Error::InvalidParameterName(msg)
}

/// 测试专用：强制 DPAPI 加密失败，用于验证 fail-closed 行为。仅测试构建编译。
#[cfg(test)]
static FORCE_DPAPI_FAIL: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

#[cfg(test)]
fn set_force_dpapi_fail(fail: bool) {
    FORCE_DPAPI_FAIL.store(fail, std::sync::atomic::Ordering::SeqCst);
}

/// Default settings values (mirrors electron-app/electron/store.ts)
const DEFAULT_SETTINGS: &[(&str, &str)] = &[
    // 按住说话的默认键。不能是 Shift：长按右 Shift 会触发 Windows 筛选键，
    // 导致松开后录音停不下来。与 src/services/defaults.ts、keyboard/mod.rs 的
    // DEFAULT_PTT_SETTING 保持一致。
    ("shortcutPTT", r#""ControlRight""#),
    ("shortcutPTTCombo", r#""Alt+Q""#),
    ("shortcutHandsFree", r#""AltRight""#),
    ("shortcutToggleAi", r#""""#),
    ("autoLaunch", "true"),
    ("selectedMic", r#""""#),
    ("hotwords", "[]"),
    ("builtinHotwordSets", r#"{"ai":false}"#),
    ("stats", r#"{"totalDurationSec":0,"totalChars":0}"#),
    ("activePresetId", r#""intent""#),
];

/// Collection keys that map to dedicated tables instead of app_settings
const COLLECTION_KEYS: &[&str] = &[
    "history",
    "manualCorrections",
    "feedbackQueue",
    "promptPresets",
    "appPromptRules",
];

fn is_collection_key(key: &str) -> bool {
    COLLECTION_KEYS.contains(&key)
}

fn collection_table(key: &str) -> Option<&'static str> {
    match key {
        "history" => Some("history_records"),
        "manualCorrections" => Some("manual_corrections"),
        "feedbackQueue" => Some("feedback_queue"),
        "promptPresets" => Some("prompt_presets"),
        "appPromptRules" => Some("app_prompt_rules"),
        _ => None,
    }
}

pub struct Storage {
    pub db: Mutex<Connection>,
}

impl Storage {
    pub fn new(db_path: PathBuf) -> SqlResult<Self> {
        if let Some(parent) = db_path.parent() {
            let _ = fs::create_dir_all(parent);
        }

        let conn = Connection::open(&db_path)?;
        conn.execute_batch("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;")?;

        let storage = Self { db: Mutex::new(conn) };
        storage.apply_migrations()?;
        storage.seed_defaults()?;
        // 迁移历史明文敏感记录：已有明文密钥一律加密落盘（DPAPI 失败则记日志，绝不继续以明文写入）
        storage.migrate_plaintext_secrets();
        Ok(storage)
    }

    /// 把 app_settings 中「敏感 key 且当前为明文」的记录迁移为 DPAPI 加密。
    /// 幂等：已带 `dpapi:` 前缀或非敏感 key 一律跳过。
    ///
    /// **fail-closed（PM 第二轮审查要求）**：加密失败或写库失败时，删除该明文行，
    /// 绝不把明文敏感值留在数据库里；后续读取走 None 分支返回 fallback。
    /// 只影响敏感 key 行，非敏感配置不受影响。
    fn migrate_plaintext_secrets(&self) {
        let db = self.db.lock().unwrap();
        let rows: Vec<(String, String)> = {
            let mut stmt = match db.prepare("SELECT key, value_json FROM app_settings") {
                Ok(stmt) => stmt,
                Err(_) => return,
            };
            let mapped = stmt.query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            });
            match mapped {
                Ok(iter) => iter.filter_map(|r| r.ok()).collect(),
                Err(_) => return,
            }
        };
        let now = chrono::Utc::now().timestamp_millis();
        for (key, value_json) in rows {
            if !is_sensitive_key(&key) || value_json.starts_with(DPAPI_PREFIX) {
                continue;
            }
            match maybe_encrypt_value(&key, value_json) {
                Ok(enc) => {
                    if let Err(e) = db.execute(
                        "UPDATE app_settings SET value_json = ?1, updated_at = ?2 WHERE key = ?3",
                        params![enc, now, key],
                    ) {
                        log::error!(
                            "Failed to persist encrypted secret '{}': {}; removing plaintext row",
                            key,
                            e,
                        );
                        let _ = db.execute("DELETE FROM app_settings WHERE key = ?1", params![key]);
                    }
                }
                Err(e) => {
                    log::error!("{}; removing plaintext row for key '{}'", e, key);
                    let _ = db.execute("DELETE FROM app_settings WHERE key = ?1", params![key]);
                }
            }
        }
    }

    fn apply_migrations(&self) -> SqlResult<()> {
        let db = self.db.lock().unwrap();

        db.execute_batch(
            "CREATE TABLE IF NOT EXISTS schema_migrations (
                version INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                applied_at INTEGER NOT NULL
            )"
        )?;

        // Migration 1: init
        if !Self::migration_exists(&db, 1)? {
            db.execute_batch(include_str!("migration_001.sql"))?;
            db.execute(
                "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?1, ?2, ?3)",
                params![1, "init-sqlite-storage", chrono::Utc::now().timestamp_millis()],
            )?;
        }

        // Migration 2: add audio_file_path
        if !Self::migration_exists(&db, 2)? {
            db.execute_batch("ALTER TABLE history_records ADD COLUMN audio_file_path TEXT;")?;
            db.execute(
                "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?1, ?2, ?3)",
                params![2, "add-audio-file-path", chrono::Utc::now().timestamp_millis()],
            )?;
        }

        Ok(())
    }

    fn migration_exists(db: &Connection, version: i64) -> SqlResult<bool> {
        let count: i64 = db.query_row(
            "SELECT COUNT(*) FROM schema_migrations WHERE version = ?1",
            params![version],
            |row| row.get(0),
        )?;
        Ok(count > 0)
    }

    fn seed_defaults(&self) -> SqlResult<()> {
        let db = self.db.lock().unwrap();
        let now = chrono::Utc::now().timestamp_millis();

        for &(key, value_json) in DEFAULT_SETTINGS {
            let exists: bool = db.query_row(
                "SELECT COUNT(*) > 0 FROM app_settings WHERE key = ?1",
                params![key],
                |row| row.get(0),
            )?;
            if !exists {
                db.execute(
                    "INSERT INTO app_settings (key, value_json, updated_at) VALUES (?1, ?2, ?3)",
                    params![key, value_json, now],
                )?;
            }
        }
        Ok(())
    }

    // ─── Get / Set / Delete ───

    pub fn get(&self, key: &str, fallback: Option<&Value>) -> Value {
        if is_collection_key(key) {
            return self.read_collection(key).unwrap_or(Value::Array(vec![]));
        }

        let db = self.db.lock().unwrap();
        let result: Option<String> = db
            .query_row(
                "SELECT value_json FROM app_settings WHERE key = ?1",
                params![key],
                |row| row.get(0),
            )
            .ok();

        match result {
            Some(json_str) => {
                if is_sensitive_key(key) && !json_str.starts_with(DPAPI_PREFIX) {
                    // 历史明文敏感值：读取时懒迁移为加密存储。
                    // **fail-closed（PM 第二轮审查要求）**：加密失败或写库失败时，
                    // 删除该明文行并返回 fallback/Null，绝不把明文作为可用值返回。
                    match maybe_encrypt_value(key, json_str.clone()) {
                        Ok(enc) => {
                            let now = chrono::Utc::now().timestamp_millis();
                            if let Err(e) = db.execute(
                                "UPDATE app_settings SET value_json = ?1, updated_at = ?2 WHERE key = ?3",
                                params![enc, now, key],
                            ) {
                                log::error!(
                                    "Failed to persist encrypted secret '{}': {}; removing plaintext row",
                                    key,
                                    e,
                                );
                                let _ = db.execute(
                                    "DELETE FROM app_settings WHERE key = ?1",
                                    params![key],
                                );
                                return fallback.cloned().unwrap_or(Value::Null);
                            }
                            return serde_json::from_str(&json_str).unwrap_or_else(|_| {
                                fallback.cloned().unwrap_or(Value::Null)
                            });
                        }
                        Err(e) => {
                            log::error!("{}; removing plaintext row for key '{}'", e, key);
                            let _ = db.execute("DELETE FROM app_settings WHERE key = ?1", params![key]);
                            return fallback.cloned().unwrap_or(Value::Null);
                        }
                    }
                }
                match maybe_decrypt_value(key, json_str) {
                    Ok(plain) => serde_json::from_str(&plain).unwrap_or_else(|_| fallback.cloned().unwrap_or(Value::Null)),
                    Err(e) => {
                        log::error!("{}", e);
                        fallback.cloned().unwrap_or(Value::Null)
                    }
                }
            }
            None => {
                // Check defaults
                for &(k, v) in DEFAULT_SETTINGS {
                    if k == key {
                        return serde_json::from_str(v).unwrap_or(Value::Null);
                    }
                }
                fallback.cloned().unwrap_or(Value::Null)
            }
        }
    }

    pub fn set(&self, key: &str, value: &Value) -> SqlResult<()> {
        if is_collection_key(key) {
            let items = match value {
                Value::Array(arr) => arr.clone(),
                _ => vec![],
            };
            return self.replace_collection(key, &items);
        }

        let db = self.db.lock().unwrap();
        let json_str = maybe_encrypt_value(
            key,
            serde_json::to_string(value).unwrap_or_else(|_| "null".to_string()),
        )
        .map_err(secret_err)?;
        let now = chrono::Utc::now().timestamp_millis();

        db.execute(
            "INSERT INTO app_settings (key, value_json, updated_at) VALUES (?1, ?2, ?3)
             ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at",
            params![key, json_str, now],
        )?;
        Ok(())
    }

    pub fn delete(&self, key: &str) -> SqlResult<()> {
        let db = self.db.lock().unwrap();
        if let Some(table) = collection_table(key) {
            db.execute(&format!("DELETE FROM {}", table), [])?;
        } else {
            db.execute("DELETE FROM app_settings WHERE key = ?1", params![key])?;
        }
        Ok(())
    }

    // ─── History ───

    pub fn history_list(&self, keyword: Option<&str>, favorite_only: bool, limit: Option<i64>, offset: Option<i64>) -> Vec<Value> {
        let db = self.db.lock().unwrap();
        let mut where_clauses = Vec::new();
        let mut param_values: Vec<Box<dyn rusqlite::types::ToSql>> = Vec::new();

        if favorite_only {
            where_clauses.push("favorite = 1".to_string());
        }
        if let Some(kw) = keyword {
            let trimmed = kw.trim().to_lowercase();
            if !trimmed.is_empty() {
                where_clauses.push(format!("LOWER(raw_json) LIKE ?{}", param_values.len() + 1));
                param_values.push(Box::new(format!("%{}%", trimmed)));
            }
        }

        let where_sql = if where_clauses.is_empty() {
            String::new()
        } else {
            format!("WHERE {}", where_clauses.join(" AND "))
        };

        let limit_sql = match limit {
            Some(l) if l > 0 => {
                let off = offset.unwrap_or(0).max(0);
                format!(" LIMIT {} OFFSET {}", l, off)
            }
            _ => String::new(),
        };

        let sql = format!(
            "SELECT raw_json FROM history_records {} ORDER BY list_order ASC{}",
            where_sql, limit_sql
        );

        let params_ref: Vec<&dyn rusqlite::types::ToSql> = param_values.iter().map(|p| p.as_ref()).collect();
        let mut stmt = db.prepare(&sql).unwrap();
        let rows = stmt
            .query_map(params_ref.as_slice(), |row| {
                let json_str: String = row.get(0)?;
                Ok(json_str)
            })
            .unwrap();

        rows.filter_map(|r| r.ok())
            .filter_map(|s| serde_json::from_str(&s).ok())
            .collect()
    }

    pub fn history_count(&self, keyword: Option<&str>, favorite_only: bool) -> i64 {
        let db = self.db.lock().unwrap();
        let mut where_clauses = Vec::new();
        let mut param_values: Vec<Box<dyn rusqlite::types::ToSql>> = Vec::new();

        if favorite_only {
            where_clauses.push("favorite = 1".to_string());
        }
        if let Some(kw) = keyword {
            let trimmed = kw.trim().to_lowercase();
            if !trimmed.is_empty() {
                where_clauses.push(format!("LOWER(raw_json) LIKE ?{}", param_values.len() + 1));
                param_values.push(Box::new(format!("%{}%", trimmed)));
            }
        }

        let where_sql = if where_clauses.is_empty() {
            String::new()
        } else {
            format!("WHERE {}", where_clauses.join(" AND "))
        };

        let sql = format!("SELECT COUNT(*) FROM history_records {}", where_sql);
        let params_ref: Vec<&dyn rusqlite::types::ToSql> = param_values.iter().map(|p| p.as_ref()).collect();

        db.query_row(&sql, params_ref.as_slice(), |row| row.get(0)).unwrap_or(0)
    }

    pub fn history_add(&self, record: &Value) -> SqlResult<()> {
        let db = self.db.lock().unwrap();

        db.execute("UPDATE history_records SET list_order = list_order + 1", [])?;

        let obj = record.as_object().cloned().unwrap_or_default();
        let id = obj.get("id").and_then(|v| v.as_str()).unwrap_or("unknown");
        let timestamp = obj.get("timestamp").and_then(|v| v.as_i64()).unwrap_or(0);
        let favorite = obj.get("favorite").and_then(|v| v.as_bool()).unwrap_or(false);
        let char_count = obj.get("charCount").and_then(|v| v.as_i64()).unwrap_or(0);
        let duration_sec = obj.get("durationSec").and_then(|v| v.as_f64()).unwrap_or(0.0);
        let is_empty = obj.get("isEmpty").and_then(|v| v.as_bool()).unwrap_or(false);
        let app_id = obj.get("appId").and_then(|v| v.as_str());
        let app_name = obj.get("appName").and_then(|v| v.as_str());
        let audio_file_path = obj.get("audioFilePath").and_then(|v| v.as_str());
        let raw_json = serde_json::to_string(record).unwrap_or_else(|_| "{}".to_string());

        db.execute(
            "INSERT INTO history_records (id, list_order, timestamp, favorite, char_count, duration_sec, is_empty, app_id, app_name, audio_file_path, raw_json)
             VALUES (?1, 0, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
            params![id, timestamp, favorite as i32, char_count, duration_sec, is_empty as i32, app_id, app_name, audio_file_path, raw_json],
        )?;

        // Update stats
        self.update_stats_delta(&db, char_count, duration_sec, 1);
        Ok(())
    }

    pub fn history_update(&self, id: &str, patch: &Value) -> SqlResult<()> {
        let db = self.db.lock().unwrap();

        let row: Option<(i64, String)> = db.query_row(
            "SELECT list_order, raw_json FROM history_records WHERE id = ?1",
            params![id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        ).ok();

        let (_list_order, raw_json) = match row {
            Some(r) => r,
            None => return Ok(()),
        };

        let mut prev: serde_json::Map<String, Value> = serde_json::from_str(&raw_json).unwrap_or_default();
        let prev_chars = prev.get("charCount").and_then(|v| v.as_i64()).unwrap_or(0);
        let prev_dur = prev.get("durationSec").and_then(|v| v.as_f64()).unwrap_or(0.0);

        if let Some(patch_obj) = patch.as_object() {
            for (k, v) in patch_obj {
                prev.insert(k.clone(), v.clone());
            }
        }

        let next = Value::Object(prev.clone());
        let timestamp = prev.get("timestamp").and_then(|v| v.as_i64()).unwrap_or(0);
        let favorite = prev.get("favorite").and_then(|v| v.as_bool()).unwrap_or(false);
        let char_count = prev.get("charCount").and_then(|v| v.as_i64()).unwrap_or(0);
        let duration_sec = prev.get("durationSec").and_then(|v| v.as_f64()).unwrap_or(0.0);
        let is_empty = prev.get("isEmpty").and_then(|v| v.as_bool()).unwrap_or(false);
        let app_id = prev.get("appId").and_then(|v| v.as_str()).map(|s| s.to_string());
        let app_name = prev.get("appName").and_then(|v| v.as_str()).map(|s| s.to_string());
        let audio_file_path = prev.get("audioFilePath").and_then(|v| v.as_str()).map(|s| s.to_string());
        let new_json = serde_json::to_string(&next).unwrap_or_else(|_| "{}".to_string());

        db.execute(
            "UPDATE history_records SET timestamp=?1, favorite=?2, char_count=?3, duration_sec=?4, is_empty=?5, app_id=?6, app_name=?7, audio_file_path=?8, raw_json=?9 WHERE id=?10",
            params![timestamp, favorite as i32, char_count, duration_sec, is_empty as i32, app_id, app_name, audio_file_path, new_json, id],
        )?;

        // Update stats: subtract old, add new
        self.update_stats_replacement(&db, prev_chars, prev_dur, char_count, duration_sec);
        Ok(())
    }

    pub fn history_delete(&self, id: &str) -> SqlResult<()> {
        let db = self.db.lock().unwrap();

        let row: Option<(i64, String)> = db.query_row(
            "SELECT list_order, raw_json FROM history_records WHERE id = ?1",
            params![id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        ).ok();

        let (list_order, raw_json) = match row {
            Some(r) => r,
            None => return Ok(()),
        };

        let obj: serde_json::Map<String, Value> = serde_json::from_str(&raw_json).unwrap_or_default();
        let char_count = obj.get("charCount").and_then(|v| v.as_i64()).unwrap_or(0);
        let duration_sec = obj.get("durationSec").and_then(|v| v.as_f64()).unwrap_or(0.0);

        db.execute("DELETE FROM history_records WHERE id = ?1", params![id])?;
        db.execute("UPDATE history_records SET list_order = list_order - 1 WHERE list_order > ?1", params![list_order])?;

        self.update_stats_delta(&db, char_count, duration_sec, -1);
        Ok(())
    }

    pub fn history_set_favorite(&self, id: &str, favorite: bool) -> SqlResult<()> {
        self.history_update(id, &serde_json::json!({ "favorite": favorite }))
    }

    // ─── Collections ───

    fn read_collection(&self, key: &str) -> SqlResult<Value> {
        let table = collection_table(key).ok_or(rusqlite::Error::InvalidParameterName("unknown collection".into()))?;
        let db = self.db.lock().unwrap();
        let mut stmt = db.prepare(&format!("SELECT raw_json FROM {} ORDER BY list_order ASC", table))?;
        let rows = stmt.query_map([], |row| {
            let s: String = row.get(0)?;
            Ok(s)
        })?;

        let items: Vec<Value> = rows
            .filter_map(|r| r.ok())
            .filter_map(|s| serde_json::from_str(&s).ok())
            .collect();

        Ok(Value::Array(items))
    }

    fn replace_collection_on(db: &Connection, key: &str, items: &[Value]) -> SqlResult<()> {
        let table = collection_table(key).ok_or(rusqlite::Error::InvalidParameterName(
            "unknown collection".into(),
        ))?;

        db.execute(&format!("DELETE FROM {}", table), [])?;

        for (index, item) in items.iter().enumerate() {
            let obj = item.as_object();
            let id = obj
                .and_then(|o| o.get("id"))
                .and_then(|v| v.as_str())
                .unwrap_or("unknown")
                .to_string();
            let raw_json = serde_json::to_string(item).unwrap_or_else(|_| "{}".to_string());

            match key {
                "history" => {
                    let timestamp = obj
                        .and_then(|o| o.get("timestamp"))
                        .and_then(|v| v.as_i64())
                        .unwrap_or(0);
                    let favorite = obj
                        .and_then(|o| o.get("favorite"))
                        .and_then(|v| v.as_bool())
                        .unwrap_or(false);
                    let char_count = obj
                        .and_then(|o| o.get("charCount"))
                        .and_then(|v| v.as_i64())
                        .unwrap_or(0);
                    let duration_sec = obj
                        .and_then(|o| o.get("durationSec"))
                        .and_then(|v| v.as_f64())
                        .unwrap_or(0.0);
                    let is_empty = obj
                        .and_then(|o| o.get("isEmpty"))
                        .and_then(|v| v.as_bool())
                        .unwrap_or(false);
                    let app_id = obj
                        .and_then(|o| o.get("appId"))
                        .and_then(|v| v.as_str());
                    let app_name = obj
                        .and_then(|o| o.get("appName"))
                        .and_then(|v| v.as_str());
                    db.execute(
                        "INSERT INTO history_records (id, list_order, timestamp, favorite, char_count, duration_sec, is_empty, app_id, app_name, raw_json) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
                        params![id, index as i64, timestamp, favorite as i32, char_count, duration_sec, is_empty as i32, app_id, app_name, raw_json],
                    )?;
                }
                _ => {
                    // Generic collection insert (manual_corrections, feedback_queue, prompt_presets, app_prompt_rules)
                    let name = obj
                        .and_then(|o| o.get("name"))
                        .and_then(|v| v.as_str());
                    match table {
                        "manual_corrections" => {
                            let created_at = obj
                                .and_then(|o| o.get("createdAt"))
                                .and_then(|v| v.as_i64())
                                .unwrap_or(0);
                            let history_id = obj
                                .and_then(|o| o.get("historyId"))
                                .and_then(|v| v.as_str());
                            db.execute(
                                "INSERT INTO manual_corrections (id, list_order, created_at, history_id, raw_json) VALUES (?1,?2,?3,?4,?5)",
                                params![id, index as i64, created_at, history_id, raw_json],
                            )?;
                        }
                        "feedback_queue" => {
                            let created_at = obj
                                .and_then(|o| o.get("createdAt"))
                                .and_then(|v| v.as_i64())
                                .unwrap_or(0);
                            let history_id = obj
                                .and_then(|o| o.get("historyId"))
                                .and_then(|v| v.as_str());
                            let status = obj
                                .and_then(|o| o.get("status"))
                                .and_then(|v| v.as_str());
                            db.execute(
                                "INSERT INTO feedback_queue (id, list_order, created_at, history_id, status, raw_json) VALUES (?1,?2,?3,?4,?5,?6)",
                                params![id, index as i64, created_at, history_id, status, raw_json],
                            )?;
                        }
                        "prompt_presets" => {
                            db.execute(
                                "INSERT INTO prompt_presets (id, list_order, name, raw_json) VALUES (?1,?2,?3,?4)",
                                params![id, index as i64, name, raw_json],
                            )?;
                        }
                        "app_prompt_rules" => {
                            let app_id = obj
                                .and_then(|o| o.get("appId"))
                                .and_then(|v| v.as_str());
                            let enabled = obj
                                .and_then(|o| o.get("enabled"))
                                .and_then(|v| v.as_bool())
                                .unwrap_or(true);
                            let priority = obj
                                .and_then(|o| o.get("priority"))
                                .and_then(|v| v.as_i64())
                                .unwrap_or(0);
                            db.execute(
                                "INSERT INTO app_prompt_rules (id, list_order, app_id, name, enabled, priority, raw_json) VALUES (?1,?2,?3,?4,?5,?6,?7)",
                                params![id, index as i64, app_id, name, enabled as i32, priority, raw_json],
                            )?;
                        }
                        _ => {}
                    }
                }
            }
        }
        Ok(())
    }

    fn replace_collection(&self, key: &str, items: &[Value]) -> SqlResult<()> {
        let db = self.db.lock().unwrap();
        Self::replace_collection_on(&db, key, items)
    }

    // ─── Stats helpers ───

    fn update_stats_delta(&self, db: &Connection, char_count: i64, duration_sec: f64, direction: i64) {
        let stats_json: Option<String> = db.query_row(
            "SELECT value_json FROM app_settings WHERE key = 'stats'",
            [], |row| row.get(0),
        ).ok();

        let (mut total_dur, mut total_chars) = parse_stats(&stats_json);
        total_dur = (total_dur + duration_sec * direction as f64).max(0.0);
        total_chars = (total_chars + char_count * direction).max(0);

        let new_stats = serde_json::json!({"totalDurationSec": total_dur, "totalChars": total_chars});
        let now = chrono::Utc::now().timestamp_millis();
        let _ = db.execute(
            "INSERT INTO app_settings (key, value_json, updated_at) VALUES ('stats', ?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at",
            params![new_stats.to_string(), now],
        );
    }

    fn update_stats_replacement(&self, db: &Connection, prev_chars: i64, prev_dur: f64, next_chars: i64, next_dur: f64) {
        let stats_json: Option<String> = db.query_row(
            "SELECT value_json FROM app_settings WHERE key = 'stats'",
            [], |row| row.get(0),
        ).ok();

        let (mut total_dur, mut total_chars) = parse_stats(&stats_json);
        total_dur = (total_dur - prev_dur + next_dur).max(0.0);
        total_chars = (total_chars - prev_chars + next_chars).max(0);

        let new_stats = serde_json::json!({"totalDurationSec": total_dur, "totalChars": total_chars});
        let now = chrono::Utc::now().timestamp_millis();
        let _ = db.execute(
            "INSERT INTO app_settings (key, value_json, updated_at) VALUES ('stats', ?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at",
            params![new_stats.to_string(), now],
        );
    }
}

impl Storage {
    /// Migrate data from the Electron app's SQLite database (one-time).
    /// Looks for sayit.db in common Electron userData paths.
    pub fn migrate_from_electron(&self) -> Result<(), String> {
        let db = self.db.lock().unwrap();

        // Check if already migrated
        let already: bool = db.query_row(
            "SELECT COUNT(*) > 0 FROM db_meta WHERE key = 'electron_data_migrated_v2'",
            [],
            |row| row.get(0),
        ).unwrap_or(false);

        if already {
            log::info!("Electron data migration already done, skipping");
            return Ok(());
        }

        // Find the Electron DB
        let electron_db_path = Self::find_electron_db();
        let electron_path = match electron_db_path {
            Some(p) => p,
            None => {
                log::info!("No Electron sayit.db found, skipping migration");
                return Ok(());
            }
        };

        log::info!("Found Electron DB at: {:?}", electron_path);

        // Open the Electron DB read-only
        let src = Connection::open_with_flags(
            &electron_path,
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
        ).map_err(|e| format!("Failed to open Electron DB: {}", e))?;

        // Migrate each table
        let mut migrated_counts: Vec<(String, usize)> = Vec::new();

        // 1. app_settings
        let count = Self::migrate_table_settings(&src, &db);
        migrated_counts.push(("app_settings".into(), count));

        // 2. history_records
        let count = Self::migrate_table_generic(&src, &db, "history_records",
            "id, list_order, timestamp, favorite, char_count, duration_sec, is_empty, app_id, app_name, audio_file_path, raw_json");
        migrated_counts.push(("history_records".into(), count));

        // 3. manual_corrections
        let count = Self::migrate_table_generic(&src, &db, "manual_corrections",
            "id, list_order, created_at, history_id, raw_json");
        migrated_counts.push(("manual_corrections".into(), count));

        // 4. feedback_queue
        let count = Self::migrate_table_generic(&src, &db, "feedback_queue",
            "id, list_order, created_at, history_id, status, raw_json");
        migrated_counts.push(("feedback_queue".into(), count));

        // 5. prompt_presets
        let count = Self::migrate_table_generic(&src, &db, "prompt_presets",
            "id, list_order, name, raw_json");
        migrated_counts.push(("prompt_presets".into(), count));

        // 6. app_prompt_rules
        let count = Self::migrate_table_generic(&src, &db, "app_prompt_rules",
            "id, list_order, app_id, name, enabled, priority, raw_json");
        migrated_counts.push(("app_prompt_rules".into(), count));

        // Mark migration as done
        let now = chrono::Utc::now().timestamp_millis();
        let _ = db.execute(
            "INSERT INTO db_meta (key, value_json, updated_at) VALUES (?1, ?2, ?3)
             ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at",
            params![
                "electron_data_migrated_v2",
                now.to_string(),
                now
            ],
        );
        let _ = db.execute(
            "INSERT INTO db_meta (key, value_json, updated_at) VALUES (?1, ?2, ?3)
             ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at",
            params![
                "electron_data_migrated_at",
                now.to_string(),
                now
            ],
        );
        let _ = db.execute(
            "INSERT INTO db_meta (key, value_json, updated_at) VALUES (?1, ?2, ?3)
             ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at",
            params![
                "electron_db_source_path",
                serde_json::json!(electron_path.to_string_lossy()).to_string(),
                now
            ],
        );

        log::info!("Electron data migration complete: {:?}", migrated_counts);
        Ok(())
    }

    fn find_electron_db() -> Option<PathBuf> {
        // Electron's app.getPath('userData') on Windows is typically:
        //   C:\Users\<user>\AppData\Roaming\<appName>
        // The app name could be "SayIt", "sayit", or "SayIt-dev"
        let roaming = dirs::data_dir()?; // AppData\Roaming
        let candidates = [
            roaming.join("SayIt").join("sayit.db"),
            roaming.join("sayit").join("sayit.db"),
            roaming.join("SayIt-dev").join("sayit.db"),
            roaming.join("sayit-dev").join("sayit.db"),
        ];

        for path in &candidates {
            if path.exists() {
                return Some(path.clone());
            }
        }
        None
    }

    fn migrate_table_settings(src: &Connection, dst: &Connection) -> usize {
        let mut count = 0usize;
        let result = src.prepare("SELECT key, value_json, updated_at FROM app_settings");
        let mut stmt = match result {
            Ok(s) => s,
            Err(_) => return 0,
        };

        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
            ))
        });

        if let Ok(rows) = rows {
            for row in rows.flatten() {
                let (key, value_json, updated_at) = row;
                // Always overwrite: Electron data takes priority over seed defaults
                let res = dst.execute(
                    "INSERT INTO app_settings (key, value_json, updated_at) VALUES (?1, ?2, ?3)
                     ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at",
                    params![key, value_json, updated_at],
                );
                if res.is_ok() {
                    count += 1;
                }
            }
        }
        count
    }

    fn migrate_table_generic(src: &Connection, dst: &Connection, table: &str, columns: &str) -> usize {
        // Check if destination table already has data — skip if so
        let dst_count: i64 = dst.query_row(
            &format!("SELECT COUNT(*) FROM {}", table),
            [],
            |row| row.get(0),
        ).unwrap_or(0);

        if dst_count > 0 {
            log::info!("Table {} already has {} rows, skipping", table, dst_count);
            return 0;
        }

        let select_sql = format!("SELECT {} FROM {} ORDER BY list_order ASC", columns, table);
        let mut stmt = match src.prepare(&select_sql) {
            Ok(s) => s,
            Err(e) => {
                log::warn!("Cannot read source table {}: {}", table, e);
                return 0;
            }
        };

        let col_count = columns.split(',').count();
        let placeholders: Vec<String> = (1..=col_count).map(|i| format!("?{}", i)).collect();
        let insert_sql = format!(
            "INSERT OR IGNORE INTO {} ({}) VALUES ({})",
            table, columns, placeholders.join(", ")
        );

        let mut count = 0usize;
        let rows = stmt.query_map([], |row| {
            let mut values: Vec<rusqlite::types::Value> = Vec::with_capacity(col_count);
            for i in 0..col_count {
                values.push(row.get(i)?);
            }
            Ok(values)
        });

        if let Ok(rows) = rows {
            for row in rows.flatten() {
                let params: Vec<&dyn rusqlite::types::ToSql> = row.iter().map(|v| v as &dyn rusqlite::types::ToSql).collect();
                if dst.execute(&insert_sql, params.as_slice()).is_ok() {
                    count += 1;
                }
            }
        }
        count
    }
}

fn parse_stats(json: &Option<String>) -> (f64, i64) {
    match json {
        Some(s) => {
            let v: Value = serde_json::from_str(s).unwrap_or(Value::Null);
            let dur = v.get("totalDurationSec").and_then(|v| v.as_f64()).unwrap_or(0.0);
            let chars = v.get("totalChars").and_then(|v| v.as_i64()).unwrap_or(0);
            (dur, chars)
        }
        None => (0.0, 0),
    }
}

// ─── 备份 / 恢复辅助（导入导出用）───
impl Storage {
    /// 导出 app_settings 为 { key: value } 对象；exclude 中的 key 会被跳过。
    pub fn export_app_settings(&self, exclude: &[&str]) -> Value {
        let db = self.db.lock().unwrap();
        let mut map = serde_json::Map::new();
        if let Ok(mut stmt) = db.prepare("SELECT key, value_json FROM app_settings") {
            let rows = stmt.query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            });
            if let Ok(rows) = rows {
                for row in rows.flatten() {
                    let (key, value_json) = row;
                    if exclude.contains(&key.as_str()) {
                        continue;
                    }
                    // 导出为明文：备份/迁移需要可读、可跨机导入（DPAPI 绑定本机用户）。
                    let val: Value = match maybe_decrypt_value(&key, value_json) {
                        Ok(plain) => serde_json::from_str(&plain).unwrap_or(Value::Null),
                        Err(e) => {
                            log::error!("{}", e);
                            Value::Null
                        }
                    };
                    map.insert(key, val);
                }
            }
        }
        Value::Object(map)
    }

    /// 原子应用一组配置：设置 key 与可选集合要么全部成功，要么全部回滚。
    pub fn apply_config_transaction(
        &self,
        app_settings: &serde_json::Map<String, Value>,
        exclude: &[&str],
        prompt_presets: Option<&[Value]>,
        app_prompt_rules: Option<&[Value]>,
    ) -> SqlResult<()> {
        let mut db = self.db.lock().unwrap();
        let tx = db.transaction()?;
        let now = chrono::Utc::now().timestamp_millis();

        for (key, value) in app_settings {
            if exclude.contains(&key.as_str()) {
                continue;
            }
            let json_str = maybe_encrypt_value(
                key,
                serde_json::to_string(value).unwrap_or_else(|_| "null".to_string()),
            )
            .map_err(secret_err)?;
            tx.execute(
                "INSERT INTO app_settings (key, value_json, updated_at) VALUES (?1, ?2, ?3)
                 ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at",
                params![key, json_str, now],
            )?;
        }

        if let Some(items) = prompt_presets {
            Self::replace_collection_on(&tx, "promptPresets", items)?;
        }
        if let Some(items) = app_prompt_rules {
            Self::replace_collection_on(&tx, "appPromptRules", items)?;
        }

        tx.commit()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sensitive_key_covers_credential_containers() {
        // 凭据容器（PM 审查要求：cloudAi.profiles / cloudAsr.profiles 等）
        assert!(is_sensitive_key("cloudAi.profiles"));
        assert!(is_sensitive_key("cloudAsr.profiles"));
        // 常规敏感字段
        assert!(is_sensitive_key("cloudAsr.apiKey"));
        assert!(is_sensitive_key("cloudAsr.doubao.consoleKey"));
        assert!(is_sensitive_key("serverToken"));
        assert!(is_sensitive_key("cloudAi.deepseek.apiKey"));
        assert!(is_sensitive_key("cloudAsr.appId"));
        // 非敏感 key 不误伤
        assert!(!is_sensitive_key("theme"));
        assert!(!is_sensitive_key("workMode"));
        assert!(!is_sensitive_key("cloudAi.model"));
        assert!(!is_sensitive_key("cloudAsr.provider"));
    }

    #[test]
    fn encryption_refuses_to_fall_back_to_plaintext() {
        // 非敏感 key 原样返回
        assert_eq!(maybe_encrypt_value("theme", "dark".to_string()).unwrap(), "dark");
        // 敏感 key：要么成功且带 dpapi: 前缀，要么 Err —— 绝不等于明文
        let r = maybe_encrypt_value("cloudAsr.apiKey", "sk-plain-secret".to_string());
        match r {
            Ok(v) => assert!(v.starts_with(DPAPI_PREFIX), "encrypted value must be dpapi: prefixed"),
            Err(_) => {}
        }
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn dpapi_roundtrip() {
        let plain = r#"{"apiKey":"sk-secret-value"}"#;
        let enc = maybe_encrypt_value("cloudAi.profiles", plain.to_string()).expect("encrypt ok");
        assert!(enc.starts_with(DPAPI_PREFIX));
        assert_ne!(enc, plain);
        let dec = maybe_decrypt_value("cloudAi.profiles", enc).expect("decrypt ok");
        assert_eq!(dec, plain);
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn migration_encrypts_existing_plaintext() {
        let dir = std::env::temp_dir().join(format!("sayit_store_test_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let db_path = dir.join("sayit.db");
        {
            // 预置明文敏感记录，模拟历史数据
            let conn = Connection::open(&db_path).unwrap();
            conn.execute_batch(
                "CREATE TABLE IF NOT EXISTS app_settings (
                    key TEXT PRIMARY KEY, value_json TEXT NOT NULL, updated_at INTEGER NOT NULL
                )",
            )
            .unwrap();
            conn.execute(
                "INSERT INTO app_settings(key, value_json, updated_at) VALUES(?1, ?2, ?3)",
                params!["cloudAi.profiles", r#"[{"provider":"deepseek","apiKey":"sk-old-plain"}]"#, 0],
            )
            .unwrap();
        }
        let storage = Storage::new(db_path.clone()).expect("storage init");
        // 迁移后读取仍返回解密后的明文值（对应用透明）
        let val = storage.get("cloudAi.profiles", None);
        assert!(val.to_string().contains("sk-old-plain"));
        // 落盘必须是加密态
        let db = storage.db.lock().unwrap();
        let stored: String = db
            .query_row(
                "SELECT value_json FROM app_settings WHERE key='cloudAi.profiles'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert!(stored.starts_with(DPAPI_PREFIX), "stored value must be encrypted after migration");
        drop(db);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 测试守卫：作用域内强制 DPAPI 加密失败，离开作用域（含断言失败）时自动恢复。
    struct DpapiFailGuard;

    impl DpapiFailGuard {
        fn enable() -> Self {
            set_force_dpapi_fail(true);
            DpapiFailGuard
        }
    }

    impl Drop for DpapiFailGuard {
        fn drop(&mut self) {
            set_force_dpapi_fail(false);
        }
    }

    #[cfg(target_os = "windows")]
    fn count_rows(db_path: &std::path::Path, key: &str) -> i64 {
        let conn = Connection::open(db_path).unwrap();
        conn.query_row(
            "SELECT COUNT(*) FROM app_settings WHERE key = ?1",
            params![key],
            |r| r.get(0),
        )
        .unwrap()
    }

    /// fail-closed：懒迁移时 DPAPI 加密失败 → 返回 fallback（绝不明文），且明文行被删除。
    #[cfg(target_os = "windows")]
    #[test]
    fn get_fails_closed_when_dpapi_encryption_fails_on_legacy_plaintext() {
        let dir = std::env::temp_dir().join(format!("sayit_failclosed_dpapi_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let db_path = dir.join("sayit.db");
        let storage = Storage::new(db_path.clone()).expect("storage init");
        // 在启动迁移之后直接塞入明文敏感行，模拟「迁移没覆盖到的遗留明文」
        {
            let db = storage.db.lock().unwrap();
            db.execute(
                "INSERT INTO app_settings(key, value_json, updated_at) VALUES(?1, ?2, ?3)",
                params!["cloudAi.apiKey", r#""sk-legacy-plain-leak""#, 0],
            )
            .unwrap();
        }

        let _guard = DpapiFailGuard::enable();
        let val = storage.get("cloudAi.apiKey", None);
        // 绝不返回明文
        assert!(!val.to_string().contains("sk-legacy-plain-leak"));
        assert!(val.is_null(), "must return fallback/Null, got {:?}", val);
        // 数据库不保留明文行
        assert_eq!(count_rows(&db_path, "cloudAi.apiKey"), 0, "plaintext row must be removed");
        drop(_guard);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// fail-closed：懒迁移时写库（UPDATE）失败 → 返回 fallback（绝不明文），且明文行被删除。
    #[cfg(target_os = "windows")]
    #[test]
    fn get_fails_closed_when_db_update_fails_on_legacy_plaintext() {
        let dir = std::env::temp_dir().join(format!("sayit_failclosed_db_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let db_path = dir.join("sayit.db");
        let storage = Storage::new(db_path.clone()).expect("storage init");
        {
            let db = storage.db.lock().unwrap();
            db.execute(
                "INSERT INTO app_settings(key, value_json, updated_at) VALUES(?1, ?2, ?3)",
                params!["serverToken", r#""tok-legacy-plain-leak""#, 0],
            )
            .unwrap();
            // 触发器让 UPDATE 确定失败（DELETE 不受影响）
            db.execute_batch(
                "CREATE TRIGGER block_settings_update BEFORE UPDATE ON app_settings
                 BEGIN SELECT RAISE(ABORT, 'forced update failure'); END;",
            )
            .unwrap();
        }

        let val = storage.get("serverToken", None);
        assert!(!val.to_string().contains("tok-legacy-plain-leak"));
        assert!(val.is_null(), "must return fallback/Null, got {:?}", val);
        assert_eq!(count_rows(&db_path, "serverToken"), 0, "plaintext row must be removed");

        // 触发器不影响非敏感配置的正常读取
        let theme = storage.get("theme", None);
        assert!(theme.is_string() || theme.is_null());

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// fail-closed：启动迁移时 DPAPI 加密失败 → 明文行被删除，读取返回 fallback；
    /// 非敏感配置不受影响。
    #[cfg(target_os = "windows")]
    #[test]
    fn migration_failure_removes_plaintext_and_preserves_non_sensitive() {
        let dir = std::env::temp_dir().join(format!("sayit_failclosed_migrate_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let db_path = dir.join("sayit.db");
        {
            let conn = Connection::open(&db_path).unwrap();
            conn.execute_batch(
                "CREATE TABLE IF NOT EXISTS app_settings (
                    key TEXT PRIMARY KEY, value_json TEXT NOT NULL, updated_at INTEGER NOT NULL
                )",
            )
            .unwrap();
            conn.execute(
                "INSERT INTO app_settings(key, value_json, updated_at) VALUES(?1, ?2, ?3)",
                params!["cloudAsr.apiKey", r#""sk-migrate-plain""#, 0],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO app_settings(key, value_json, updated_at) VALUES(?1, ?2, ?3)",
                params!["theme", r#""dark""#, 0],
            )
            .unwrap();
        }

        let _guard = DpapiFailGuard::enable();
        let storage = Storage::new(db_path.clone()).expect("storage init");
        // 敏感行被删除、不可读
        assert_eq!(count_rows(&db_path, "cloudAsr.apiKey"), 0, "plaintext secret row must be removed");
        let val = storage.get("cloudAsr.apiKey", None);
        assert!(!val.to_string().contains("sk-migrate-plain"));
        // 非敏感配置完好
        let theme = storage.get("theme", None);
        assert_eq!(theme.as_str(), Some("dark"), "non-sensitive config must survive");
        drop(_guard);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
