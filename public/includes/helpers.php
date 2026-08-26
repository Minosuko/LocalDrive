<?php
/**
 * CloudDrive — Shared Helpers
 */

define('BASE_DIR',    dirname(__DIR__));
define('PROJECT_ROOT', dirname(BASE_DIR));
if (!defined('STORAGE_ROOT')) define('STORAGE_ROOT', PROJECT_ROOT . DIRECTORY_SEPARATOR . 'storage');
if (!defined('STORAGE_DIR')) define('STORAGE_DIR', STORAGE_ROOT . DIRECTORY_SEPARATOR . 'main');
if (!defined('CHUNKS_DIR')) define('CHUNKS_DIR', STORAGE_ROOT . DIRECTORY_SEPARATOR . 'chunk');
if (!defined('CACHE_DIR')) define('CACHE_DIR', STORAGE_ROOT . DIRECTORY_SEPARATOR . 'cache');
if (!defined('TRASH_DIR')) define('TRASH_DIR', STORAGE_ROOT . DIRECTORY_SEPARATOR . 'trash');
define('CONFIG_FILE', PROJECT_ROOT . DIRECTORY_SEPARATOR . 'config.json');

function get_config() {
    if (isset($GLOBALS['clouddrive_config_cache']) && is_array($GLOBALS['clouddrive_config_cache'])) {
        return $GLOBALS['clouddrive_config_cache'];
    }
    $def = [
        'buffer_size' => 2048, 
        'memory_limit' => 128,
        'chunk_size' => 5,
        'max_uploads' => 3,
        'thumbnail_quality' => 80
    ];
    $stored = file_exists(CONFIG_FILE) ? json_decode((string)file_get_contents(CONFIG_FILE), true) : [];
    return $GLOBALS['clouddrive_config_cache'] = array_merge($def, is_array($stored) ? $stored : []);
}

function save_config($data) {
    $cfg = get_config();
    $new = array_merge($cfg, $data);
    $json = json_encode($new, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES);
    if ($json === false || !atomic_write_file(CONFIG_FILE, $json)) {
        throw new RuntimeException('Could not save configuration');
    }
    $GLOBALS['clouddrive_config_cache'] = $new;
    return $new;
}

function atomic_write_file($path, $contents) {
    $temporary = $path . '.' . bin2hex(random_bytes(6)) . '.tmp';
    if (file_put_contents($temporary, $contents, LOCK_EX) === false) {
        @unlink($temporary);
        return false;
    }
    if (!@rename($temporary, $path)) {
        @unlink($temporary);
        return false;
    }
    return true;
}

$cfg = get_config();
define('BUFFER_SIZE', max(128, (int)$cfg['buffer_size']) * 1024);
function init_storage() {
    static $initialized = false;
    if ($initialized) return;
    foreach ([STORAGE_DIR, CHUNKS_DIR, CACHE_DIR, TRASH_DIR] as $dir) {
        if (!is_dir($dir)) mkdir($dir, 0755, true);
    }
    if (!is_dir(CACHE_DIR . DIRECTORY_SEPARATOR . 'thumbs')) mkdir(CACHE_DIR . DIRECTORY_SEPARATOR . 'thumbs', 0755, true);
    if (!is_dir(CACHE_DIR . DIRECTORY_SEPARATOR . 'hq')) mkdir(CACHE_DIR . DIRECTORY_SEPARATOR . 'hq', 0755, true);
    $initialized = true;
}

function sanitize_path($path) {
    $path = str_replace('\\', '/', $path);
    $path = preg_replace('#/+#', '/', $path);
    $parts = [];
    foreach (explode('/', $path) as $p) {
        if ($p === '..' || $p === '.' || $p === '') continue;
        $parts[] = $p;
    }
    return '/' . implode('/', $parts);
}

function get_real_path($virtual) {
    $san = sanitize_path($virtual);
    return STORAGE_DIR . str_replace('/', DIRECTORY_SEPARATOR, $san);
}

function is_reserved_storage_name($name) {
    return stripos($name, '.clouddrive-stage-') === 0;
}

function has_reserved_storage_path($path) {
    foreach (preg_split('#[\\\\/]+#', (string)$path) as $segment) {
        if (is_reserved_storage_name($segment)) return true;
    }
    return false;
}

function is_internal_storage_name($name) {
    return $name === '.chunks' || is_reserved_storage_name($name);
}

function cleanup_stale_internal_entry($path, $maxAge = 86400) {
    if (!is_internal_storage_name(basename($path)) || !file_exists($path)) return;
    if (time() - (@filemtime($path) ?: time()) < $maxAge) return;
    is_dir($path) ? delete_directory($path) : @unlink($path);
}

