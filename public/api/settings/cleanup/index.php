<?php
require_once dirname(__DIR__, 2) . '/_init.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') error_response('Method not allowed', 405);

$data = json_decode(file_get_contents('php://input'), true);
$action = $data['action'] ?? '';

if (!in_array($action, ['clean_chunk', 'clean_syscache', 'clean_imgcache', 'clean_trash'])) {
    error_response('Invalid cleanup action');
}

$target_dir = '';
if ($action === 'clean_chunk') $target_dir = CHUNKS_DIR;
if ($action === 'clean_trash') $target_dir = TRASH_DIR;

function empty_dir($dir, $skipDirs = []) {
    if (!is_dir($dir)) return;
    $iterator = new RecursiveIteratorIterator(
        new RecursiveDirectoryIterator($dir, RecursiveDirectoryIterator::SKIP_DOTS),
        RecursiveIteratorIterator::CHILD_FIRST
    );
    foreach ($iterator as $file) {
        $real = str_replace('\\', '/', $file->getRealPath());
        $skip = false;
        foreach ($skipDirs as $sd) {
            if (strpos($real, str_replace('\\', '/', $sd)) === 0) {
                $skip = true; break;
            }
        }
        if ($skip) continue;
        
        if ($file->isDir()) {
            @rmdir($file->getRealPath());
        } else {
            @unlink($file->getRealPath());
        }
    }
}

if ($action === 'clean_syscache') {
    // Delete only files directly in CACHE_DIR (tree.json, dir_*.json)
    $iterator = new DirectoryIterator(CACHE_DIR);
    foreach ($iterator as $fileinfo) {
        if ($fileinfo->isFile()) {
            @unlink($fileinfo->getRealPath());
        }
    }
} elseif ($action === 'clean_imgcache') {
    // Empty CACHE_DIR/thumbs and CACHE_DIR/hq
    empty_dir(CACHE_DIR . DIRECTORY_SEPARATOR . 'thumbs');
    empty_dir(CACHE_DIR . DIRECTORY_SEPARATOR . 'hq');
    // Clean up orphaned .jpg files directly in CACHE_DIR (legacy)
    $legacy = new DirectoryIterator(CACHE_DIR);
    foreach ($legacy as $f) {
        if ($f->isFile() && $f->getExtension() === 'jpg') {
            @unlink($f->getRealPath());
        }
    }
} else {
    empty_dir($target_dir);
}



json_response(['success' => true]);
