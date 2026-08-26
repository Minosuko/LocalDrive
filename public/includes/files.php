<?php
/**
 * CloudDrive — File & Folder Management
 */

require_once __DIR__ . '/helpers.php';

function handle_list() {
    $path    = $_GET['path'] ?? '/';
    $real    = get_real_path($path);

    if (!is_dir($real)) {
        if (sanitize_path($path) !== '/') error_response('Folder not found', 404);
        $path = '/';
        $real = get_real_path('/');
        if (!is_dir($real)) mkdir($real, 0755, true);
    }

    $only_folders = isset($_GET['only_folders']) && $_GET['only_folders'] === '1';

    $base = realpath($real) ?: $real;
    $cacheKey = md5($base . '_' . ($only_folders ? 'folders' : 'all'));
    $cacheFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'dir_' . $cacheKey . '.json';
    $etagFile = $cacheFile . '.etag';
    $cacheVersionFile = $cacheFile . '.version';
    $lockFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'dir_' . $cacheKey . '.lock';
    $versionFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'dir_' . $cacheKey . '.generation';
    if (!is_file($versionFile)) @atomic_write_file($versionFile, bin2hex(random_bytes(12)));
    $cacheGeneration = @file_get_contents($versionFile) ?: '';

    // Serve from cache if valid and fresh (< 60s to catch external edits, or strictly rely on invalidation)
    if (($_GET['refresh'] ?? '') !== '1' && file_exists($cacheFile) && time() - filemtime($cacheFile) < 60
        && (@filemtime($real) ?: 0) <= filemtime($cacheFile)
        && hash_equals((string)$cacheGeneration, (string)(@file_get_contents($cacheVersionFile) ?: ''))) {
        $cachedEtag = trim((string)@file_get_contents($etagFile));
        if ($cachedEtag !== '' && directory_etag_matches($cachedEtag)) {
            header('Cache-Control: private, no-cache, no-transform');
            header('Vary: Authorization');
            header('ETag: ' . $cachedEtag);
            http_response_code(304);
            exit;
        }
        $cached = file_get_contents($cacheFile);
        if ($cached !== false) {
            send_directory_json($cached, $cachedEtag ?: null);
        }
    }

    $items = @scandir($real);
    if ($items === false) error_response('Cannot read directory', 500);

    $files = [];
    foreach ($items as $item) {
        if ($item === '.' || $item === '..') continue;
        $fp    = $real . DIRECTORY_SEPARATOR . $item;
        if (is_internal_storage_name($item)) {
            cleanup_stale_internal_entry($fp);
            continue;
        }
        $isDir = is_dir($fp);
        if ($only_folders && !$isDir) continue;
        
        $ext   = $isDir ? '' : strtolower(pathinfo($item, PATHINFO_EXTENSION));

        $files[] = [
            'name'      => $item,
            'type'      => $isDir ? 'folder' : 'file',
            'size'      => $isDir ? 0 : @filesize($fp),
            'modified'  => @filemtime($fp),
            'extension' => $ext,
            'icon'      => $isDir ? 'folder' : get_file_type($ext),
            'mime'      => $isDir ? 'httpd/unix-directory' : get_metadata_mime_type($fp),
        ];
    }

    usort($files, function ($a, $b) {
        if ($a['type'] !== $b['type']) return $a['type'] === 'folder' ? -1 : 1;
        return strnatcasecmp($a['name'], $b['name']);
    });

    $san   = sanitize_path($path);
    $parts = array_filter(explode('/', $san));
    $bc    = [['name' => 'Home', 'path' => '/']];
    $cur   = '';
    foreach ($parts as $p) { $cur .= '/' . $p; $bc[] = ['name' => $p, 'path' => $cur]; }

    $res = ['success' => true, 'data' => [
        'files'       => $files,
        'path'        => $san ?: '/',
        'breadcrumbs' => $bc,
    ]];

    $json = json_encode($res, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
    $etag = '"dir-' . hash('sha256', $json) . '"';
    with_cache_file_lock($lockFile, static function () use (
        $versionFile, $cacheGeneration, $cacheVersionFile, $cacheFile, $etagFile, $json, $etag
    ) {
        $currentGeneration = @file_get_contents($versionFile) ?: '';
        if (!hash_equals($cacheGeneration, $currentGeneration)) return;
        @unlink($cacheVersionFile);
        if (@atomic_write_file($cacheFile, $json)) {
            @atomic_write_file($etagFile, $etag);
            @atomic_write_file($cacheVersionFile, $currentGeneration);
        }
    });
    send_directory_json($json, $etag);
}

function send_directory_json($json, $etag = null) {
    $etag = $etag ?: '"dir-' . hash('sha256', $json) . '"';
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: private, no-cache, no-transform');
    header('Vary: Authorization');
    header('ETag: ' . $etag);
    if (directory_etag_matches($etag)) {
        http_response_code(304);
        exit;
    }
    header('Content-Length: ' . strlen($json));
    echo $json;
    exit;
}

function directory_etag_matches($etag) {
    if (isset($_SERVER['HTTP_IF_NONE_MATCH'])) {
        foreach (explode(',', $_SERVER['HTTP_IF_NONE_MATCH']) as $candidate) {
            $candidate = trim($candidate);
            if (strpos($candidate, 'W/') === 0) $candidate = substr($candidate, 2);
            if ($candidate === '*' || hash_equals($etag, $candidate)) return true;
        }
    }
    return false;
}

function handle_create_folder() {
    $d    = json_decode(file_get_contents('php://input'), true);
    $path = $d['path'] ?? '/';
    $name = trim((string)($d['name'] ?? ''));

    if ($name === '') $name = 'New Folder';
    if ($name === '.' || $name === '..') error_response('Invalid folder name');
    $name = trim(preg_replace('/[<>:"\/\\\\|?*\x00-\x1f]/', '', $name));
    if (!$name) error_response('Invalid folder name');
    if (is_reserved_storage_name($name)) error_response('Reserved file name');

    $target = get_real_path($path) . DIRECTORY_SEPARATOR . $name;
    if (file_exists($target)) error_response('Already exists');
    if (!mkdir($target, 0755, true)) error_response('Failed', 500);

    invalidate_dir_cache(get_real_path($path));
    invalidate_tree_cache();

    json_response(['success' => true]);
}

function handle_delete() {
    $d     = json_decode(file_get_contents('php://input'), true);
    $paths = $d['paths'] ?? [];
    if (empty($paths)) error_response('No paths');

    $deleted = [];
    $errors  = [];
    $metaFile = TRASH_DIR . DIRECTORY_SEPARATOR . 'metadata.json';
    $meta = file_exists($metaFile) ? json_decode(file_get_contents($metaFile), true) ?: [] : [];

    foreach ($paths as $p) {
        $rp = get_real_path($p);
        if (realpath($rp) === realpath(STORAGE_DIR)) { $errors[] = 'Cannot delete root'; continue; }
        if (!file_exists($rp)) { $errors[] = "Not found: $p"; continue; }
        
        $id = uniqid('trash_') . '_' . time();
        $dest = TRASH_DIR . DIRECTORY_SEPARATOR . $id;
        
        if (@rename($rp, $dest)) {
            $meta[$id] = [
                'original_path' => $p,
                'name' => basename($rp),
                'type' => is_dir($dest) ? 'folder' : 'file',
                'deleted_at' => time()
            ];
            $deleted[] = $p;
        } else {
            $errors[] = "Failed to move to trash: $p";
        }
    }
    
    if (!empty($deleted)) {
        file_put_contents($metaFile, json_encode($meta, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));
        
        $invalidatedDirs = [];
        foreach ($deleted as $p) {
            $dir = dirname(get_real_path($p));
            if (!isset($invalidatedDirs[$dir])) {
                invalidate_dir_cache($dir);
                $invalidatedDirs[$dir] = true;
            }
        }
        invalidate_tree_cache();
    }

    json_response(['success' => empty($errors), 'deleted' => $deleted, 'errors' => $errors]);
}

function handle_rename() {
    $d   = json_decode(file_get_contents('php://input'), true);
    $path = $d['path'] ?? '';
    $new  = trim(preg_replace('/[<>:"\/\\\\|?*\x00-\x1f]/', '', $d['new_name'] ?? ''));
    if (!$path || !$new) error_response('Missing path or new_name');
    if (is_reserved_storage_name($new)) error_response('Reserved file name');

    $rp  = get_real_path($path);
    $np  = dirname($rp) . DIRECTORY_SEPARATOR . $new;
    if (!file_exists($rp)) error_response('Not found', 404);
    if (file_exists($np))  error_response('Name taken');
    if (!@rename($rp, $np)) error_response('Failed', 500);

    invalidate_dir_cache(dirname($rp));
    invalidate_tree_cache();

    json_response(['success' => true]);
}

function handle_move() {
    $d     = json_decode(file_get_contents('php://input'), true);
    $paths = $d['paths'] ?? [];
    $dest  = $d['destination'] ?? '';
    if (empty($paths) || $dest === '') error_response('Missing paths/destination');

    $dd = get_real_path($dest);
    if (!is_dir($dd)) error_response('Destination not found', 404);

    $moved = []; $errors = [];
    foreach ($paths as $p) {
        $rp = get_real_path($p);
        $np = $dd . DIRECTORY_SEPARATOR . basename($rp);
        if (!file_exists($rp)) { $errors[] = "Not found: $p"; continue; }
        if (is_dir($rp) && strpos(realpath($dd), realpath($rp)) === 0) { $errors[] = "Cannot move into itself: $p"; continue; }
        if (file_exists($np)) { $errors[] = "Exists: " . basename($rp); continue; }
        @rename($rp, $np) ? $moved[] = $p : $errors[] = "Failed: $p";
    }
    if (!empty($moved)) {
        $invalidatedDirs = [$dd => true];
        invalidate_dir_cache($dd);
        foreach ($moved as $p) {
            $dir = dirname(get_real_path($p));
            if (!isset($invalidatedDirs[$dir])) {
                invalidate_dir_cache($dir);
                $invalidatedDirs[$dir] = true;
            }
        }
        invalidate_tree_cache();
    }

    json_response(['success' => empty($errors), 'moved' => $moved, 'errors' => $errors]);
}

function handle_copy() {
    $d     = json_decode(file_get_contents('php://input'), true);
    $paths = $d['paths'] ?? [];
    $dest  = $d['destination'] ?? '';
    if (empty($paths) || $dest === '') error_response('Missing paths/destination');

    $destinationDir = get_real_path($dest);
    if (!is_dir($destinationDir)) error_response('Destination not found', 404);

    $copied = []; $errors = [];
    foreach ($paths as $virtualPath) {
        $source = get_real_path($virtualPath);
        $target = $destinationDir . DIRECTORY_SEPARATOR . basename($source);
        if (!file_exists($source)) { $errors[] = "Not found: $virtualPath"; continue; }
        if (realpath($source) === realpath(STORAGE_DIR)) { $errors[] = 'Cannot copy root'; continue; }
        if (file_exists($target)) { $errors[] = 'Exists: ' . basename($source); continue; }
        $sourceReal = realpath($source);
        $destinationReal = realpath($destinationDir);
        if (is_dir($source) && $sourceReal && $destinationReal
            && ($destinationReal === $sourceReal || strpos($destinationReal, $sourceReal . DIRECTORY_SEPARATOR) === 0)) {
            $errors[] = "Cannot copy into itself: $virtualPath";
            continue;
        }
        if (copy_storage_item($source, $target)) {
            $copied[] = $virtualPath;
        } else {
            if (file_exists($target)) is_dir($target) ? delete_directory($target) : @unlink($target);
            $errors[] = "Failed: $virtualPath";
        }
    }

    if ($copied) {
        invalidate_dir_cache($destinationDir);
        invalidate_tree_cache();
    }
    json_response(['success' => empty($errors), 'copied' => $copied, 'errors' => $errors]);
}

function copy_storage_item($source, $target) {
    if (is_file($source)) return @copy($source, $target);
    if (!is_dir($source) || !@mkdir($target, 0755)) return false;
    $children = @scandir($source);
    if ($children === false) return false;
    foreach ($children as $name) {
        if ($name === '.' || $name === '..') continue;
        if (!copy_storage_item($source . DIRECTORY_SEPARATOR . $name, $target . DIRECTORY_SEPARATOR . $name)) return false;
    }
    return true;
}

function handle_info() {
    json_response(['success' => true, 'data' => get_storage_info_cached()]);
}
