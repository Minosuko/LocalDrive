<?php
/**
 * CloudDrive API - List every file and folder below one directory.
 */
require_once __DIR__ . '/../../_init.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    error_response('Method not allowed', 405);
}

$path = $_GET['path'] ?? '/';
$real = get_real_path($path);
if (!is_dir($real)) {
    error_response('Folder not found', 404);
}

$base = realpath($real) ?: $real;
$cacheKey = md5(str_replace('\\', '/', $base));
$cacheFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'manifest_' . $cacheKey . '.json';
$versionFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'manifest.version';
if (!is_file($versionFile)) {
    @atomic_write_file($versionFile, bin2hex(random_bytes(12)));
}
$cacheGeneration = @file_get_contents($versionFile) ?: '';
$cacheVersionFile = $cacheFile . '.version';
$etagFile = $cacheFile . '.etag';

if (($_GET['stream'] ?? '') === '1') {
    stream_manifest_json($base, $path, $versionFile, $cacheGeneration);
}

$cachedGeneration = @file_get_contents($cacheVersionFile) ?: '';
if (($_GET['refresh'] ?? '') !== '1' && is_file($cacheFile) && is_file($cacheVersionFile)
    && hash_equals($cacheGeneration, $cachedGeneration) && time() - filemtime($cacheFile) < 60) {
    $cached = file_get_contents($cacheFile);
    if ($cached !== false) {
        send_manifest_json($cached);
    }
}

$files = [];
$scanStable = true;
try {
    $directory = new RecursiveDirectoryIterator($base, RecursiveDirectoryIterator::SKIP_DOTS);
    $filtered = new RecursiveCallbackFilterIterator($directory, static function ($current) {
        if (manifest_internal_name($current->getFilename())) {
            cleanup_stale_internal_entry($current->getPathname());
            return false;
        }
        return true;
    });
    $iterator = new RecursiveIteratorIterator(
        $filtered,
        RecursiveIteratorIterator::SELF_FIRST
    );
    foreach ($iterator as $file) {
        try {
            $fullPath = $file->getPathname();
            $relative = ltrim(str_replace('\\', '/', substr($fullPath, strlen($base))), '/');
            if ($relative === '') {
                continue;
            }
            $isDirectory = $file->isDir();
            $files[] = [
                'path' => $relative,
                'name' => $file->getFilename(),
                'type' => $isDirectory ? 'folder' : 'file',
                'size' => $isDirectory ? 0 : $file->getSize(),
                'modified' => $file->getMTime(),
                'mime' => $isDirectory ? 'httpd/unix-directory' : get_metadata_mime_type($fullPath),
            ];
        } catch (Throwable $error) {
            $scanStable = false;
        }
    }
} catch (Throwable $error) {
    error_response('Cannot read folder', 500);
}
$currentGeneration = @file_get_contents($versionFile) ?: '';
if (!$scanStable || !hash_equals($cacheGeneration, $currentGeneration)) {
    error_response('Folder changed during scan', 409);
}

usort($files, static function ($left, $right) {
    return strnatcasecmp($left['path'], $right['path']);
});
$json = json_encode(
    ['success' => true, 'data' => ['path' => sanitize_path($path), 'files' => $files]],
    JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
);
if ($json === false) {
    error_response('Cannot encode folder manifest', 500);
}

$etag = '"manifest-' . hash('sha256', $json) . '"';
$currentGeneration = @file_get_contents($versionFile) ?: '';
if (hash_equals($cacheGeneration, $currentGeneration)) {
    @unlink($cacheVersionFile);
    if (@atomic_write_file($cacheFile, $json)) {
        @atomic_write_file($cacheVersionFile, $currentGeneration);
    }
}
send_manifest_json($json, $etag);

function manifest_internal_name($name) {
    return is_internal_storage_name($name);
}

function stream_manifest_json($base, $path, $versionFile, $generation) {
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: private, no-cache, no-transform');
    header('Vary: Authorization');
    echo '{"success":true,"data":{"path":' . json_encode(
        sanitize_path($path),
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
    ) . ',"files":[';
    $first = true;
    $stable = true;
    $count = 0;
    try {
        $directory = new RecursiveDirectoryIterator($base, RecursiveDirectoryIterator::SKIP_DOTS);
        $filtered = new RecursiveCallbackFilterIterator($directory, static function ($current) {
            if (manifest_internal_name($current->getFilename())) {
                cleanup_stale_internal_entry($current->getPathname());
                return false;
            }
            return true;
        });
        $iterator = new RecursiveIteratorIterator($filtered, RecursiveIteratorIterator::SELF_FIRST);
        foreach ($iterator as $file) {
            try {
                $fullPath = $file->getPathname();
                $relative = ltrim(str_replace('\\', '/', substr($fullPath, strlen($base))), '/');
                if ($relative === '') continue;
                $isDirectory = $file->isDir();
                $entry = [
                    'path' => $relative,
                    'name' => $file->getFilename(),
                    'type' => $isDirectory ? 'folder' : 'file',
                    'size' => $isDirectory ? 0 : $file->getSize(),
                    'modified' => $file->getMTime(),
                    'mime' => $isDirectory ? 'httpd/unix-directory' : get_metadata_mime_type($fullPath),
                ];
                $encoded = json_encode(
                    $entry,
                    JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
                );
                if ($encoded === false) {
                    $stable = false;
                    continue;
                }
                if (!$first) echo ',';
                echo $encoded;
                $first = false;
                if ((++$count % 100) === 0) flush();
            } catch (Throwable $error) {
                $stable = false;
            }
        }
    } catch (Throwable $error) {
        $stable = false;
    }
    $currentGeneration = @file_get_contents($versionFile) ?: '';
    if (!hash_equals($generation, $currentGeneration)) $stable = false;
    echo '],"stable":' . ($stable ? 'true' : 'false') . '}}';
    exit;
}

function send_manifest_json($json, $etag = null) {
    $etag = $etag ?: '"manifest-' . hash('sha256', $json) . '"';
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: private, no-cache, no-transform');
    header('Vary: Authorization');
    header('ETag: ' . $etag);
    if (isset($_SERVER['HTTP_IF_NONE_MATCH'])) {
        foreach (explode(',', $_SERVER['HTTP_IF_NONE_MATCH']) as $candidate) {
            $candidate = trim($candidate);
            if (strpos($candidate, 'W/') === 0) {
                $candidate = substr($candidate, 2);
            }
            if ($candidate === '*' || hash_equals($etag, $candidate)) {
                http_response_code(304);
                exit;
            }
        }
    }
    header('Content-Length: ' . strlen($json));
    echo $json;
    exit;
}
