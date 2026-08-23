<?php
/**
 * CloudDrive API — Create ZIP Archive
 */
require_once __DIR__ . '/../../_init.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    error_response('Method not allowed', 405);
}

$data = json_decode(file_get_contents('php://input'), true);
$paths = $data['paths'] ?? [];
$zipName = trim($data['zip_name'] ?? 'archive.zip');
$destination = $data['destination'] ?? '/';

if (empty($paths)) {
    error_response('No paths provided to compress', 400);
}

if (!$zipName) {
    $zipName = 'archive.zip';
}
if (strtolower(pathinfo($zipName, PATHINFO_EXTENSION)) !== 'zip') {
    $zipName .= '.zip';
}
// Sanitize filename to avoid directory traversal or invalid characters
$zipName = preg_replace('/[<>:"\/\\\\|?*\x00-\x1f]/', '', $zipName);
if (!$zipName) {
    error_response('Invalid ZIP filename', 400);
}

$destReal = get_real_path($destination);
if (!is_dir($destReal)) {
    error_response('Destination directory does not exist', 404);
}

$zipReal = $destReal . DIRECTORY_SEPARATOR . $zipName;

if (file_exists($zipReal)) {
    error_response('A file or folder with the ZIP name already exists in destination', 409);
}

if (!class_exists('ZipArchive')) {
    error_response('ZipArchive extension is not enabled in PHP', 500);
}

$zip = new ZipArchive();
if ($zip->open($zipReal, ZipArchive::CREATE) !== true) {
    error_response('Failed to create ZIP file', 500);
}

// Function to recursively add files/directories to zip
function add_to_zip($zip, $realPath, $zipLocalPath, $zipReal) {
    // Avoid adding the zip itself to the zip
    if ($realPath === $zipReal) {
        return;
    }
    if (is_dir($realPath)) {
        $zip->addEmptyDir($zipLocalPath);
        $files = array_diff(scandir($realPath), ['.', '..']);
        foreach ($files as $file) {
            add_to_zip($zip, $realPath . DIRECTORY_SEPARATOR . $file, $zipLocalPath . '/' . $file, $zipReal);
        }
    } else {
        $zip->addFile($realPath, $zipLocalPath);
    }
}

$errors = [];
$successCount = 0;

foreach ($paths as $path) {
    $real = get_real_path($path);
    if (!file_exists($real)) {
        $errors[] = "Path does not exist: $path";
        continue;
    }
    // Prevent zipping a parent folder into a destination zip inside itself
    if (is_dir($real) && strpos(realpath($zipReal), realpath($real)) === 0) {
        $errors[] = "Cannot compress folder inside its own zip: $path";
        continue;
    }

    $baseName = basename($real);
    add_to_zip($zip, $real, $baseName, $zipReal);
    $successCount++;
}

$zip->close();

require_once BASE_DIR . '/includes/helpers.php';
invalidate_dir_cache($destReal);
invalidate_tree_cache();

if ($successCount === 0) {
    if (file_exists($zipReal)) {
        @unlink($zipReal);
    }
    error_response(!empty($errors) ? implode('; ', $errors) : 'No files were compressed', 400);
}

json_response([
    'success' => true,
    'message' => 'Archive created successfully',
    'errors' => $errors
]);
