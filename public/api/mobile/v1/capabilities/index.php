<?php
require_once dirname(__DIR__, 4) . '/includes/mobile.php';
mobile_initialize(true);
mobile_require_method('GET');
mobile_response([
    'api' => 'mobile/v1',
    'account_storage' => true,
    'dav' => '/api/mobile/v1/dav',
    'max_parallel_uploads' => 3,
    'features' => ['files', 'manifest', 'thumbnails', 'preview', 'sync', 'trash'],
]);
