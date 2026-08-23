<?php
// GET → stream file download (supports Range headers)
require_once __DIR__ . '/../../_init.php';
require_once BASE_DIR . '/includes/download.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') error_response('Method not allowed', 405);
handle_download();