function format_size($bytes) {
    $u = ['B','KB','MB','GB','TB'];
    $i = 0; $v = (float)$bytes;
    while ($v >= 1024 && $i < 4) { $v /= 1024; $i++; }
    return round($v, 2) . ' ' . $u[$i];
}

function with_cache_file_lock($path, callable $callback) {
    $lock = @fopen($path, 'c');
    if (!is_resource($lock) || !@flock($lock, LOCK_EX)) {
        if (is_resource($lock)) fclose($lock);
        return $callback();
    }
    try {
        return $callback();
    } finally {
        @flock($lock, LOCK_UN);
        fclose($lock);
    }
}

function invalidate_dir_cache($realDir) {
    $base = realpath($realDir) ?: $realDir;
    $keyAll = md5($base . '_all');
    $keyFolders = md5($base . '_folders');
    $version = bin2hex(random_bytes(12));
    foreach ([$keyAll, $keyFolders] as $key) {
        with_cache_file_lock(CACHE_DIR . DIRECTORY_SEPARATOR . 'dir_' . $key . '.lock', static function () use ($key, $version) {
            $cacheFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'dir_' . $key . '.json';
            @atomic_write_file(CACHE_DIR . DIRECTORY_SEPARATOR . 'dir_' . $key . '.generation', $version);
            @unlink($cacheFile);
            @unlink($cacheFile . '.etag');
            @unlink($cacheFile . '.version');
        });
    }
    $folderPrefix = CACHE_DIR . DIRECTORY_SEPARATOR . 'thumbs' . DIRECTORY_SEPARATOR . 'folder_' . md5(str_replace('\\', '/', $base));
    foreach (glob($folderPrefix . '*.jpg') ?: [] as $thumbnail) @unlink($thumbnail);
}

function invalidate_tree_cache() {
    $cacheFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'tree.json';
    with_cache_file_lock($cacheFile . '.lock', static function () use ($cacheFile) {
        @atomic_write_file(CACHE_DIR . DIRECTORY_SEPARATOR . 'manifest.version', bin2hex(random_bytes(12)));
        @unlink($cacheFile);
        @unlink($cacheFile . '.etag');
        @unlink($cacheFile . '.version');
    });
}

function get_dir_size($dir) {
    if (!is_dir($dir)) return 0;
    $size = 0;
    try {
        $iterator = new RecursiveIteratorIterator(
            new RecursiveDirectoryIterator($dir, RecursiveDirectoryIterator::SKIP_DOTS)
        );
        foreach ($iterator as $file) {
            if ($file->getFilename() === 'metadata.json') continue;
            $size += $file->getSize();
        }
    } catch (Exception $e) {}
    return $size;
}

function json_response($data, $status = 200) {
    $json = json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
    if ($json === false) {
        $status = 500;
        $json = '{"success":false,"error":"Could not encode response"}';
    }
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Content-Length: ' . strlen($json));
    echo $json;
    exit;
}

function error_response($msg, $status = 400) {
    json_response(['success' => false, 'error' => $msg], $status);
}

function get_file_type($ext) {
    static $lookup = null;
    if ($lookup === null) {
        $groups = [
        'image'    => ['jpg','jpeg','png','gif','bmp','svg','webp','ico','tiff','avif','psd','psb','heic','heif','raw','cr2','nef','arw','sai','sai2'],
        'video'    => ['mp4','avi','mkv','mov','wmv','flv','webm','m4v','ts'],
        'audio'    => ['mp3','wav','flac','aac','ogg','wma','m4a','opus'],
        'document' => ['pdf','doc','docx','xls','xlsx','ppt','pptx','odt','ods'],
        'text'     => ['txt','md','rtf','csv','log','ini','cfg'],
        'code'     => ['php','js','ts','html','css','py','java','c','cpp','h','json','xml','yml','yaml','sql','sh','bat','rb','go','rs'],
        'archive'  => ['zip','rar','7z','tar','gz','bz2','xz','iso'],
        ];
        $lookup = [];
        foreach ($groups as $type => $extensions) {
            foreach ($extensions as $extension) $lookup[$extension] = $type;
        }
    }
    return $lookup[strtolower($ext)] ?? 'file';
}

