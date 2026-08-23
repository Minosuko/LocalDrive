<?php

function mobile_project_root() {
    return dirname(__DIR__, 2);
}

function mobile_storage_root() {
    $override = getenv('CLOUDDRIVE_MOBILE_STORAGE_ROOT');
    return $override !== false && trim($override) !== ''
        ? rtrim(trim($override), '/\\')
        : mobile_project_root() . DIRECTORY_SEPARATOR . 'storage';
}

function mobile_db() {
    static $database = null;
    if ($database instanceof PDO) return $database;

    $system = mobile_storage_root() . DIRECTORY_SEPARATOR . 'system';
    if (!is_dir($system) && !mkdir($system, 0755, true) && !is_dir($system)) {
        mobile_error('Account storage is unavailable', 500);
    }
    $database = new PDO('sqlite:' . $system . DIRECTORY_SEPARATOR . 'mobile-api.sqlite', null, null, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
    $database->exec('PRAGMA busy_timeout = 5000');
    $database->exec('PRAGMA foreign_keys = ON');
    $database->exec('PRAGMA synchronous = NORMAL');
    if ((int)$database->query('PRAGMA user_version')->fetchColumn() < 1) {
        mobile_migrate_database($database);
    }
    return $database;
}

function mobile_migrate_database(PDO $database) {
    $database->exec('PRAGMA journal_mode = WAL');
    $database->exec('BEGIN IMMEDIATE');
    try {
        if ((int)$database->query('PRAGMA user_version')->fetchColumn() >= 1) {
            $database->exec('COMMIT');
            return;
        }
    $database->exec(<<<'SQL'
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    public_id TEXT NOT NULL UNIQUE,
    username TEXT NOT NULL,
    username_norm TEXT NOT NULL UNIQUE COLLATE NOCASE,
    display_name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    storage_key TEXT NOT NULL UNIQUE,
    role TEXT NOT NULL DEFAULT 'user' CHECK (role IN ('root', 'user')),
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    quota_bytes INTEGER CHECK (quota_bytes IS NULL OR quota_bytes >= 0),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_login_at INTEGER
)
SQL);
        $columns = $database->query('PRAGMA table_info(users)')->fetchAll();
        if (!array_filter($columns, static fn($column) => $column['name'] === 'role')) {
            $database->exec("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'user'");
        }
        $rootCount = (int)$database->query("SELECT COUNT(*) FROM users WHERE role = 'root'")->fetchColumn();
        if ($rootCount === 0) {
            $firstUser = $database->query('SELECT id FROM users ORDER BY id LIMIT 1')->fetchColumn();
            if ($firstUser !== false) $database->exec("UPDATE users SET role = 'root' WHERE id = " . (int)$firstUser);
        }
        $database->exec("CREATE UNIQUE INDEX IF NOT EXISTS users_single_root_idx ON users(role) WHERE role = 'root'");
        $database->exec(<<<'SQL'
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    device_id TEXT,
    device_name TEXT,
    access_token_hash TEXT NOT NULL UNIQUE,
    access_expires_at INTEGER NOT NULL,
    refresh_token_hash TEXT NOT NULL UNIQUE,
    refresh_expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    last_used_at INTEGER,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
)
SQL);
        $database->exec('CREATE INDEX IF NOT EXISTS sessions_user_idx ON sessions(user_id)');
        $database->exec('CREATE INDEX IF NOT EXISTS sessions_access_expiry_idx ON sessions(access_expires_at)');
        $database->exec('CREATE INDEX IF NOT EXISTS sessions_refresh_expiry_idx ON sessions(refresh_expires_at)');
        $database->exec("UPDATE users SET status = 'disabled', updated_at = " . time() . " WHERE role <> 'root' AND status <> 'disabled'");
        $database->exec("DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE role <> 'root')");
        $database->exec(<<<'SQL'
CREATE TABLE IF NOT EXISTS login_attempts (
    key_hash TEXT PRIMARY KEY,
    failures INTEGER NOT NULL,
    window_started INTEGER NOT NULL,
    last_attempt INTEGER NOT NULL,
    blocked_until INTEGER
)
SQL);
        $database->exec('PRAGMA user_version = 1');
        $database->exec('COMMIT');
    } catch (Throwable $error) {
        try { $database->exec('ROLLBACK'); } catch (Throwable $ignored) {}
        throw $error;
    }
}

