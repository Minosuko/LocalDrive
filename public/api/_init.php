<?php
/**
 * CloudDrive API — Shared initialization
 * All API endpoint files include this first.
 */

require_once dirname(__DIR__) . '/includes/helpers.php';

@ini_set('max_execution_time', '0');
@ini_set('memory_limit', max(128, (int)$cfg['memory_limit']) . 'M');
@ini_set('post_max_size', max(8, (int)$cfg['chunk_size'] + 1) . 'M');
@ini_set('upload_max_filesize', max(8, (int)$cfg['chunk_size'] + 1) . 'M');
@ini_set('max_input_time', '-1');
@ini_set('zlib.output_compression', 'Off');

if (function_exists('apache_setenv')) {
    @apache_setenv('no-gzip', '1');
}

header('Cache-Control: private, no-store, no-transform');
header('Vary: Authorization');
header('Access-Control-Allow-Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS');
header('Access-Control-Allow-Headers: Authorization, Content-Type, If-None-Match, X-File-Id, X-Chunk-Index, X-Total-Chunks, X-Filename, X-Folder, X-File-Size, X-Chunk-Size, X-HTTP-Method-Override');
header('Access-Control-Expose-Headers: ETag');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_SERVER['HTTP_X_HTTP_METHOD_OVERRIDE'])) {
    $override = strtoupper(trim($_SERVER['HTTP_X_HTTP_METHOD_OVERRIDE']));
    if (in_array($override, ['PATCH', 'DELETE'], true)) $_SERVER['REQUEST_METHOD'] = $override;
}
