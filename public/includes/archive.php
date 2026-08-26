<?php

class CloudDriveArchiveException extends RuntimeException
{
    public $httpStatus;

    public function __construct($message, $httpStatus = 500)
    {
        parent::__construct($message);
        $this->httpStatus = (int)$httpStatus;
    }
}

function clouddrive_archive_formats()
{
    return ['zip', '7z', 'rar', 'tar', 'gz', 'xz'];
}

function clouddrive_archive_format($name)
{
    $lower = strtolower((string)$name);
    foreach (['.tar.gz' => 'gz', '.tgz' => 'gz', '.tar.xz' => 'xz', '.txz' => 'xz'] as $suffix => $format) {
        if (substr($lower, -strlen($suffix)) === $suffix) return $format;
    }
    $extension = strtolower(pathinfo($lower, PATHINFO_EXTENSION));
    return in_array($extension, clouddrive_archive_formats(), true) ? $extension : null;
}

function clouddrive_archive_binary()
{
    static $resolved = false;
    if ($resolved !== false) return $resolved ?: null;
    $configured = getenv('CLOUDDRIVE_7Z_BINARY');
    $config = function_exists('get_config') ? get_config() : [];
    $candidates = [];
    if ($configured !== false && trim($configured) !== '') $candidates[] = trim($configured);
    if (!empty($config['archive_binary'])) $candidates[] = trim((string)$config['archive_binary']);
    if (DIRECTORY_SEPARATOR === '\\') {
        $candidates[] = 'C:\\Program Files\\7-Zip\\7z.exe';
        $candidates[] = 'C:\\Program Files (x86)\\7-Zip\\7z.exe';
        $candidates[] = 'C:\\Windows\\System32\\7z.exe';
    } else {
        $candidates[] = '/usr/bin/7zz';
        $candidates[] = '/usr/bin/7z';
        $candidates[] = '/usr/local/bin/7zz';
        $candidates[] = '/usr/local/bin/7z';
    }
    foreach (explode(PATH_SEPARATOR, (string)getenv('PATH')) as $directory) {
        if ($directory === '') continue;
        $candidates[] = rtrim($directory, '/\\') . DIRECTORY_SEPARATOR . (DIRECTORY_SEPARATOR === '\\' ? '7z.exe' : '7zz');
        if (DIRECTORY_SEPARATOR !== '\\') $candidates[] = rtrim($directory, '/') . '/7z';
    }
    foreach (array_unique($candidates) as $candidate) {
        if (!is_file($candidate)) continue;
        $real = realpath($candidate);
        if ($real !== false) return $resolved = $real;
    }
    $resolved = null;
    return null;
}

function clouddrive_archive_backend_available()
{
    return clouddrive_archive_binary() !== null || class_exists('ZipArchive');
}

function clouddrive_archive_resolve_file($virtualPath)
{
    if (!is_string($virtualPath) || $virtualPath === '' || strlen($virtualPath) > 4096 || $virtualPath[0] !== '/'
        || strpos($virtualPath, '\\') !== false || strpos($virtualPath, "\0") !== false
        || sanitize_path($virtualPath) !== $virtualPath || has_reserved_storage_path($virtualPath)) {
        throw new CloudDriveArchiveException('Invalid archive path', 400);
    }
    $root = realpath(STORAGE_DIR);
    $candidate = get_real_path($virtualPath);
    $resolved = realpath($candidate);
    if ($root === false || $resolved === false || !is_file($resolved) || is_link($candidate)
        || !clouddrive_archive_inside_root($resolved, $root)) {
        throw new CloudDriveArchiveException('Archive not found', 404);
    }
    $relative = trim(str_replace('\\', '/', substr($resolved, strlen($root))), '/');
    $current = $root;
    foreach ($relative === '' ? [] : explode('/', $relative) as $segment) {
        $current .= DIRECTORY_SEPARATOR . $segment;
        if (is_link($current)) throw new CloudDriveArchiveException('Archive symlinks are not supported', 400);
    }
    if (clouddrive_archive_format($resolved) === null) {
        throw new CloudDriveArchiveException('Unsupported archive type', 415);
    }
    return $resolved;
}

