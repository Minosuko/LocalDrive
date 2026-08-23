<?php
/**
 * CloudDrive API — Extract ZIP Archive
 */
require_once __DIR__ . '/../../_init.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    error_response('Method not allowed', 405);
}

$data = json_decode(file_get_contents('php://input'), true);
$zipPath = $data['path'] ?? '';
$destination = $data['destination'] ?? '/';

if (!$zipPath) {
    error_response('No zip file path provided', 400);
}

$zipReal = get_real_path($zipPath);
if (!file_exists($zipReal) || is_dir($zipReal)) {
    error_response('ZIP file not found', 404);
}

$destReal = get_real_path($destination);
if (!is_dir($destReal)) {
    error_response('Destination directory does not exist', 404);
}

if (!class_exists('ZipArchive')) {
    error_response('ZipArchive extension is not enabled in PHP', 500);
}

$zip = new ZipArchive();
if ($zip->open($zipReal) !== true) {
    error_response('Failed to open ZIP file', 500);
}

// Security Check: Directory Traversal
$storageRootReal = realpath(STORAGE_DIR);
for ($i = 0; $i < $zip->numFiles; $i++) {
    $filename = $zip->getNameIndex($i);
    // Construct hypothetical extraction target path
    $targetPath = $destReal . DIRECTORY_SEPARATOR . $filename;
    
    // Resolve path segments without requiring the folder to exist yet
    $parts = array_filter(explode('/', str_replace('\\', '/', $targetPath)), function($p) {
        return $p !== '' && $p !== '.';
    });
    $resolvedParts = [];
    foreach ($parts as $p) {
        if ($p === '..') {
            array_pop($resolvedParts);
        } else {
            $resolvedParts[] = $p;
        }
    }
    
    // Check if the path begins with the storage root path
    $resolvedPath = implode(DIRECTORY_SEPARATOR, $resolvedParts);
    
    if (stripos($resolvedPath, $storageRootReal) !== 0) {
        $zip->close();
        error_response('Malicious path detected inside ZIP archive: ' . $filename, 400);
    }
}

// Safe to extract
if (!$zip->extractTo($destReal)) {
    $zip->close();
    error_response('Failed to extract ZIP archive', 500);
}

$zip->close();

invalidate_dir_cache($destReal);
invalidate_tree_cache();

json_response([
    'success' => true,
    'message' => 'Archive extracted successfully'
]);
