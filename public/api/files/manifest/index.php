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
$mediaOnly = ($_GET['media'] ?? '') === '1';
$excludedRelative = manifest_normalize_excluded_path($_GET['exclude'] ?? '');
$cacheIdentity = str_replace('\\', '/', $base);
if ($mediaOnly || $excludedRelative !== '') {
    $cacheIdentity .= '|media=' . ($mediaOnly ? '1' : '0') . '|exclude=' . $excludedRelative;
}
$cacheKey = md5($cacheIdentity);
$cacheFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'manifest_' . $cacheKey . '.json';
$versionFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'manifest.version';
if (!is_file($versionFile)) {
    @atomic_write_file($versionFile, bin2hex(random_bytes(12)));
}
$cacheGeneration = @file_get_contents($versionFile) ?: '';
$cacheVersionFile = $cacheFile . '.version';
$etagFile = $cacheFile . '.etag';
$streamCacheFile = $cacheFile . '.stream.json';
$streamCacheVersionFile = $streamCacheFile . '.version';

if (($_GET['stream'] ?? '') === '1') {
    stream_manifest_json(
        $base,
        $path,
        $versionFile,
        $cacheGeneration,
        $streamCacheFile,
        $streamCacheVersionFile,
        ($_GET['refresh'] ?? '') === '1',
        $mediaOnly,
        $excludedRelative
    );
}

$cachedGeneration = @file_get_contents($cacheVersionFile) ?: '';
if (($_GET['refresh'] ?? '') !== '1' && is_file($cacheFile) && is_file($cacheVersionFile)
    && hash_equals($cacheGeneration, $cachedGeneration) && time() - filemtime($cacheFile) < 60) {
    $cachedEtag = trim((string)@file_get_contents($etagFile));
    if ($cachedEtag !== '' && trim($_SERVER['HTTP_IF_NONE_MATCH'] ?? '') === $cachedEtag) {
        header('Cache-Control: private, no-cache, no-transform');
        header('Vary: Authorization');
        header('ETag: ' . $cachedEtag);
        http_response_code(304);
        exit;
    }
    $cached = file_get_contents($cacheFile);
    if ($cached !== false) {
        send_manifest_json($cached, $cachedEtag ?: null);
    }
}

