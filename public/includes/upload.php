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

    if (!$fileId || $chunkIndex < 0 || $totalChunks <= 0 || $chunkIndex >= $totalChunks || !$filename
        || $fileSize < 0 || $chunkSize <= 0) {
        error_response('Missing required headers: X-File-Id, X-Chunk-Index, X-Total-Chunks, X-Filename');
    }

    $fileId   = preg_replace('/[^a-zA-Z0-9_\-]/', '', $fileId);
    if ($fileId === '') error_response('Invalid file ID');
    $maximumChunkSize = max(1, (int)(get_config()['chunk_size'] ?? 16)) * 1024 * 1024;
    if ($chunkSize > $maximumChunkSize) error_response('Chunk is too large', 413);
    $offset = $chunkIndex * $chunkSize;
    if ($offset < 0 || ($fileSize === 0 && ($chunkIndex !== 0 || $totalChunks !== 1))
        || ($fileSize > 0 && $offset >= $fileSize)) {
        error_response('Invalid chunk range');
    }
    $expectedTotalChunks = max(1, (int)ceil($fileSize / $chunkSize));
    if ($totalChunks !== $expectedTotalChunks) error_response('Invalid total chunk count');
    $expectedBytes = $fileSize === 0 ? 0 : min($chunkSize, $fileSize - $offset);
    $chunkDir = CHUNKS_DIR . DIRECTORY_SEPARATOR . $fileId;
    if (!is_dir($chunkDir) && !mkdir($chunkDir, 0755, true) && !is_dir($chunkDir)) {
        error_response('Failed to create chunk directory', 500);
    }

    $partFile = $chunkDir . DIRECTORY_SEPARATOR . $fileId . '.part';
    $metadataFile = $chunkDir . DIRECTORY_SEPARATOR . 'upload.json';
    $expectedMetadata = [
        'file_id' => $fileId,
        'filename' => $filename,
        'folder' => $folder,
        'file_size' => $fileSize,
        'chunk_size' => $chunkSize,
        'total_chunks' => $totalChunks,
    ];

    $lock = @fopen($chunkDir . DIRECTORY_SEPARATOR . 'upload.lock', 'c');
    if (!is_resource($lock) || !flock($lock, LOCK_EX)) error_response('Failed to lock upload', 500);
    $metadataValid = true;
    try {
        if (is_file($metadataFile)) {
            $metadata = json_decode((string)file_get_contents($metadataFile), true);
            $metadataValid = is_array($metadata) && $metadata === $expectedMetadata;
        } else {
            $metadataValid = atomic_write_file(
                $metadataFile,
                json_encode($expectedMetadata, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES)
            );
        }
        if ($metadataValid) {
            $preallocated = fopen($partFile, 'c+b');
            if (!$preallocated) {
                $metadataValid = false;
            } else {
                clearstatcache(true, $partFile);
                if (@filesize($partFile) === 0 && $fileSize > 0 && !ftruncate($preallocated, $fileSize)) {
                    $metadataValid = false;
                }
                fclose($preallocated);
            }
        }
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
    }
    if (!$metadataValid) error_response('Upload metadata does not match', 409);

    $fp = fopen($partFile, 'c+b');
    if (!$fp) error_response('Failed to open part file', 500);
    $marker = $chunkDir . DIRECTORY_SEPARATOR . 'chunk_' . str_pad((string)$chunkIndex, 6, '0', STR_PAD_LEFT) . '.done';
    @unlink($marker);
    if (fseek($fp, $offset) !== 0) {
        fclose($fp);
        error_response('Invalid chunk offset', 500);
    }

    if (isset($_FILES['chunk']) && $_FILES['chunk']['error'] === UPLOAD_ERR_OK) {
        if ((int)($_FILES['chunk']['size'] ?? -1) !== $expectedBytes) {
            fclose($fp);
            error_response('Chunk size does not match', 400);
        }
        $chunkData = fopen($_FILES['chunk']['tmp_name'], 'rb');
        $bytes = stream_copy_to_stream($chunkData, $fp, $expectedBytes);
        fclose($chunkData);
        @unlink($_FILES['chunk']['tmp_name']);
    } else {
        if (isset($_SERVER['CONTENT_LENGTH']) && (int)$_SERVER['CONTENT_LENGTH'] !== $expectedBytes) {
            fclose($fp);
            error_response('Chunk size does not match', 400);
        }
        $in = fopen('php://input', 'rb');
        $bytes = stream_copy_to_stream($in, $fp, $expectedBytes);
        fclose($in);
    }
    fflush($fp);
    fclose($fp);
    if ($bytes === false || (int)$bytes !== $expectedBytes) {
        error_response('Incomplete chunk', 400);
    }
    if (!atomic_write_file($marker, (string)$expectedBytes)) error_response('Could not record chunk', 500);

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
    if ($fileId === '') error_response('Invalid file ID');
    $chunkDir = CHUNKS_DIR . DIRECTORY_SEPARATOR . $fileId;
    $partFile = $chunkDir . DIRECTORY_SEPARATOR . $fileId . '.part';
    $metadataFile = $chunkDir . DIRECTORY_SEPARATOR . 'upload.json';
    $metadata = is_file($metadataFile) ? json_decode((string)file_get_contents($metadataFile), true) : null;
    if (file_exists($partFile)) {
        if (!is_array($metadata)
            || (string)($metadata['filename'] ?? '') !== (string)$filename
            || (string)($metadata['folder'] ?? '') !== (string)$folder
            || (int)($metadata['total_chunks'] ?? 0) !== $totalChunks) {
            error_response('Upload metadata does not match', 409);
        }
        $fileSize = (int)$metadata['file_size'];
        $chunkSize = (int)$metadata['chunk_size'];
        for ($index = 0; $index < $totalChunks; $index++) {
            $expectedBytes = $fileSize === 0 ? 0 : min($chunkSize, $fileSize - ($index * $chunkSize));
            $marker = $chunkDir . DIRECTORY_SEPARATOR . 'chunk_' . str_pad((string)$index, 6, '0', STR_PAD_LEFT) . '.done';
            if (!is_file($marker) || (int)file_get_contents($marker) !== $expectedBytes) {
                error_response("Missing or incomplete chunk $index", 409);
            }
        }
        clearstatcache(true, $partFile);
        if ((int)filesize($partFile) !== $fileSize) error_response('Uploaded file size does not match', 409);
    }
    
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
            'path'      => rtrim(sanitize_path($folder), '/') . '/' . $safeName
        ]
    ]);
}
