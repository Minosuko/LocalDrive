<?php
// GET → cached thumbnail for images (GD) and videos (ffmpeg)
require_once __DIR__ . '/../_init.php';
require_once BASE_DIR . '/includes/thumbnail.php';

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    handle_thumbnail();
} elseif ($_SERVER['REQUEST_METHOD'] === 'POST') {
    handle_thumbnail_upload();
} else {
    error_response('Method not allowed', 405);
}
