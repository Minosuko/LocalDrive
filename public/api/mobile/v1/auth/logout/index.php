<?php
require_once dirname(__DIR__, 5) . '/includes/mobile.php';
$principal = mobile_initialize(true);
mobile_require_method('POST');
mobile_logout($principal);
mobile_response(['logged_out' => true]);
