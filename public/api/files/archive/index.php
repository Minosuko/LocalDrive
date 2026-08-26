<?php
require_once __DIR__ . '/../../_init.php';
require_once BASE_DIR . '/includes/archive.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') error_response('Method not allowed', 405);
$path = $_GET['path'] ?? '';
if (!is_string($path) || $path === '') error_response('Provide an archive path');
try {
    $result = clouddrive_archive_list($path);
} catch (CloudDriveArchiveException $error) {
    error_response($error->getMessage(), $error->httpStatus);
}
$etag = '"archive-' . $result['cache_key'] . '"';
header('Cache-Control: private, no-cache, no-transform');
header('Vary: Authorization');
header('ETag: ' . $etag);
if (trim((string)($_SERVER['HTTP_IF_NONE_MATCH'] ?? '')) === $etag) {
    http_response_code(304);
    exit;
}
json_response(['success' => true, 'data' => $result['data']]);