function mobile_initialize($requireAuthentication = true) {
    if (!defined('MOBILE_API_REQUEST')) define('MOBILE_API_REQUEST', true);
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: no-referrer');
    header('Cache-Control: no-store');
    if (strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'OPTIONS') {
        header('Allow: GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS');
        http_response_code(204);
        exit;
    }
    $length = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($length > 0 && $length > 1024 * 1024 * 1024) mobile_error('Request body is too large', 413);
    return $requireAuthentication ? mobile_require_principal() : null;
}

function mobile_json_input($maximumBytes = 65536) {
    $length = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($length > $maximumBytes) mobile_error('Request body is too large', 413);
    $raw = file_get_contents('php://input');
    $data = json_decode($raw === false ? '' : $raw, true);
    if (!is_array($data)) mobile_error('Invalid JSON body', 400);
    return $data;
}

function mobile_response($data = null, $status = 200) {
    $json = json_encode(
        ['success' => true, 'data' => $data],
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
    );
    if ($json === false) mobile_error('Could not encode response', 500);
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Content-Length: ' . strlen($json));
    echo $json;
    exit;
}

function mobile_error($message, $status = 400) {
    $json = json_encode(
        ['success' => false, 'error' => $message],
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
    );
    if ($json === false) $json = '{"success":false,"error":"Request failed"}';
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    if ($status === 401) header('WWW-Authenticate: Bearer realm="CloudDrive"');
    header('Content-Length: ' . strlen($json));
    echo $json;
    exit;
}

function mobile_require_method($methods) {
    $method = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');
    $allowed = is_array($methods) ? $methods : [$methods];
    if (!in_array($method, $allowed, true)) mobile_error('Method not allowed', 405);
    return $method;
}

function mobile_random_token() {
    return rtrim(strtr(base64_encode(random_bytes(32)), '+/', '-_'), '=');
}

function mobile_token_hash($token) {
    return hash('sha256', (string)$token);
}

function mobile_browser_cookie_name() {
    return 'clouddrive_root_session';
}

function mobile_set_browser_cookie($refreshToken, $expiresAt) {
    $secure = !empty($_SERVER['HTTPS']) && strtolower((string)$_SERVER['HTTPS']) !== 'off';
    setcookie(mobile_browser_cookie_name(), (string)$refreshToken, [
        'expires' => (int)$expiresAt,
        'path' => '/',
        'secure' => $secure,
        'httponly' => true,
        'samesite' => 'Strict',
    ]);
}

function mobile_clear_browser_cookie() {
    $secure = !empty($_SERVER['HTTPS']) && strtolower((string)$_SERVER['HTTPS']) !== 'off';
    setcookie(mobile_browser_cookie_name(), '', [
        'expires' => time() - 3600,
        'path' => '/',
        'secure' => $secure,
        'httponly' => true,
        'samesite' => 'Strict',
    ]);
}

function mobile_normalize_username($username) {
    $username = trim((string)$username);
    if (!preg_match('/^[A-Za-z0-9._-]{3,64}$/', $username)) {
        mobile_error('Username must be 3-64 letters, numbers, dots, dashes, or underscores');
    }
    return strtolower($username);
}

function mobile_validate_password($password) {
    $password = (string)$password;
    $length = strlen($password);
    if ($length < 10 || $length > 128) mobile_error('Password must be 10-128 characters');
    return $password;
}

