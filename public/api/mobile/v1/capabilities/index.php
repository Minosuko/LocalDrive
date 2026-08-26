<?php
require_once dirname(__DIR__, 4) . '/includes/mobile.php';
mobile_prepare_account_storage();
mobile_require_method('GET');
require_once dirname(__DIR__, 4) . '/includes/helpers.php';
require_once dirname(__DIR__, 4) . '/includes/archive.php';
$features = ['files', 'manifest', 'thumbnails', 'preview', 'sync', 'trash'];
if (clouddrive_archive_backend_available()) $features[] = 'archives';
mobile_response([
    'api' => 'mobile/v1',
    'account_storage' => true,
    'dav' => '/api/mobile/v1/dav',
    'max_parallel_uploads' => 3,
    'features' => $features,
    'archive_formats' => clouddrive_archive_backend_available() ? clouddrive_archive_formats() : [],
]);
