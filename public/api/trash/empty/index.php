<?php
/**
 * CloudDrive API — Empty Trash / Delete Permanently
 */
require_once __DIR__ . '/../../_init.php';

if ($_SERVER['REQUEST_METHOD'] !== 'DELETE') {
    error_response('Method not allowed', 405);
}

$d = json_decode(file_get_contents('php://input'), true);
$ids = $d['ids'] ?? []; // If empty, empty entire trash

$metaFile = TRASH_DIR . DIRECTORY_SEPARATOR . 'metadata.json';
$meta = file_exists($metaFile) ? json_decode(file_get_contents($metaFile), true) ?: [] : [];

$deleted = [];
$errors = [];

if (empty($ids)) {
    // Empty ALL trash
    foreach ($meta as $id => $info) {
        $physicalPath = TRASH_DIR . DIRECTORY_SEPARATOR . $id;
        if (file_exists($physicalPath)) {
            $ok = is_dir($physicalPath) ? delete_directory($physicalPath) : @unlink($physicalPath);
            if (!$ok) $errors[] = "Failed to delete: $id";
        }
    }
    // Delete any orphaned physical files in trash dir (excluding metadata.json)
    $files = scandir(TRASH_DIR);
    foreach ($files as $f) {
        if ($f === '.' || $f === '..' || $f === 'metadata.json') continue;
        $p = TRASH_DIR . DIRECTORY_SEPARATOR . $f;
        is_dir($p) ? delete_directory($p) : @unlink($p);
    }
    // Clear meta completely
    file_put_contents($metaFile, json_encode([], JSON_PRETTY_PRINT));
    json_response(['success' => empty($errors), 'errors' => $errors]);
} else {
    // Delete specific items
    foreach ($ids as $id) {
        if (!isset($meta[$id])) continue;
        
        $physicalPath = TRASH_DIR . DIRECTORY_SEPARATOR . $id;
        if (file_exists($physicalPath)) {
            $ok = is_dir($physicalPath) ? delete_directory($physicalPath) : @unlink($physicalPath);
            if ($ok) {
                $deleted[] = $id;
                unset($meta[$id]);
            } else {
                $errors[] = "Failed to delete: $id";
            }
        } else {
            // Already gone physically
            unset($meta[$id]);
            $deleted[] = $id;
        }
    }
    file_put_contents($metaFile, json_encode($meta, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));
    json_response(['success' => empty($errors), 'deleted' => $deleted, 'errors' => $errors]);
}
