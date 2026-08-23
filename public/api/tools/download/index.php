<?php
// POST -> download video to local CloudDrive storage using Cobalt instances
require_once dirname(__DIR__, 2) . '/_init.php';
require_once BASE_DIR . '/includes/files.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') error_response('Method not allowed', 405);
set_time_limit(0); // Allow long downloads

$data = json_decode(file_get_contents('php://input'), true);
$url = $data['url'] ?? '';
$folder = $data['folder'] ?? '/Downloads';

if (empty($url)) error_response('Missing URL');

// Normalize destination path
$destDir = get_real_path($folder);
if (!is_dir($destDir)) {
    if (!@mkdir($destDir, 0755, true)) {
        error_response('Failed to create destination folder');
    }
}

require_once BASE_DIR . '/includes/VideoDownloader.php';

try {
    $videoInfo = VideoDownloader::getDownloadUrl($url);
    $videoUrl = $videoInfo['url'];
    $videoFilename = trim(preg_replace('/[<>:"\/\\\\|?*\x00-\x1f]/', '', $videoInfo['title'])) . '.' . $videoInfo['ext'];
} catch (Exception $e) {
    error_response('VideoDownloader Error: ' . $e->getMessage());
}

if (empty($videoFilename)) $videoFilename = 'video_' . time() . '.mp4';

// Ensure the destination path ends with separator
$destDir = rtrim($destDir, DIRECTORY_SEPARATOR);
$destFile = $destDir . DIRECTORY_SEPARATOR . $videoFilename;

// Add suffix if file exists
$pathinfo = pathinfo($destFile);
$base = $pathinfo['filename'];
$ext = isset($pathinfo['extension']) ? '.' . $pathinfo['extension'] : '';
$counter = 1;
while (file_exists($destFile)) {
    $destFile = $destDir . DIRECTORY_SEPARATOR . $base . " ($counter)" . $ext;
    $videoFilename = $base . " ($counter)" . $ext;
    $counter++;
}

// Download stream directly into file
$fp = fopen($destFile, 'w+');
if (!$fp) {
    error_response('Failed to open destination file for writing');
}

$ch = curl_init($videoUrl);
curl_setopt($ch, CURLOPT_FILE, $fp);
curl_setopt($ch, CURLOPT_FOLLOWLOCATION, true);
curl_setopt($ch, CURLOPT_TIMEOUT, 3600); // Allow 1 hour for big files
curl_setopt($ch, CURLOPT_USERAGENT, 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36');
$success = curl_exec($ch);
$httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
$error = curl_error($ch);
curl_close($ch);
fclose($fp);

if (!$success || $httpCode >= 400) {
    @unlink($destFile);
    error_response('Failed to download video stream: ' . ($error ?: "HTTP $httpCode"));
}

// Invalidate cache for the destination folder so it appears immediately
invalidate_dir_cache($destDir);
invalidate_tree_cache();

json_response([
    'success' => true,
    'file' => [
        'name' => $videoFilename,
        'size' => filesize($destFile),
        'path' => ($folder === '/' ? '/' : rtrim($folder, '/').'/') . $videoFilename
    ]
]);
