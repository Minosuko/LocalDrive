<?php
/**
 * CloudDrive API — Restore Trash Items
 */
require_once __DIR__ . '/../../_init.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    error_response('Method not allowed', 405);
}

$d = json_decode(file_get_contents('php://input'), true);
$ids = $d['ids'] ?? [];
if (empty($ids)) error_response('Missing item IDs', 400);

$metaFile = TRASH_DIR . DIRECTORY_SEPARATOR . 'metadata.json';
$meta = file_exists($metaFile) ? json_decode(file_get_contents($metaFile), true) ?: [] : [];

$restored = [];
$errors = [];

foreach ($ids as $id) {
    if (!isset($meta[$id])) {
        $errors[] = "Item not found in trash meta: $id";
        continue;
    }
    
    $info = $meta[$id];
    $physicalPath = TRASH_DIR . DIRECTORY_SEPARATOR . $id;
    $originalVirtual = $info['original_path'];
    $originalPhysical = get_real_path($originalVirtual);
    
    if (!file_exists($physicalPath)) {
        $errors[] = "Physical file missing: $id";
        unset($meta[$id]); // Clean up dead meta
        continue;
    }
    
    // Ensure parent directory exists for restoration
    $parentDir = dirname($originalPhysical);
    if (!is_dir($parentDir)) mkdir($parentDir, 0755, true);
    
    // Prevent overwrite if a file with the same name now exists
    if (file_exists($originalPhysical)) {
        // Append timestamp to name to avoid collision
        $pathInfo = pathinfo($originalPhysical);
        $originalPhysical = $pathInfo['dirname'] . DIRECTORY_SEPARATOR . $pathInfo['filename'] . '_restored_' . time();
        if (isset($pathInfo['extension'])) $originalPhysical .= '.' . $pathInfo['extension'];
    }
    
    if (@rename($physicalPath, $originalPhysical)) {
        $restored[] = $id;
        unset($meta[$id]);
        $invalidatedDirs[dirname($originalPhysical)] = true;
    } else {
        $errors[] = "Failed to restore: $id";
    }
}

if (!empty($invalidatedDirs)) {
    foreach (array_keys($invalidatedDirs) as $dir) {
        invalidate_dir_cache($dir);
    }
    invalidate_tree_cache();
}

file_put_contents($metaFile, json_encode($meta, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));

json_response(['success' => empty($errors), 'restored' => $restored, 'errors' => $errors]);
