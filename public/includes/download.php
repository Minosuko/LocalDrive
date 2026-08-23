<?php
/**
 * CloudDrive — Streaming Download with Range Support
 */

require_once __DIR__ . '/helpers.php';

function handle_download() {
    $path = $_GET['path'] ?? '';
    if (!$path) error_response('No path specified');

    $real = get_real_path($path);
    if (!file_exists($real) || is_dir($real)) error_response('File not found', 404);

    $size     = filesize($real);
    $filename = basename($real);
    $mime     = get_mime_type($real);

    while (ob_get_level()) ob_end_clean();

    // Range handling
    $start  = 0;
    $end    = $size - 1;
    $status = 200;

    if (isset($_SERVER['HTTP_RANGE']) && $size > 0) {
        if (preg_match('/bytes=(\d*)-(\d*)/', $_SERVER['HTTP_RANGE'], $m)) {
            $start = $m[1] !== '' ? intval($m[1]) : 0;
            $end   = $m[2] !== '' ? intval($m[2]) : $size - 1;
            if ($start > $end || $start >= $size) {
                http_response_code(416);
                header("Content-Range: bytes */$size");
                exit;
            }
            if ($end >= $size) $end = $size - 1;
            $status = 206;
        }
    }

    $length = $end - $start + 1;

    http_response_code($status);
    header('Content-Type: ' . $mime);
    header('Content-Length: ' . $length);
    $disp = isset($_GET['view']) ? 'inline' : 'attachment';
    header('Content-Disposition: ' . $disp . '; filename="' . rawurlencode($filename) . '"');
    header('Accept-Ranges: bytes');
    header('Cache-Control: no-cache, no-store');
    if ($status === 206) header("Content-Range: bytes $start-$end/$size");

    $fp = fopen($real, 'rb');
    if ($start > 0) fseek($fp, $start);

    $rem = $length;
    while ($rem > 0 && !feof($fp) && !connection_aborted()) {
        $buf = fread($fp, min(BUFFER_SIZE, $rem));
        if ($buf === false || $buf === '') break;
        echo $buf;
        flush();
        $rem -= strlen($buf);
    }
    fclose($fp);
    exit;
}
