<?php
// GET → disk usage and storage info
require_once __DIR__ . '/../_init.php';
require_once BASE_DIR . '/includes/files.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') error_response('Method not allowed', 405);
handle_info();