function known_mime_type($extension) {
    static $map = [
        'jpg'=>'image/jpeg','jpeg'=>'image/jpeg','png'=>'image/png','gif'=>'image/gif',
        'svg'=>'image/svg+xml','webp'=>'image/webp','avif'=>'image/avif','bmp'=>'image/bmp','ico'=>'image/x-icon',
        'heic'=>'image/heic','heif'=>'image/heif','tif'=>'image/tiff','tiff'=>'image/tiff',
        'mp4'=>'video/mp4','webm'=>'video/webm','mkv'=>'video/x-matroska','avi'=>'video/x-msvideo',
        'mov'=>'video/quicktime','m4v'=>'video/x-m4v','wmv'=>'video/x-ms-wmv','ts'=>'video/mp2t',
        'mp3'=>'audio/mpeg','wav'=>'audio/wav','ogg'=>'audio/ogg','oga'=>'audio/ogg','opus'=>'audio/opus',
        'flac'=>'audio/flac','aac'=>'audio/aac','m4a'=>'audio/mp4','wma'=>'audio/x-ms-wma',
        'pdf'=>'application/pdf','zip'=>'application/zip','gz'=>'application/gzip','tar'=>'application/x-tar',
        '7z'=>'application/x-7z-compressed','rar'=>'application/vnd.rar',
        'json'=>'application/json','html'=>'text/html','css'=>'text/css',
        'js'=>'application/javascript','ts'=>'text/plain','txt'=>'text/plain','md'=>'text/markdown','rtf'=>'application/rtf',
        'csv'=>'text/csv','xml'=>'application/xml','yml'=>'text/yaml','yaml'=>'text/yaml','log'=>'text/plain',
        'doc'=>'application/msword','docx'=>'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'xls'=>'application/vnd.ms-excel','xlsx'=>'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'ppt'=>'application/vnd.ms-powerpoint','pptx'=>'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    ];
    return $map[strtolower($extension)] ?? null;
}

function get_metadata_mime_type($filepath) {
    $known = known_mime_type(pathinfo($filepath, PATHINFO_EXTENSION));
    return $known ?: get_mime_type($filepath);
}

function get_mime_type($filepath) {
    if (function_exists('mime_content_type')) {
        $mime = @mime_content_type($filepath);
        if ($mime && $mime !== 'application/octet-stream') return $mime;
    }
    return known_mime_type(pathinfo($filepath, PATHINFO_EXTENSION)) ?: 'application/octet-stream';
}

function delete_directory($dir) {
    if (!is_dir($dir)) return false;
    $items = scandir($dir);
    foreach ($items as $item) {
        if ($item === '.' || $item === '..') continue;
        $path = $dir . DIRECTORY_SEPARATOR . $item;
        is_dir($path) ? delete_directory($path) : unlink($path);
    }
    return rmdir($dir);
}

function get_directory_size($dir) {
    $size = 0;
    if (!is_dir($dir)) return 0;
    $it = new RecursiveIteratorIterator(
        new RecursiveDirectoryIterator($dir, RecursiveDirectoryIterator::SKIP_DOTS),
        RecursiveIteratorIterator::LEAVES_ONLY
    );
    foreach ($it as $f) { if ($f->isFile()) $size += $f->getSize(); }
    return $size;
}

function get_storage_info_cached($maximumAge = 10) {
    $generationFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'manifest.version';
    if (!is_file($generationFile)) @atomic_write_file($generationFile, bin2hex(random_bytes(12)));
    $generation = @file_get_contents($generationFile) ?: '';
    $cacheFile = CACHE_DIR . DIRECTORY_SEPARATOR . 'storage-info.json';
    $readCache = static function ($allowStale = false) use ($cacheFile, $maximumAge) {
        if (!is_file($cacheFile)) return null;
        $cached = json_decode((string)@file_get_contents($cacheFile), true);
        if (!is_array($cached) || !is_array($cached['data'] ?? null)) return null;
        if (!$allowStale && time() - (int)($cached['generated_at'] ?? 0) > $maximumAge) return null;
        return $cached['data'] ?? null;
    };
    $cached = $readCache();
    if (is_array($cached)) return $cached;

    $lock = @fopen(CACHE_DIR . DIRECTORY_SEPARATOR . 'storage-info.lock', 'c');
    if (is_resource($lock) && !@flock($lock, LOCK_EX | LOCK_NB)) {
        $stale = $readCache(true);
        if (is_array($stale)) {
            fclose($lock);
            return $stale;
        }
        @flock($lock, LOCK_EX);
    }
    try {
        $cached = $readCache();
        if (is_array($cached)) return $cached;
        $used = get_directory_size(STORAGE_DIR);
        $free = @disk_free_space(STORAGE_DIR) ?: 0;
        $data = ['used_space' => $used, 'total_space' => $used + $free, 'free_space' => $free];
        @atomic_write_file($cacheFile, json_encode([
            'generation' => @file_get_contents($generationFile) ?: $generation,
            'generated_at' => time(),
            'data' => $data,
        ], JSON_UNESCAPED_SLASHES));
        return $data;
    } finally {
        if (is_resource($lock)) {
            @flock($lock, LOCK_UN);
            fclose($lock);
        }
    }
}

init_storage();