function clouddrive_archive_inside_root($path, $root)
{
    $path = rtrim(str_replace('\\', '/', $path), '/');
    $root = rtrim(str_replace('\\', '/', $root), '/');
    if (DIRECTORY_SEPARATOR === '\\') {
        $path = strtolower($path);
        $root = strtolower($root);
    }
    return $path === $root || strpos($path, $root . '/') === 0;
}

function clouddrive_archive_source_signature($path)
{
    clearstatcache(true, $path);
    $stat = @stat($path);
    if (!is_array($stat)) throw new CloudDriveArchiveException('Archive not found', 404);
    return [
        str_replace('\\', '/', realpath($path) ?: $path),
        (int)$stat['size'],
        (int)$stat['mtime'],
        (int)$stat['ctime'],
    ];
}

function clouddrive_archive_list($virtualPath)
{
    $path = clouddrive_archive_resolve_file($virtualPath);
    $format = clouddrive_archive_format($path);
    $signature = clouddrive_archive_source_signature($path);
    $binary = clouddrive_archive_binary();
    $backend = $binary !== null ? '7z-v2' : 'zip-v1';
    $cacheKey = hash('sha256', implode('|', array_merge([$backend], $signature)));
    $cachePath = CACHE_DIR . DIRECTORY_SEPARATOR . 'archive_' . $cacheKey . '.json';
    if (is_file($cachePath)) {
        $cached = @file_get_contents($cachePath);
        $decoded = $cached === false ? null : json_decode($cached, true);
        if (is_array($decoded)) return ['data' => $decoded, 'cache_key' => $cacheKey];
    }
    if ($binary !== null) {
        $entries = clouddrive_archive_list_with_7z($binary, $path);
    } elseif ($format === 'zip' && class_exists('ZipArchive')) {
        $entries = clouddrive_archive_list_zip($path);
    } else {
        throw new CloudDriveArchiveException('Archive reader is unavailable', 501);
    }
    if ($signature !== clouddrive_archive_source_signature($path)) {
        throw new CloudDriveArchiveException('Archive changed while being read', 409);
    }
    $data = [
        'path' => $virtualPath,
        'format' => $format,
        'files' => $entries,
    ];
    $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
    if ($json === false || strlen($json) > 32 * 1024 * 1024) {
        throw new CloudDriveArchiveException('Archive listing is too large', 413);
    }
    @atomic_write_file($cachePath, $json);
    return ['data' => $data, 'cache_key' => $cacheKey];
}

function clouddrive_archive_list_zip($path)
{
    $zip = new ZipArchive();
    if ($zip->open($path) !== true) throw new CloudDriveArchiveException('Could not open archive', 422);
    $entries = [];
    try {
        if ($zip->numFiles > 100000) throw new CloudDriveArchiveException('Archive contains too many entries', 413);
        for ($index = 0; $index < $zip->numFiles; $index++) {
            $stat = $zip->statIndex($index);
            if (!is_array($stat)) throw new CloudDriveArchiveException('Could not read archive entry', 422);
            $name = clouddrive_archive_entry_name($stat['name'] ?? '');
            if ($name === null) throw new CloudDriveArchiveException('Archive contains an unsafe entry path', 422);
            $directory = substr($name, -1) === '/';
            $entries[] = [
                'name' => $name,
                'type' => $directory ? 'folder' : 'file',
                'size' => $directory ? 0 : max(0, (int)($stat['size'] ?? 0)),
                'packed_size' => isset($stat['comp_size']) ? max(0, (int)$stat['comp_size']) : null,
                'mtime' => max(0, (int)($stat['mtime'] ?? 0)),
                'encrypted' => !empty($stat['encryption_method']),
                'crc' => isset($stat['crc']) ? strtoupper(str_pad(dechex((int)$stat['crc']), 8, '0', STR_PAD_LEFT)) : null,
            ];
        }
    } finally {
        $zip->close();
    }
    return $entries;
}