$files = [];
$scanStable = true;
try {
    $directory = new RecursiveDirectoryIterator($base, RecursiveDirectoryIterator::SKIP_DOTS);
    $filtered = new RecursiveCallbackFilterIterator($directory, static function ($current) use ($base, $excludedRelative) {
        if (manifest_internal_name($current->getFilename())) {
            cleanup_stale_internal_entry($current->getPathname());
            return false;
        }
        $relative = manifest_relative_path($base, $current->getPathname());
        if (manifest_path_is_excluded($relative, $excludedRelative)) return false;
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
            if ($mediaOnly && $isDirectory) continue;
            $mime = $isDirectory ? 'httpd/unix-directory' : get_metadata_mime_type($fullPath);
            if ($mediaOnly && !manifest_is_media_file($mime, $file->getFilename())) continue;
            $files[] = [
                'path' => $relative,
                'name' => $file->getFilename(),
                'type' => $isDirectory ? 'folder' : 'file',
                'size' => $isDirectory ? 0 : $file->getSize(),
                'modified' => $file->getMTime(),
                'mime' => $mime,
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
        @atomic_write_file($etagFile, $etag);
        @atomic_write_file($cacheVersionFile, $currentGeneration);
    }
}
send_manifest_json($json, $etag);

function manifest_internal_name($name) {
    return is_internal_storage_name($name);
}

function stream_manifest_json(
    $base,
    $path,
    $versionFile,
    $generation,
    $cacheFile,
    $cacheVersionFile,
    $refresh,
    $mediaOnly,
    $excludedRelative
) {
    if (!$refresh && manifest_stream_cache_fresh($cacheFile, $cacheVersionFile, $generation)) {
        send_stream_manifest_file($cacheFile);
    }

    $lock = @fopen($cacheFile . '.lock', 'c');
    if (is_resource($lock)) @flock($lock, LOCK_EX);
    if (!$refresh && manifest_stream_cache_fresh($cacheFile, $cacheVersionFile, $generation)) {
        if (is_resource($lock)) {
            @flock($lock, LOCK_UN);
            fclose($lock);
        }
        send_stream_manifest_file($cacheFile);
    }

    $temporary = $cacheFile . '.' . bin2hex(random_bytes(6)) . '.tmp';
    register_shutdown_function(static function () use ($temporary) { @unlink($temporary); });
    $cacheOutput = @fopen($temporary, 'wb');
    $cacheWritable = is_resource($cacheOutput);
    $emit = static function ($chunk) use ($cacheOutput, &$cacheWritable) {
        echo $chunk;
        if (!$cacheWritable) return;
        $length = strlen($chunk);
        $offset = 0;
        while ($offset < $length) {
            $written = @fwrite($cacheOutput, substr($chunk, $offset));
            if ($written === false || $written === 0) {
                $cacheWritable = false;
                return;
            }
            $offset += $written;
        }
    };

    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: private, no-cache, no-transform');
    header('Vary: Authorization');
    $emit('{"success":true,"data":{"path":' . json_encode(
        sanitize_path($path),
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
    ) . ',"files":[');
    $first = true;
    $stable = true;
    $count = 0;
    try {
        $directory = new RecursiveDirectoryIterator($base, RecursiveDirectoryIterator::SKIP_DOTS);
        $filtered = new RecursiveCallbackFilterIterator($directory, static function ($current) use ($base, $excludedRelative) {
            if (manifest_internal_name($current->getFilename())) {
                cleanup_stale_internal_entry($current->getPathname());
                return false;
            }
            $relative = manifest_relative_path($base, $current->getPathname());
            if (manifest_path_is_excluded($relative, $excludedRelative)) return false;
            return true;
        });
        $iterator = new RecursiveIteratorIterator($filtered, RecursiveIteratorIterator::SELF_FIRST);
        foreach ($iterator as $file) {
            try {
                $fullPath = $file->getPathname();
                $relative = ltrim(str_replace('\\', '/', substr($fullPath, strlen($base))), '/');
                if ($relative === '') continue;
                $isDirectory = $file->isDir();
                if ($mediaOnly && $isDirectory) continue;
                $mime = $isDirectory ? 'httpd/unix-directory' : get_metadata_mime_type($fullPath);
                if ($mediaOnly && !manifest_is_media_file($mime, $file->getFilename())) continue;
                $entry = [
                    'path' => $relative,
                    'name' => $file->getFilename(),
                    'type' => $isDirectory ? 'folder' : 'file',
                    'size' => $isDirectory ? 0 : $file->getSize(),
                    'modified' => $file->getMTime(),
                    'mime' => $mime,
                ];
                $encoded = json_encode(
                    $entry,
                    JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
                );
                if ($encoded === false) {
                    $stable = false;
                    continue;
                }
                if (!$first) $emit(',');
                $emit($encoded);
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
    $emit('],"stable":' . ($stable ? 'true' : 'false') . '}}');
    if (is_resource($cacheOutput)) {
        if (!@fflush($cacheOutput)) $cacheWritable = false;
        fclose($cacheOutput);
    }
    if ($stable && $cacheWritable && hash_equals($generation, @file_get_contents($versionFile) ?: '')) {
        @unlink($cacheVersionFile);
        if (@rename($temporary, $cacheFile)) {
            @atomic_write_file($cacheVersionFile, $generation);
        } else {
            @unlink($temporary);
        }
    } else {
        @unlink($temporary);
    }
    if (is_resource($lock)) {
        @flock($lock, LOCK_UN);
        fclose($lock);
    }
    exit;
}

function manifest_stream_cache_fresh($cacheFile, $cacheVersionFile, $generation) {
    return is_file($cacheFile) && is_file($cacheVersionFile)
        && time() - (@filemtime($cacheFile) ?: 0) < 60
        && hash_equals($generation, (string)(@file_get_contents($cacheVersionFile) ?: ''));
}

function manifest_normalize_excluded_path($path) {
    if (!is_string($path) || trim($path) === '') return '';
    return trim(sanitize_path($path), '/');
}

function manifest_relative_path($base, $path) {
    return ltrim(str_replace('\\', '/', substr($path, strlen($base))), '/');
}

function manifest_path_is_excluded($relative, $excludedRelative) {
    if ($excludedRelative === '') return false;
    $relative = trim(str_replace('\\', '/', $relative), '/');
    if (DIRECTORY_SEPARATOR === '\\') {
        $relative = strtolower($relative);
        $excludedRelative = strtolower($excludedRelative);
    }
    return $relative === $excludedRelative || strpos($relative, $excludedRelative . '/') === 0;
}

function manifest_is_media_file($mime, $name) {
    if (stripos($mime, 'image/') === 0 || stripos($mime, 'video/') === 0) return true;
    static $extensions = [
        'jpg' => true, 'jpeg' => true, 'png' => true, 'gif' => true, 'webp' => true,
        'bmp' => true, 'heic' => true, 'heif' => true, 'avif' => true, 'mp4' => true,
        'mkv' => true, 'webm' => true, 'mov' => true, 'avi' => true, 'm4v' => true, '3gp' => true,
    ];
    return isset($extensions[strtolower(pathinfo($name, PATHINFO_EXTENSION))]);
}

function send_stream_manifest_file($cacheFile) {
    $stat = @stat($cacheFile);
    if ($stat === false) return;
    $etag = 'W/"manifest-stream-' . dechex((int)$stat['mtime']) . '-' . dechex((int)$stat['size']) . '"';
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: private, no-cache, no-transform');
    header('Vary: Authorization');
    header('ETag: ' . $etag);
    if (trim((string)($_SERVER['HTTP_IF_NONE_MATCH'] ?? '')) === $etag) {
        http_response_code(304);
        exit;
    }
    header('Content-Length: ' . (int)$stat['size']);
    readfile($cacheFile);
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
