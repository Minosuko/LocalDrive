<?php
require_once dirname(__DIR__) . '/_init.php';

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $config = get_config();
    if (($_GET['config_only'] ?? '') === '1') {
        json_response(['success' => true, 'config' => $config]);
    }

    $storage = get_storage_info_cached();
    $disk_used = $storage['used_space'];
    $disk_free = $storage['free_space'];
    $disk_total = max(1, $disk_used + $disk_free);
    
    $cache_size = get_dir_size(CACHE_DIR);
    $chunk_size = get_dir_size(CHUNKS_DIR);
    $trash_size = get_dir_size(TRASH_DIR);
    
    json_response([
        'success' => true,
        'config' => $config,
        'metrics' => [
            'disk_total' => $disk_total,
            'disk_used'  => $disk_used,
            'disk_free'  => $disk_free,
            'cache_size' => $cache_size,
            'chunk_size' => $chunk_size,
            'trash_size' => $trash_size
        ]
    ]);
}

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $data = json_decode(file_get_contents('php://input'), true);
    if (!is_array($data)) error_response('Invalid payload');
    
    $updates = [];
    if (isset($data['buffer_size'])) $updates['buffer_size'] = max(128, (int)$data['buffer_size']);
    if (isset($data['memory_limit'])) $updates['memory_limit'] = max(128, (int)$data['memory_limit']);
    if (isset($data['chunk_size'])) $updates['chunk_size'] = max(1, (int)$data['chunk_size']);
    if (isset($data['max_uploads'])) $updates['max_uploads'] = max(1, min(10, (int)$data['max_uploads']));
    if (isset($data['thumbnail_quality'])) $updates['thumbnail_quality'] = max(1, min(100, (int)$data['thumbnail_quality']));
    
    $newConfig = save_config($updates);
    json_response(['success' => true, 'config' => $newConfig]);
}

error_response('Method not allowed', 405);
