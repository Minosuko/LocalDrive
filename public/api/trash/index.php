<?php
/**
 * CloudDrive API — List Trash Items
 */
require_once __DIR__ . '/../_init.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    error_response('Method not allowed', 405);
}

$metaFile = TRASH_DIR . DIRECTORY_SEPARATOR . 'metadata.json';
$meta = file_exists($metaFile) ? json_decode(file_get_contents($metaFile), true) ?: [] : [];

$files = [];
foreach ($meta as $id => $info) {
    // Only include if the physical file/folder still exists in TRASH_DIR
    $physicalPath = TRASH_DIR . DIRECTORY_SEPARATOR . $id;
    if (file_exists($physicalPath)) {
        $isDir = $info['type'] === 'folder';
        $ext = $isDir ? '' : strtolower(pathinfo($info['name'], PATHINFO_EXTENSION));
        
        $files[] = [
            'id' => $id,
            'name' => $info['name'],
            'path' => $info['original_path'], // Storing original path here so UI can display it
            'type' => $info['type'],
            'size' => $isDir ? 0 : filesize($physicalPath),
            'modified' => $info['deleted_at'], // Using modified to show deleted time
            'extension' => $ext,
            'icon' => $isDir ? 'folder' : get_file_type($ext)
        ];
    }
}

// Sort by deleted_at descending
usort($files, function($a, $b) {
    return $b['modified'] <=> $a['modified'];
});

json_response(['success' => true, 'data' => [
    'files' => $files,
    'path' => '/Trash',
    'breadcrumbs' => [['name' => 'Trash', 'path' => '/Trash']]
]]);
