<?php
require_once dirname(__DIR__, 5) . '/includes/mobile.php';
mobile_prepare_account_storage();
require_once dirname(__DIR__, 5) . '/includes/helpers.php';
mobile_require_method('POST');

$input = mobile_json_input(2 * 1024 * 1024);
$paths = $input['paths'] ?? null;
if (!is_array($paths) || count($paths) > 500) mobile_error('Provide up to 500 file paths');

$root = realpath(STORAGE_DIR);
if ($root === false) mobile_error('Storage is unavailable', 500);
$files = [];
foreach ($paths as $path) {
    if (!is_string($path) || strlen($path) > 4096 || $path === '' || $path[0] !== '/'
        || strpos($path, '\\') !== false || strpos($path, "\0") !== false
        || sanitize_path($path) !== $path || has_reserved_storage_path($path)) {
        mobile_error('Invalid file path');
    }
    $candidate = get_real_path($path);
    if (is_link($candidate)) continue;
    $resolved = realpath($candidate);
    if ($resolved === false || !mobile_status_inside_root($resolved, $root)) continue;
    clearstatcache(true, $resolved);
    $stat = @stat($resolved);
    if (!is_array($stat) || (($stat['mode'] & 0170000) !== 0100000)) continue;
    $files[] = [
        'path' => $path,
        'size' => $stat['size'],
        'modified' => $stat['mtime'],
    ];
}

mobile_response(['files' => $files]);

function mobile_status_inside_root($path, $root) {
    $path = rtrim(str_replace('\\', '/', $path), '/');
    $root = rtrim(str_replace('\\', '/', $root), '/');
    if (DIRECTORY_SEPARATOR === '\\') {
        $path = strtolower($path);
        $root = strtolower($root);
    }
    return $path === $root || strpos($path, $root . '/') === 0;
}
