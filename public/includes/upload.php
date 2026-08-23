<?php
/**
 * CloudDrive — Chunked Upload Handler
 * Streams php://input → disk in 8KB buffers. Peak RAM ~8KB per upload.
 */

require_once __DIR__ . '/helpers.php';

function handle_upload_chunk() {
    $fileId      = $_SERVER['HTTP_X_FILE_ID'] ?? '';
    $chunkIndex  = isset($_SERVER['HTTP_X_CHUNK_INDEX']) ? intval($_SERVER['HTTP_X_CHUNK_INDEX']) : -1;
    $totalChunks = isset($_SERVER['HTTP_X_TOTAL_CHUNKS']) ? intval($_SERVER['HTTP_X_TOTAL_CHUNKS']) : 0;
    $filename    = isset($_SERVER['HTTP_X_FILENAME']) ? urldecode($_SERVER['HTTP_X_FILENAME']) : '';
    $folder      = isset($_SERVER['HTTP_X_FOLDER']) ? urldecode($_SERVER['HTTP_X_FOLDER']) : '/';
    $fileSize    = isset($_SERVER['HTTP_X_FILE_SIZE']) ? intval($_SERVER['HTTP_X_FILE_SIZE']) : 0;
    $chunkSize   = isset($_SERVER['HTTP_X_CHUNK_SIZE']) ? intval($_SERVER['HTTP_X_CHUNK_SIZE']) : (5 * 1024 * 1024);

    if ($folder === '/Trash' || strpos($folder, '/Trash/') === 0) {
        error_response('Cannot upload to Trash', 400);
    }

    if (!$fileId || $chunkIndex < 0 || $totalChunks <= 0 || !$filename) {
        error_response('Missing required headers: X-File-Id, X-Chunk-Index, X-Total-Chunks, X-Filename');
    }

    $fileId   = preg_replace('/[^a-zA-Z0-9_\-]/', '', $fileId);
    $chunkDir = CHUNKS_DIR . DIRECTORY_SEPARATOR . $fileId;
    if (!is_dir($chunkDir)) mkdir($chunkDir, 0755, true);

    $partFile = $chunkDir . DIRECTORY_SEPARATOR . $fileId . '.part';

    // Open file without truncating, allowing random access
    $fp = fopen($partFile, 'c');
    if (!$fp) error_response('Failed to open part file', 500);

    // Pre-allocate space to avoid fragmentation
    if (filesize($partFile) === 0 && $fileSize > 0) {
        ftruncate($fp, $fileSize);
    }

    $offset = $chunkIndex * $chunkSize;
    fseek($fp, $offset);

    if (isset($_FILES['chunk']) && $_FILES['chunk']['error'] === UPLOAD_ERR_OK) {
        $chunkData = fopen($_FILES['chunk']['tmp_name'], 'rb');
        $bytes = stream_copy_to_stream($chunkData, $fp);
        fclose($chunkData);
        @unlink($_FILES['chunk']['tmp_name']);
    } else {
        $in = fopen('php://input', 'rb');
        $bytes = stream_copy_to_stream($in, $fp);
        fclose($in);
    }
    
    fclose($fp);

    json_response(['success' => true, 'chunk_index' => $chunkIndex, 'bytes_written' => $bytes]);
}

function handle_upload_complete() {
    $data = json_decode(file_get_contents('php://input'), true);

    $fileId      = $data['file_id'] ?? '';
    $filename    = $data['filename'] ?? '';
    $totalChunks = intval($data['total_chunks'] ?? 0);
    $folder      = $data['folder'] ?? '/';

    if ($folder === '/Trash' || strpos($folder, '/Trash/') === 0) {
        error_response('Cannot upload to Trash', 400);
    }

    if (!$fileId || !$filename || $totalChunks <= 0) {
        error_response('Missing: file_id, filename, total_chunks');
    }

    set_time_limit(0); // Prevent PHP timeout during long operations
    
    $fileId   = preg_replace('/[^a-zA-Z0-9_\-]/', '', $fileId);
    $chunkDir = CHUNKS_DIR . DIRECTORY_SEPARATOR . $fileId;
    $partFile = $chunkDir . DIRECTORY_SEPARATOR . $fileId . '.part';
    
    $targetDir = get_real_path($folder);
    if (!is_dir($targetDir)) mkdir($targetDir, 0755, true);

    $safeName = preg_replace('/[<>:"\/\\\\|?*\x00-\x1f]/', '', basename($filename));
    if (is_reserved_storage_name($safeName)) error_response('Reserved file name');
    if (!$safeName) $safeName = 'unnamed_file';
    $targetPath = $targetDir . DIRECTORY_SEPARATOR . $safeName;

    // Deduplicate
    if (file_exists($targetPath)) {
        $info = pathinfo($safeName);
        $base = $info['filename'];
        $ext  = isset($info['extension']) ? '.' . $info['extension'] : '';
        $c = 1;
        while (file_exists($targetDir . DIRECTORY_SEPARATOR . $base . " ($c)" . $ext)) $c++;
        $safeName   = $base . " ($c)" . $ext;
        $targetPath = $targetDir . DIRECTORY_SEPARATOR . $safeName;
    }

    if (file_exists($partFile)) {
        // NEW METHOD: Instant rename of pre-allocated file
        if (!rename($partFile, $targetPath)) error_response('Cannot move file', 500);
    } else if (file_exists($chunkDir . DIRECTORY_SEPARATOR . 'chunk_000000')) {
        // OLD METHOD FALLBACK: Assemble all chunks
        $firstChunk = $chunkDir . DIRECTORY_SEPARATOR . 'chunk_000000';
        if (!rename($firstChunk, $targetPath)) error_response('Cannot move first chunk', 500);
        
        if ($totalChunks > 1) {
            $out = fopen($targetPath, 'ab');
            if (!$out) error_response('Cannot open target for appending', 500);

            // Use an 8MB buffer to optimize IO speed without exhausting memory on large chunks
            $bufferSize = 8 * 1024 * 1024; 

            for ($i = 1; $i < $totalChunks; $i++) {
                $cf = $chunkDir . DIRECTORY_SEPARATOR . 'chunk_' . str_pad($i, 6, '0', STR_PAD_LEFT);
                if (!file_exists($cf)) { fclose($out); @unlink($targetPath); error_response("Missing chunk $i", 500); }
                
                $in = fopen($cf, 'rb');
                if ($in) {
                    while (!feof($in)) {
                        $data = fread($in, $bufferSize);
                        if ($data === false || $data === '') break;
                        fwrite($out, $data);
                    }
                    fclose($in);
                }
                @unlink($cf);
            }
            fclose($out);
        }
    } else {
        error_response('Upload chunks not found', 404);
    }

    // Cleanup
    delete_directory($chunkDir);

    // Invalidate directory cache
    invalidate_dir_cache($targetDir);
    invalidate_tree_cache();

    $ext = strtolower(pathinfo($safeName, PATHINFO_EXTENSION));
    json_response([
        'success' => true,
        'file'    => [
            'name'      => $safeName,
            'type'      => 'file',
            'size'      => filesize($targetPath),
            'modified'  => filemtime($targetPath),
            'extension' => $ext,
            'icon'      => get_file_type($ext),
            'path'      => sanitize_path($folder)
        ]
    ]);
}
