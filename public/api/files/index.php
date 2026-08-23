<?php
// GET  → list files    DELETE → delete    PATCH → rename
require_once __DIR__ . '/../_init.php';
require_once BASE_DIR . '/includes/files.php';

switch ($_SERVER['REQUEST_METHOD']) {
    case 'GET':    handle_list();   break;
    case 'DELETE': handle_delete(); break;
    case 'PATCH':  handle_rename(); break;
    default:       error_response('Method not allowed', 405);
}
