<?php
// POST → assemble chunks into final file
require_once __DIR__ . '/../../../_init.php';
require_once BASE_DIR . '/includes/upload.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') error_response('Method not allowed', 405);
handle_upload_complete();
