<?php
/**
 * CloudDrive API — High Quality Image Viewer
 * Serves high quality JPEG proxies for proprietary formats like PSD/SAI2.
 */
require_once __DIR__ . '/../_init.php';
require_once BASE_DIR . '/includes/thumbnail.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    error_response('Method not allowed', 405);
}

$path = $_GET['path'] ?? '';
if (!$path) error_response('No path specified');

$real = get_real_path($path);
if (!file_exists($real) || is_dir($real)) error_response('File not found', 404);

$ext = strtolower(pathinfo($real, PATHINFO_EXTENSION));

$key = thumbnail_source_key($real, 'view-png');
$cachePath = CACHE_DIR . DIRECTORY_SEPARATOR . 'hq' . DIRECTORY_SEPARATOR . $key . '.png';

if (thumbnail_cache_is_valid($cachePath)) {
    serve_image($cachePath, $key . '_view');
}

$generated = publish_thumbnail_cache($cachePath, $key . '_view', static function ($temporary) use ($real, $ext) {
    $image = null;
    if ($ext === 'psd' || $ext === 'psb') {
        $image = extract_psd_full($real);
    } elseif ($ext === 'sai' || $ext === 'sai2') {
        $image = extract_sai_full($real);
    }
    if (!$image) return false;
    try {
        return imagepng($image, $temporary, 6);
    } finally {
        imagedestroy($image);
    }
});

if (!$generated) {
    $query = ['path' => $path, 'view' => 1];
    if (isset($_GET['v'])) $query['v'] = $_GET['v'];
    header('Location: /api/files/download/?' . http_build_query($query));
    exit;
}

serve_image($cachePath, $key . '_view');
