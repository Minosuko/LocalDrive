<?php
require_once dirname(__DIR__, 5) . '/includes/mobile.php';
$principal = mobile_initialize(true);
$method = mobile_require_method(['GET', 'PATCH']);
if ($method === 'PATCH') mobile_response(mobile_update_root_account($principal, mobile_json_input()));
mobile_response([
    'id' => $principal['public_id'],
    'username' => $principal['username'],
    'display_name' => $principal['display_name'],
    'role' => $principal['role'],
    'quota_bytes' => $principal['quota_bytes'] === null ? null : (int)$principal['quota_bytes'],
    'created_at' => isset($principal['created_at']) ? (int)$principal['created_at'] : null,
    'last_login_at' => isset($principal['last_login_at']) ? (int)$principal['last_login_at'] : null,
]);
