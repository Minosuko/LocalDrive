<?php
/**
 * CloudDrive API — Get Full Directory Tree
 */
require_once __DIR__ . '/../_init.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    error_response('Method not allowed', 405);
}

$cacheFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'tree.json';
$versionFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'manifest.version';
if (!is_file($versionFile)) @atomic_write_file($versionFile, bin2hex(random_bytes(12)));
$generation = @file_get_contents($versionFile) ?: '';
$cacheVersionFile = $cacheFile . '.version';
$etagFile = $cacheFile . '.etag';
if (file_exists($cacheFile) && time() - filemtime($cacheFile) < 120
    && hash_equals($generation, @file_get_contents($cacheVersionFile) ?: '')) {
    $cachedEtag = trim((string)@file_get_contents($etagFile));
    if ($cachedEtag !== '' && trim($_SERVER['HTTP_IF_NONE_MATCH'] ?? '') === $cachedEtag) {
        header('Cache-Control: private, no-cache, no-transform');
        header('Vary: Authorization');
        header('ETag: ' . $cachedEtag);
        http_response_code(304);
        exit;
    }
    $cached = file_get_contents($cacheFile);
    if ($cached !== false) send_tree_json($cached, $cachedEtag ?: null);
}

$root = rtrim(str_replace('\\', '/', STORAGE_DIR), '/');
if (!is_dir($root)) json_response(['success' => true, 'data' => ['tree' => []]]);

$tree = [];
$childCounts = [];
$scanStable = true;
try {
    $directory = new RecursiveDirectoryIterator($root, RecursiveDirectoryIterator::SKIP_DOTS);
    $filtered = new RecursiveCallbackFilterIterator($directory, static function ($current) {
        if (is_internal_storage_name($current->getFilename())) {
            cleanup_stale_internal_entry($current->getPathname());
            return false;
        }
        return true;
    });
    $iter = new RecursiveIteratorIterator(
        $filtered,
        RecursiveIteratorIterator::SELF_FIRST
    );

    foreach ($iter as $file) {
        $fullPath = str_replace('\\', '/', $file->getPathname());
        $rel = substr($fullPath, strlen($root));
        if ($rel === '' || $rel === false) continue;
        if ($rel[0] !== '/') $rel = '/' . $rel;
        $separator = strrpos($rel, '/');
        $parent = $separator === 0 ? '/' : substr($rel, 0, $separator);
        $childCounts[$parent] = ($childCounts[$parent] ?? 0) + 1;

        if ($file->isDir()) {
            $tree[$rel] = [
                'name' => $file->getFilename(),
                'path' => $rel,
                'parent' => $parent,
                'children_count' => 0,
            ];
        }
    }
} catch (Throwable $e) {
    $scanStable = false;
}

if (!$scanStable || !hash_equals($generation, @file_get_contents($versionFile) ?: '')) {
    error_response('Folder changed during scan', 409);
}

foreach ($tree as $path => &$entry) $entry['children_count'] = $childCounts[$path] ?? 0;
unset($entry);
$tree = array_values($tree);

usort($tree, function($a, $b) {
    return strcasecmp($a['path'], $b['path']);
});

$res = ['success' => true, 'data' => ['tree' => $tree]];
$json = json_encode($res, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
$etag = '"tree-' . hash('sha256', $json) . '"';
with_cache_file_lock($cacheFile . '.lock', static function () use (
    $versionFile, $generation, $cacheVersionFile, $cacheFile, $etagFile, $json, $etag
) {
    if (!hash_equals($generation, @file_get_contents($versionFile) ?: '')) return;
    @unlink($cacheVersionFile);
    if (@atomic_write_file($cacheFile, $json)) {
        @atomic_write_file($etagFile, $etag);
        @atomic_write_file($cacheVersionFile, $generation);
    }
});
send_tree_json($json, $etag);

function send_tree_json($json, $etag = null) {
    $etag = $etag ?: '"tree-' . hash('sha256', $json) . '"';
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: private, no-cache, no-transform');
    header('Vary: Authorization');
    header('ETag: ' . $etag);
    if (trim($_SERVER['HTTP_IF_NONE_MATCH'] ?? '') === $etag) {
        http_response_code(304);
        exit;
    }
    header('Content-Length: ' . strlen($json));
    echo $json;
    exit;
}
