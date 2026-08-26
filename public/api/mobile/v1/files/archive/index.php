<?php
require_once dirname(__DIR__, 5) . '/includes/mobile.php';
mobile_prepare_account_storage();
require_once dirname(__DIR__, 5) . '/includes/helpers.php';
require_once dirname(__DIR__, 5) . '/includes/archive.php';
mobile_require_method('GET');

$path = $_GET['path'] ?? '';
if (!is_string($path) || $path === '') mobile_error('Provide an archive path');
try {
    $result = clouddrive_archive_list($path);
} catch (CloudDriveArchiveException $error) {
    mobile_error($error->getMessage(), $error->httpStatus);
}
$etag = '"archive-' . $result['cache_key'] . '"';
header('Cache-Control: private, no-cache, no-transform');
header('Vary: Authorization');
header('ETag: ' . $etag);
if (trim((string)($_SERVER['HTTP_IF_NONE_MATCH'] ?? '')) === $etag) {
    http_response_code(304);
    exit;
}
mobile_response($result['data']);