function clouddrive_archive_list_with_7z($binary, $path)
{
    $stderr = tempnam(CACHE_DIR, 'archive-error-');
    if ($stderr === false) throw new CloudDriveArchiveException('Could not prepare archive reader', 500);
    $descriptors = [
        0 => ['file', DIRECTORY_SEPARATOR === '\\' ? 'NUL' : '/dev/null', 'rb'],
        1 => ['pipe', 'wb'],
        2 => ['file', $stderr, 'wb'],
    ];
    $process = @proc_open(
        [$binary, 'l', '-slt', '-ba', '-bd', '-sccUTF-8', $path],
        $descriptors,
        $pipes,
        dirname($path),
        null,
        ['bypass_shell' => true]
    );
    if (!is_resource($process)) {
        @unlink($stderr);
        throw new CloudDriveArchiveException('Could not start archive reader', 501);
    }
    stream_set_blocking($pipes[1], false);
    $output = '';
    $deadline = microtime(true) + 30;
    $exitCode = null;
    try {
        while (true) {
            $chunk = fread($pipes[1], 65536);
            if ($chunk === false) throw new CloudDriveArchiveException('Could not read archive listing', 500);
            if ($chunk !== '') {
                $output .= $chunk;
                if (strlen($output) > 32 * 1024 * 1024) {
                    proc_terminate($process);
                    throw new CloudDriveArchiveException('Archive listing is too large', 413);
                }
            }
            $status = proc_get_status($process);
            if (!$status['running']) {
                $exitCode = (int)$status['exitcode'];
                break;
            }
            if (microtime(true) >= $deadline) {
                proc_terminate($process);
                throw new CloudDriveArchiveException('Archive listing timed out', 504);
            }
            usleep(10000);
        }
        while (!feof($pipes[1])) {
            $chunk = fread($pipes[1], 65536);
            if ($chunk === false || $chunk === '') break;
            $output .= $chunk;
            if (strlen($output) > 32 * 1024 * 1024) throw new CloudDriveArchiveException('Archive listing is too large', 413);
        }
    } finally {
        fclose($pipes[1]);
        $closedCode = proc_close($process);
        if ($exitCode === null && $closedCode >= 0) $exitCode = $closedCode;
    }
    $errorText = (string)@file_get_contents($stderr);
    @unlink($stderr);
    if ($exitCode !== 0) {
        throw new CloudDriveArchiveException(
            stripos($errorText, 'password') !== false ? 'Archive is encrypted' : 'Could not read archive',
            422
        );
    }
    if (preg_match('//u', $output) !== 1) throw new CloudDriveArchiveException('Archive contains invalid text metadata', 422);
    return clouddrive_archive_parse_7z($output, $path, clouddrive_archive_format($path));
}