function mobile_user_payload($user) {
    return [
        'id' => $user['public_id'],
        'username' => $user['username'],
        'display_name' => $user['display_name'],
        'role' => $user['role'],
        'quota_bytes' => $user['quota_bytes'] === null ? null : (int)$user['quota_bytes'],
    ];
}

function mobile_issue_session(PDO $database, $user, $deviceId, $deviceName, $sessionId = null) {
    $now = time();
    $accessToken = mobile_random_token();
    $refreshToken = mobile_random_token();
    $accessExpires = $now + 3600;
    $refreshExpires = $now + 30 * 86400;
    $sessionId = $sessionId ?: bin2hex(random_bytes(16));
    $database->prepare('DELETE FROM sessions WHERE refresh_expires_at <= ?')->execute([$now]);
    $statement = $database->prepare(<<<'SQL'
INSERT INTO sessions (
    id, user_id, device_id, device_name, access_token_hash, access_expires_at,
    refresh_token_hash, refresh_expires_at, created_at, last_used_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
SQL);
    $statement->execute([
        $sessionId,
        $user['id'],
        trim((string)$deviceId) ?: null,
        trim((string)$deviceName) ?: null,
        mobile_token_hash($accessToken),
        $accessExpires,
        mobile_token_hash($refreshToken),
        $refreshExpires,
        $now,
        $now,
    ]);
    return [
        'access_token' => $accessToken,
        'token_type' => 'Bearer',
        'expires_in' => $accessExpires - $now,
        'access_expires_at' => $accessExpires,
        'refresh_token' => $refreshToken,
        'refresh_expires_in' => $refreshExpires - $now,
        'refresh_expires_at' => $refreshExpires,
        'user' => mobile_user_payload($user),
    ];
}

function mobile_register($input) {
    $database = mobile_db();
    if ((int)$database->query("SELECT COUNT(*) FROM users WHERE role = 'root'")->fetchColumn() > 0) {
        mobile_error('Root account is already configured. Sign in as root.', 403);
    }
    $username = trim((string)($input['username'] ?? ''));
    $normalized = mobile_normalize_username($username);
    $password = mobile_validate_password($input['password'] ?? '');
    $displayName = trim((string)($input['display_name'] ?? $username));
    if ($displayName === '' || mb_strlen($displayName) > 80) mobile_error('Display name must be 1-80 characters');
    $now = time();
    try {
        $database->beginTransaction();
        $statement = $database->prepare(<<<'SQL'
INSERT INTO users (
    public_id, username, username_norm, display_name, password_hash, storage_key,
    role, status, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, 'root', 'active', ?, ?)
SQL);
        $statement->execute([
            bin2hex(random_bytes(16)),
            $username,
            $normalized,
            $displayName,
            password_hash($password, PASSWORD_DEFAULT),
            bin2hex(random_bytes(16)),
            $now,
            $now,
        ]);
        $user = $database->query('SELECT * FROM users WHERE id = ' . (int)$database->lastInsertId())->fetch();
        $session = mobile_issue_session($database, $user, $input['device_id'] ?? '', $input['device_name'] ?? '');
        $database->commit();
        mobile_create_root_directories();
        return $session;
    } catch (PDOException $error) {
        if ($database->inTransaction()) $database->rollBack();
        if ((string)$error->getCode() === '23000' || str_contains($error->getMessage(), 'UNIQUE constraint')) {
            mobile_error('That username is unavailable', 409);
        }
        mobile_error('Could not create account', 500);
    }
}

function mobile_attempt_key($normalizedUsername) {
    return hash('sha256', ($_SERVER['REMOTE_ADDR'] ?? 'local') . '|' . $normalizedUsername);
}

function mobile_check_login_limit(PDO $database, $key) {
    $statement = $database->prepare('SELECT blocked_until FROM login_attempts WHERE key_hash = ?');
    $statement->execute([$key]);
    $blockedUntil = (int)($statement->fetchColumn() ?: 0);
    if ($blockedUntil > time()) mobile_error('Too many attempts. Try again shortly.', 429);
}

function mobile_record_login_failure(PDO $database, $key) {
    $now = time();
    $statement = $database->prepare('SELECT failures, window_started FROM login_attempts WHERE key_hash = ?');
    $statement->execute([$key]);
    $current = $statement->fetch();
    $withinWindow = $current && $now - (int)$current['window_started'] < 900;
    $failures = $withinWindow ? (int)$current['failures'] + 1 : 1;
    $window = $withinWindow ? (int)$current['window_started'] : $now;
    $blockedUntil = $failures >= 5 ? $now + min(900, 30 * ($failures - 4)) : null;
    $statement = $database->prepare(<<<'SQL'
INSERT INTO login_attempts (key_hash, failures, window_started, last_attempt, blocked_until)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT(key_hash) DO UPDATE SET failures = excluded.failures,
window_started = excluded.window_started, last_attempt = excluded.last_attempt,
blocked_until = excluded.blocked_until
SQL);
    $statement->execute([$key, $failures, $window, $now, $blockedUntil]);
}

function mobile_login($input) {
    $database = mobile_db();
    $username = trim((string)($input['username'] ?? ''));
    $normalized = mobile_normalize_username($username);
    $password = (string)($input['password'] ?? '');
    $attemptKey = mobile_attempt_key($normalized);
    mobile_check_login_limit($database, $attemptKey);
    $statement = $database->prepare("SELECT * FROM users WHERE username_norm = ? AND role = 'root' AND status = 'active'");
    $statement->execute([$normalized]);
    $user = $statement->fetch();
    if (!$user || !password_verify($password, $user['password_hash'])) {
        mobile_record_login_failure($database, $attemptKey);
        mobile_error('Invalid username or password', 401);
    }
    $database->prepare('DELETE FROM login_attempts WHERE key_hash = ?')->execute([$attemptKey]);
    $now = time();
    if (password_needs_rehash($user['password_hash'], PASSWORD_DEFAULT)) {
        $database->prepare('UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?')
            ->execute([password_hash($password, PASSWORD_DEFAULT), $now, $user['id']]);
    }
    $database->prepare('UPDATE users SET last_login_at = ?, updated_at = ? WHERE id = ?')->execute([$now, $now, $user['id']]);
    return mobile_issue_session($database, $user, $input['device_id'] ?? '', $input['device_name'] ?? '');
}

function mobile_refresh($input) {
    $token = (string)($input['refresh_token'] ?? '');
    if ($token === '') mobile_error('Refresh token is required', 401);
    $database = mobile_db();
    $statement = $database->prepare(<<<'SQL'
SELECT sessions.*, users.public_id, users.username, users.display_name, users.quota_bytes,
users.storage_key, users.role, users.status
FROM sessions JOIN users ON users.id = sessions.user_id
WHERE sessions.refresh_token_hash = ? AND users.role = 'root'
SQL);
    $statement->execute([mobile_token_hash($token)]);
    $row = $statement->fetch();
    if (!$row || $row['status'] !== 'active' || (int)$row['refresh_expires_at'] <= time()) {
        mobile_error('Session expired', 401);
    }
    $user = [
        'id' => $row['user_id'],
        'public_id' => $row['public_id'],
        'username' => $row['username'],
        'display_name' => $row['display_name'],
        'role' => $row['role'],
        'quota_bytes' => $row['quota_bytes'],
    ];
    $database->beginTransaction();
    try {
        $database->prepare('DELETE FROM sessions WHERE id = ?')->execute([$row['id']]);
        $session = mobile_issue_session($database, $user, $row['device_id'] ?? '', $row['device_name'] ?? '', $row['id']);
        $database->commit();
        return $session;
    } catch (Throwable $error) {
        if ($database->inTransaction()) $database->rollBack();
        mobile_error('Could not refresh session', 500);
    }
}

function mobile_authorization_header() {
    $authorization = trim((string)($_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? ''));
    if ($authorization === '' && isset($_SERVER['PHP_AUTH_USER'])) {
        $authorization = 'Basic ' . base64_encode(
            (string)$_SERVER['PHP_AUTH_USER'] . ':' . (string)($_SERVER['PHP_AUTH_PW'] ?? '')
        );
    }
    return $authorization;
}

function mobile_session_principal($token, $refresh = false) {
    if (!is_string($token) || !preg_match('/^[A-Za-z0-9_-]{20,}$/', $token)) return null;
    $database = mobile_db();
    $hashColumn = $refresh ? 'sessions.refresh_token_hash' : 'sessions.access_token_hash';
    $sql = sprintf(<<<'SQL'
SELECT sessions.id AS session_id, sessions.access_expires_at, sessions.refresh_expires_at, sessions.last_used_at,
users.id AS user_id, users.public_id, users.username, users.display_name,
users.storage_key, users.quota_bytes, users.role, users.status, users.created_at, users.last_login_at
FROM sessions JOIN users ON users.id = sessions.user_id
WHERE %s = ? AND users.role = 'root'
SQL, $hashColumn);
    $statement = $database->prepare($sql);
    $statement->execute([mobile_token_hash($token)]);
    $principal = $statement->fetch();
    $now = time();
    $expiresAt = $refresh ? (int)($principal['refresh_expires_at'] ?? 0) : (int)($principal['access_expires_at'] ?? 0);
    if (!$principal || $principal['status'] !== 'active' || $expiresAt <= $now) return null;
    if ($now - (int)($principal['last_used_at'] ?? 0) >= 300) {
        $database->prepare('UPDATE sessions SET last_used_at = ? WHERE id = ?')->execute([$now, $principal['session_id']]);
    }
    $principal['auth_type'] = $refresh ? 'cookie' : 'bearer';
    return $principal;
}

function mobile_basic_principal($authorization = null) {
    $authorization = $authorization === null ? mobile_authorization_header() : trim((string)$authorization);
    if (!preg_match('/^Basic\s+(.+)$/i', $authorization, $matches)) return null;
    $decoded = base64_decode($matches[1], true);
    if ($decoded === false || strpos($decoded, ':') === false) return null;
    [$username, $password] = explode(':', $decoded, 2);
    $username = trim($username);
    if (strpos($username, '\\') !== false) $username = substr($username, strrpos($username, '\\') + 1);
    if (strpos($username, '@') !== false) $username = strstr($username, '@', true);
    $normalized = strtolower(trim($username));
    if (!preg_match('/^[a-z0-9._-]{3,64}$/', $normalized)) return null;
    $database = mobile_db();
    $statement = $database->prepare("SELECT * FROM users WHERE username_norm = ? AND role = 'root' AND status = 'active'");
    $statement->execute([$normalized]);
    $user = $statement->fetch();
    if (!$user) return null;
    if (!mobile_basic_auth_cache_valid($authorization, $user)) {
        if (!password_verify($password, $user['password_hash'])) return null;
        mobile_remember_basic_auth($authorization, $user);
    }
    return [
        'session_id' => null,
        'user_id' => $user['id'],
        'public_id' => $user['public_id'],
        'username' => $user['username'],
        'display_name' => $user['display_name'],
        'storage_key' => $user['storage_key'],
        'quota_bytes' => $user['quota_bytes'],
        'role' => $user['role'],
        'status' => $user['status'],
        'created_at' => $user['created_at'],
        'last_login_at' => $user['last_login_at'],
        'auth_type' => 'basic',
    ];
}

function mobile_basic_auth_cache_valid($authorization, $user) {
    $cacheFile = mobile_basic_auth_cache_file($authorization);
    if (!is_file($cacheFile) || time() - (@filemtime($cacheFile) ?: 0) > 600) return false;
    $cached = json_decode((string)@file_get_contents($cacheFile), true);
    return is_array($cached)
        && (int)($cached['user_id'] ?? 0) === (int)$user['id']
        && hash_equals((string)($cached['password'] ?? ''), hash('sha256', $user['password_hash']));
}

function mobile_remember_basic_auth($authorization, $user) {
    $cacheFile = mobile_basic_auth_cache_file($authorization);
    $data = json_encode([
        'user_id' => (int)$user['id'],
        'password' => hash('sha256', $user['password_hash']),
    ], JSON_UNESCAPED_SLASHES);
    $temporary = $cacheFile . '.' . bin2hex(random_bytes(6)) . '.tmp';
    if (@file_put_contents($temporary, $data, LOCK_EX) !== false) {
        if (!@rename($temporary, $cacheFile)) @unlink($temporary);
    } else {
        @unlink($temporary);
    }
}

function mobile_basic_auth_cache_file($authorization) {
    $directory = mobile_storage_root() . DIRECTORY_SEPARATOR . 'system' . DIRECTORY_SEPARATOR . 'basic-auth-cache';
    if (!is_dir($directory)) @mkdir($directory, 0700, true);
    $key = hash_hmac('sha256', $authorization, mobile_basic_auth_cache_secret());
    return $directory . DIRECTORY_SEPARATOR . $key . '.json';
}

function mobile_basic_auth_cache_secret() {
    static $secret = null;
    if ($secret !== null) return $secret;
    $system = mobile_storage_root() . DIRECTORY_SEPARATOR . 'system';
    $path = $system . DIRECTORY_SEPARATOR . 'basic-auth-cache.key';
    $stored = @file_get_contents($path);
    if (is_string($stored) && strlen($stored) >= 32) return $secret = $stored;

    $lock = @fopen($path . '.lock', 'c');
    if (is_resource($lock)) @flock($lock, LOCK_EX);
    try {
        $stored = @file_get_contents($path);
        if (is_string($stored) && strlen($stored) >= 32) return $secret = $stored;
        $stored = random_bytes(32);
        $temporary = $path . '.' . bin2hex(random_bytes(6)) . '.tmp';
        if (@file_put_contents($temporary, $stored, LOCK_EX) === false || !@rename($temporary, $path)) {
            @unlink($temporary);
            return $secret = $stored;
        }
        @chmod($path, 0600);
        return $secret = $stored;
    } finally {
        if (is_resource($lock)) {
            @flock($lock, LOCK_UN);
            fclose($lock);
        }
    }
}

function mobile_request_principal($allowCookie = true, $allowBasic = false) {
    $authorization = mobile_authorization_header();
    if (preg_match('/^Bearer\s+([A-Za-z0-9_-]{20,})$/i', $authorization, $matches)) {
        $principal = mobile_session_principal($matches[1]);
        if ($principal) return $principal;
    }
    if ($allowBasic && stripos($authorization, 'Basic ') === 0) {
        $principal = mobile_basic_principal($authorization);
        if ($principal) return $principal;
    }
    if ($allowCookie) {
        $cookie = (string)($_COOKIE[mobile_browser_cookie_name()] ?? '');
        $principal = mobile_session_principal($cookie, true);
        if ($principal) return $principal;
    }
    return null;
}

function mobile_require_principal() {
    $principal = mobile_request_principal(true, false);
    if (!$principal) mobile_error('Authentication required', 401);
    return $principal;
}

function mobile_logout($principal) {
    if (!empty($principal['session_id'])) {
        mobile_db()->prepare('DELETE FROM sessions WHERE id = ?')->execute([$principal['session_id']]);
    }
    mobile_clear_browser_cookie();
}

function mobile_update_root_account($principal, $input) {
    mobile_require_root($principal);
    $database = mobile_db();
    $statement = $database->prepare('SELECT * FROM users WHERE id = ? AND role = \'root\'');
    $statement->execute([$principal['user_id']]);
    $user = $statement->fetch();
    $currentPassword = (string)($input['current_password'] ?? '');
    if (!$user || !password_verify($currentPassword, $user['password_hash'])) {
        mobile_error('Current password is incorrect', 401);
    }

    $updates = [];
    $values = [];
    if (array_key_exists('display_name', $input)) {
        $displayName = trim((string)$input['display_name']);
        if ($displayName === '' || mb_strlen($displayName) > 80) mobile_error('Display name must be 1-80 characters');
        $updates[] = 'display_name = ?';
        $values[] = $displayName;
    }
    $newPassword = (string)($input['new_password'] ?? '');
    if ($newPassword !== '') {
        $updates[] = 'password_hash = ?';
        $values[] = password_hash(mobile_validate_password($newPassword), PASSWORD_DEFAULT);
    }
    if (!$updates) mobile_error('No account changes supplied');
    $updates[] = 'updated_at = ?';
    $values[] = time();
    $values[] = $user['id'];
    $database->prepare('UPDATE users SET ' . implode(', ', $updates) . ' WHERE id = ?')->execute($values);
    if ($newPassword !== '' && !empty($principal['session_id'])) {
        $database->prepare('DELETE FROM sessions WHERE user_id = ? AND id <> ?')->execute([$user['id'], $principal['session_id']]);
    }
    $statement->execute([$principal['user_id']]);
    return mobile_user_payload($statement->fetch());
}

function mobile_require_root($principal) {
    if (($principal['role'] ?? '') !== 'root') mobile_error('Root account required', 403);
    return $principal;
}

function mobile_list_users() {
    $statement = mobile_db()->query(<<<'SQL'
SELECT public_id, username, display_name, role, status, quota_bytes, created_at, last_login_at
FROM users WHERE role = 'root' ORDER BY username_norm
SQL);
    return array_map(static function ($user) {
        return [
            'id' => $user['public_id'],
            'username' => $user['username'],
            'display_name' => $user['display_name'],
            'role' => $user['role'],
            'status' => $user['status'],
            'quota_bytes' => $user['quota_bytes'] === null ? null : (int)$user['quota_bytes'],
            'created_at' => (int)$user['created_at'],
            'last_login_at' => $user['last_login_at'] === null ? null : (int)$user['last_login_at'],
        ];
    }, $statement->fetchAll());
}

function mobile_create_root_directories() {
    $root = mobile_storage_root();
    foreach (['main', 'chunk', 'cache', 'trash'] as $name) {
        $directory = $root . DIRECTORY_SEPARATOR . $name;
        if (!is_dir($directory) && !mkdir($directory, 0755, true) && !is_dir($directory)) {
            mobile_error('Could not initialize CloudDrive storage', 500);
        }
    }
    return $root;
}

function mobile_prepare_root_storage($principal = null) {
    $principal = mobile_require_root($principal ?: mobile_initialize(true));
    $root = mobile_storage_root();
    if (!is_dir($root)) $root = mobile_create_root_directories();
    if (!defined('STORAGE_ROOT')) define('STORAGE_ROOT', $root);
    if (!defined('STORAGE_DIR')) define('STORAGE_DIR', $root . DIRECTORY_SEPARATOR . 'main');
    if (!defined('CHUNKS_DIR')) define('CHUNKS_DIR', $root . DIRECTORY_SEPARATOR . 'chunk');
    if (!defined('CACHE_DIR')) define('CACHE_DIR', $root . DIRECTORY_SEPARATOR . 'cache');
    if (!defined('TRASH_DIR')) define('TRASH_DIR', $root . DIRECTORY_SEPARATOR . 'trash');
    return $principal;
}

function mobile_prepare_account_storage() {
    return mobile_prepare_root_storage();
}
