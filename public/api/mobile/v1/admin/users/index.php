<?php
require_once dirname(__DIR__, 5) . '/includes/mobile.php';
$principal = mobile_require_root(mobile_initialize(true));
$method = mobile_require_method(['GET', 'POST', 'PATCH', 'DELETE']);
if ($method === 'GET') mobile_response(['users' => mobile_list_users()]);
mobile_error('This CloudDrive supports one root account only', 405);