function clouddrive_archive_parse_7z($output, $path = '', $format = null)
{
    $records = [];
    $record = [];
    $flush = static function () use (&$record, &$records) {
        if ($record) $records[] = $record;
        $record = [];
    };
    foreach (preg_split('/\r\n|\r|\n/', $output) as $line) {
        if (trim($line) === '') {
            $flush();
            continue;
        }
        $separator = strpos($line, ' = ');
        if ($separator === false) continue;
        $record[substr($line, 0, $separator)] = substr($line, $separator + 3);
    }
    $flush();
    if (count($records) > 100001) throw new CloudDriveArchiveException('Archive contains too many entries', 413);
    $entries = [];
    foreach ($records as $record) {
        if (!isset($record['Path']) || isset($record['Type'])) continue;
        $name = clouddrive_archive_entry_name($record['Path']);
        if ($name === null) throw new CloudDriveArchiveException('Archive contains an unsafe entry path', 422);
        $attributes = (string)($record['Attributes'] ?? '');
        $directory = ($record['Folder'] ?? '') === '+' || stripos($attributes, 'D') !== false || substr($name, -1) === '/';
        if ($directory && substr($name, -1) !== '/') $name .= '/';
        $entries[] = [
            'name' => $name,
            'type' => $directory ? 'folder' : 'file',
            'size' => $directory ? 0 : clouddrive_archive_decimal($record['Size'] ?? '0'),
            'packed_size' => isset($record['Packed Size']) ? clouddrive_archive_decimal($record['Packed Size']) : null,
            'mtime' => clouddrive_archive_time($record['Modified'] ?? ''),
            'encrypted' => ($record['Encrypted'] ?? '-') === '+',
            'crc' => isset($record['CRC']) && preg_match('/^[A-Fa-f0-9]{1,64}$/D', $record['CRC'])
                ? strtoupper($record['CRC']) : null,
        ];
        if (count($entries) > 100000) throw new CloudDriveArchiveException('Archive contains too many entries', 413);
    }
    if (!$entries && in_array($format, ['gz', 'xz'], true)) {
        foreach ($records as $record) {
            if (!isset($record['Size'])) continue;
            $entries[] = [
                'name' => clouddrive_archive_stream_name(basename((string)$path), $format),
                'type' => 'file',
                'size' => clouddrive_archive_decimal($record['Size']),
                'packed_size' => isset($record['Packed Size']) ? clouddrive_archive_decimal($record['Packed Size']) : null,
                'mtime' => clouddrive_archive_time($record['Modified'] ?? ''),
                'encrypted' => ($record['Encrypted'] ?? '-') === '+',
                'crc' => isset($record['CRC']) && preg_match('/^[A-Fa-f0-9]{1,64}$/D', $record['CRC'])
                    ? strtoupper($record['CRC']) : null,
            ];
            break;
        }
    }
    return $entries;
}

function clouddrive_archive_stream_name($name, $format)
{
    $lower = strtolower((string)$name);
    if ($format === 'gz') {
        if (substr($lower, -4) === '.tgz') return substr($name, 0, -4) . '.tar';
        if (substr($lower, -7) === '.tar.gz') return substr($name, 0, -3);
        if (substr($lower, -5) === '.gzip') return substr($name, 0, -5) ?: 'data';
        return substr($lower, -3) === '.gz' ? (substr($name, 0, -3) ?: 'data') : 'data';
    }
    if (substr($lower, -4) === '.txz') return substr($name, 0, -4) . '.tar';
    if (substr($lower, -7) === '.tar.xz') return substr($name, 0, -3);
    return substr($lower, -3) === '.xz' ? (substr($name, 0, -3) ?: 'data') : 'data';
}

function clouddrive_archive_entry_name($name)
{
    if (!is_string($name) || $name === '' || strlen($name) > 4096 || strpos($name, "\0") !== false) return null;
    $name = str_replace('\\', '/', $name);
    if ($name[0] === '/' || strpos($name, '//') === 0 || preg_match('/^[A-Za-z]:\//', $name)) return null;
    $directory = substr($name, -1) === '/';
    $trimmed = trim($name, '/');
    if ($trimmed === '') return null;
    $segments = explode('/', $trimmed);
    if (count($segments) > 64) return null;
    foreach ($segments as $segment) {
        if ($segment === '' || $segment === '.' || $segment === '..') return null;
    }
    return implode('/', $segments) . ($directory ? '/' : '');
}

function clouddrive_archive_decimal($value)
{
    $value = trim((string)$value);
    if (!preg_match('/^\d+$/D', $value)) return 0;
    $maximum = (string)PHP_INT_MAX;
    $normalized = ltrim($value, '0');
    if ($normalized === '') return 0;
    if (strlen($normalized) > strlen($maximum)
        || (strlen($normalized) === strlen($maximum) && strcmp($normalized, $maximum) > 0)) {
        return PHP_INT_MAX;
    }
    return (int)$normalized;
}

function clouddrive_archive_time($value)
{
    $value = trim((string)$value);
    if ($value === '') return 0;
    $timestamp = strtotime($value . (preg_match('/(?:Z|[+-]\d\d:?\d\d)$/', $value) ? '' : ' UTC'));
    return $timestamp === false ? 0 : max(0, $timestamp);
}
