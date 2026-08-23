<?php
require_once __DIR__ . '/../../../_init.php';
require_once BASE_DIR . '/includes/upload.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') error_response('Method not allowed', 405);

$data = json_decode(file_get_contents('php://input'), true);
$fileId = $data['file_id'] ?? '';

if (!$fileId) error_response('Missing file_id');
$fileId = preg_replace('/[^a-zA-Z0-9_\-]/', '', $fileId);

$chunkDir = CHUNKS_DIR . DIRECTORY_SEPARATOR . $fileId;
if (is_dir($chunkDir)) {
    array_map('unlink', glob($chunkDir . DIRECTORY_SEPARATOR . 'chunk_*'));
    array_map('unlink', glob($chunkDir . DIRECTORY_SEPARATOR . '*.part'));
    @rmdir($chunkDir);
}

json_response(['success' => true]);
