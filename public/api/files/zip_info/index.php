<?php
/**
 * CloudDrive API — Zip File List
 * Returns a list of files inside a ZIP archive.
 */
require_once __DIR__ . '/../../_init.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') error_response('Method not allowed', 405);

$path = $_GET['path'] ?? '';
if (!$path) error_response('No path provided', 400);

$real = get_real_path($path);
if (!file_exists($real) || is_dir($real)) error_response('File not found', 404);

$cacheKey = hash('sha256', implode('|', [
    str_replace('\\', '/', realpath($real) ?: $real),
    @filemtime($real) ?: 0,
    @filectime($real) ?: 0,
    @filesize($real) ?: 0,
]));
$cachePath = CACHE_DIR . DIRECTORY_SEPARATOR . 'zip_' . $cacheKey . '.json';

if (file_exists($cachePath)) {
    $cached = file_get_contents($cachePath);
    if ($cached !== false) send_zip_json($cached, $cacheKey);
}

if (!class_exists('ZipArchive')) {
    error_response('ZipArchive extension not available', 500);
}

$zip = new ZipArchive();
if ($zip->open($real) === true) {
    $files = [];
    for ($i = 0; $i < $zip->numFiles; $i++) {
        $stat = $zip->statIndex($i);
        $files[] = [
            'name' => $stat['name'],
            'size' => $stat['size'],
            'mtime' => $stat['mtime']
        ];
    }
    $zip->close();
    
    $json = json_encode(
        ['success' => true, 'data' => ['files' => $files]],
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE
    );
    @atomic_write_file($cachePath, $json);
    send_zip_json($json, $cacheKey);
} else {
    error_response('Failed to open zip file', 500);
}

function send_zip_json($json, $cacheKey) {
    $etag = '"zip-' . $cacheKey . '"';
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
