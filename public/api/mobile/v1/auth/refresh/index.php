<?php
require_once dirname(__DIR__, 5) . '/includes/mobile.php';
mobile_initialize(false);
mobile_require_method('POST');
$session = mobile_refresh(mobile_json_input());
mobile_set_browser_cookie($session['refresh_token'], $session['refresh_expires_at']);
mobile_response($session);
