<?php
/**
 * CloudDrive — Thumbnail Generator
 * Generates & caches thumbnails for images (GD) and videos (ffmpeg).
 * Cache key = md5(realpath + mtime) to auto-invalidate on file change.
 */

require_once __DIR__ . '/helpers.php';

define('THUMB_MAX_W', 300);
define('THUMB_MAX_H', 300);
define('THUMBS_DIR', CACHE_DIR . DIRECTORY_SEPARATOR . 'thumbs');
define('HQ_DIR', CACHE_DIR . DIRECTORY_SEPARATOR . 'hq');
define('HQ_MAX_W', 1920);
define('HQ_MAX_H', 1920);
$cfg = get_config();
define('THUMB_QUALITY', max(10, min(100, (int)$cfg['thumbnail_quality'])));
define('MAX_SOURCE_SIZE', 100 * 1024 * 1024);

// Ensure thumbnail directories exist
if (!is_dir(THUMBS_DIR)) @mkdir(THUMBS_DIR, 0755, true);
if (!is_dir(HQ_DIR)) @mkdir(HQ_DIR, 0755, true);

/**
 * Check if we have enough memory to load an image of given dimensions.
 * Tries to increase memory_limit on the fly if needed.
 */
function ensure_memory_for_image($width, $height, $bytesPerPixel = 4) {
    $needed = $width * $height * $bytesPerPixel * 2.5; // GD overhead
    $current = memory_get_usage(true);
    $limit = ini_get('memory_limit');
    if ($limit === '-1') return true;
    $limitBytes = (int)$limit * 1024 * 1024;
    $available = $limitBytes - $current;
    if ($needed > $available) {
        $newLimit = ceil(($current + $needed + 10 * 1024 * 1024) / 1024 / 1024) . 'M';
        @ini_set('memory_limit', $newLimit);
        $limitBytes2 = (int)ini_get('memory_limit') * 1024 * 1024;
        if ($limitBytes2 === $limitBytes) return false; // couldn't increase
    }
    return true;
}

function handle_thumbnail() {
    $path = $_GET['path'] ?? '';
    if (!$path) error_response('No path', 400);

    $isFolder = ($_GET['type'] ?? '') === 'folder';
    $real = get_real_path($path);

    if ($isFolder) {
        if (!is_dir($real)) error_response('Not found', 404);
        $images = find_folder_thumb_images($real);
        if (!$images) error_response('No images in folder', 404);
        $normalized = str_replace('\\', '/', realpath($real) ?: $real);
        $sourceSignature = ['folder-v3', THUMB_MAX_W, THUMB_MAX_H, 70];
        foreach ($images as $image) {
            $sourceSignature[] = basename($image) . ':' . (@filemtime($image) ?: 0) . ':' . (@filesize($image) ?: 0);
        }
        $cacheKey = 'folder_' . md5($normalized) . '_' . substr(hash('sha256', implode('|', $sourceSignature)), 0, 24);
        $cachePath = THUMBS_DIR . DIRECTORY_SEPARATOR . $cacheKey . '.jpg';
        if (thumbnail_cache_is_valid($cachePath)) {
            serve_image($cachePath, $cacheKey);
        }
        if (!publish_thumbnail_cache($cachePath, $cacheKey, static function ($temporary) use ($real, $images) {
            return generate_folder_thumb($real, $temporary, $images);
        })) {
            error_response('No images in folder', 404);
        }
        serve_image($cachePath, $cacheKey);
    }

    if (!file_exists($real) || is_dir($real)) error_response('Not found', 404);

    $ext   = strtolower(pathinfo($real, PATHINFO_EXTENSION));
    $type  = get_file_type($ext);

    if (!in_array($type, ['image', 'video'])) {
        error_response('No thumbnail for this type', 404);
    }

    // Select directory based on size
    $isHq = ($_GET['size'] ?? '') === 'hq';
    $cacheDir = $isHq ? HQ_DIR : THUMBS_DIR;
    $key = thumbnail_source_key($real, $isHq ? 'hq' : 'thumbnail');
    $cacheExtension = $ext === 'ico' ? 'ico' : 'jpg';
    $cachePath = $cacheDir . DIRECTORY_SEPARATOR . $key . '.' . $cacheExtension;

    // Serve from cache
    if (thumbnail_cache_is_valid($cachePath)) {
        serve_image($cachePath, $key);
    }

    $generated = publish_thumbnail_cache($cachePath, $key, static function ($temporary) use ($type, $ext, $isHq, $real) {
        if ($type === 'image') {
            if ($ext === 'ico') {
                return copy($real, $temporary);
            }
            return $isHq
                ? generate_hq_preview($real, $ext, $temporary)
                : generate_image_thumb($real, $ext, $temporary);
        }
        return generate_video_thumb($real, $temporary);
    });
    if (!$generated) error_response('Generation failed', 500);
    serve_image($cachePath, $key);
}

function handle_thumbnail_upload() {
    $path = $_GET['path'] ?? '';
    if (!$path) error_response('No path', 400);

    $real = get_real_path($path);
    if (!file_exists($real) || is_dir($real)) error_response('Not found', 404);

    $key = thumbnail_source_key($real, 'thumbnail');
    $cachePath = THUMBS_DIR . DIRECTORY_SEPARATOR . $key . '.jpg';

    $data = file_get_contents('php://input');
    $d = json_decode($data, true);
    if (!$d || empty($d['image'])) error_response('No image data');

    $b64 = $d['image'];
    if (strpos($b64, 'data:image/jpeg;base64,') === 0) {
        $b64 = substr($b64, 23);
    }

    $decoded = base64_decode($b64, true);
    if (!$decoded || strlen($decoded) > 8 * 1024 * 1024) error_response('Invalid base64');
    $imageInfo = @getimagesizefromstring($decoded);
    if (!$imageInfo || ($imageInfo['mime'] ?? '') !== 'image/jpeg') error_response('Invalid JPEG');

    if (!publish_thumbnail_cache($cachePath, $key, static function ($temporary) use ($decoded) {
        return file_put_contents($temporary, $decoded, LOCK_EX) !== false;
    }, true)) error_response('Could not save thumbnail', 500);
    json_response(['success' => true]);
}

function thumbnail_source_key($path, $variant) {
    $normalized = str_replace('\\', '/', realpath($path) ?: $path);
    return hash('sha256', implode('|', [
        'thumbnail-v4', $normalized, $variant,
        @filemtime($path) ?: 0, @filectime($path) ?: 0, @filesize($path) ?: 0,
        THUMB_MAX_W, THUMB_MAX_H, HQ_MAX_W, HQ_MAX_H, THUMB_QUALITY,
    ]));
}

function thumbnail_cache_is_valid($path) {
    return is_file($path) && (@filesize($path) ?: 0) > 0;
}

function publish_thumbnail_cache($cachePath, $cacheKey, callable $generator, $replace = false) {
    if (!$replace && thumbnail_cache_is_valid($cachePath)) return true;
    $failedPath = $cachePath . '.failed';
    if (!$replace && is_file($failedPath) && time() - (@filemtime($failedPath) ?: 0) < 30) return false;

    $lockDirectory = CACHE_DIR . DIRECTORY_SEPARATOR . 'thumbnail-locks';
    if (!is_dir($lockDirectory)) @mkdir($lockDirectory, 0755, true);
    $lock = @fopen($lockDirectory . DIRECTORY_SEPARATOR . substr(hash('sha256', $cacheKey), 0, 2) . '.lock', 'c');
    if (!is_resource($lock) || !flock($lock, LOCK_EX)) {
        if (is_resource($lock)) fclose($lock);
        return false;
    }

    $temporary = null;
    try {
        if (!$replace && thumbnail_cache_is_valid($cachePath)) return true;
        if (!$replace && is_file($failedPath) && time() - (@filemtime($failedPath) ?: 0) < 30) return false;
        $temporary = tempnam(dirname($cachePath), '.thumb-');
        if ($temporary === false || !$generator($temporary) || !thumbnail_cache_is_valid($temporary)) {
            @touch($failedPath);
            return false;
        }
        if (!@rename($temporary, $cachePath)) {
            @touch($failedPath);
            return false;
        }
        $temporary = null;
        @unlink($failedPath);
        return true;
    } finally {
        if ($temporary) @unlink($temporary);
        flock($lock, LOCK_UN);
        fclose($lock);
    }
}

function extract_psd_full($src) {
    require_once __DIR__ . '/PSDReader.php';
    if (class_exists('PSDReader')) {
        try {
            $reader = new PSDReader($src);
            $img = @$reader->getImage();
            if ($img && (is_resource($img) || $img instanceof GdImage)) {
                return $img;
            }
        } catch (Exception $e) {}
    }
    
    $fp = @fopen($src, 'rb');
    if (!$fp) return false;
    
    try {
        $sig = fread($fp, 4);
        if ($sig !== '8BPS') return false;
        
        $ver = unpack('n', fread($fp, 2))[1];
        if ($ver !== 1 && $ver !== 2) return false;
        
        fseek($fp, 6, SEEK_CUR);
        $channels = unpack('n', fread($fp, 2))[1];
        $height = unpack('N', fread($fp, 4))[1];
        $width = unpack('N', fread($fp, 4))[1];
        $depth = unpack('n', fread($fp, 2))[1];
        $colorMode = unpack('n', fread($fp, 2))[1];
        
        $cmLen = unpack('N', fread($fp, 4))[1];
        if ($cmLen > 0) fseek($fp, $cmLen, SEEK_CUR);
        
        $irLen = unpack('N', fread($fp, 4))[1];
        $irEnd = ftell($fp) + $irLen;
        
        while (ftell($fp) < $irEnd) {
            $rSig = fread($fp, 4);
            if ($rSig !== '8BIM' && $rSig !== 'MeSa') break;
            
            $resId = unpack('n', fread($fp, 2))[1];
            
            $nameLen = ord(fread($fp, 1));
            if ($nameLen > 0) {
                fseek($fp, $nameLen, SEEK_CUR);
            }
            if (($nameLen + 1) % 2 !== 0) fseek($fp, 1, SEEK_CUR);
            
            $dataLen = unpack('N', fread($fp, 4))[1];
            
            if ($resId === 1033 || $resId === 1036) {
                $thumbHeader = fread($fp, 28);
                $format = unpack('N', substr($thumbHeader, 0, 4))[1];
                
                if ($format === 1) { // kJpegRGB
                    $jpegLen = $dataLen - 28;
                    if ($jpegLen > 0 && $jpegLen < 10 * 1024 * 1024) {
                        $jpegData = fread($fp, $jpegLen);
                        return @imagecreatefromstring($jpegData);
                    }
                }
                break;
            }
            
            $skip = $dataLen;
            if ($skip % 2 !== 0) $skip++;
            fseek($fp, $skip, SEEK_CUR);
        }
    } finally {
        if (is_resource($fp)) fclose($fp);
    }
    
    return false;
}

function generate_psd_thumb($src, $dest) {
    // 1. Try to read the embedded 1033 resource (FAST)
    $fp = @fopen($src, 'rb');
    $fastImg = null;
    if ($fp) {
        try {
            $sig = fread($fp, 4);
            if ($sig === '8BPS') {
                $ver = unpack('n', fread($fp, 2))[1];
                if ($ver === 1 || $ver === 2) {
                    fseek($fp, 6, SEEK_CUR);
                    $channels = unpack('n', fread($fp, 2))[1];
                    $height = unpack('N', fread($fp, 4))[1];
                    $width = unpack('N', fread($fp, 4))[1];
                    $depth = unpack('n', fread($fp, 2))[1];
                    $colorMode = unpack('n', fread($fp, 2))[1];
                    
                    $cmLen = unpack('N', fread($fp, 4))[1];
                    if ($cmLen > 0) fseek($fp, $cmLen, SEEK_CUR);
                    
                    $irLen = unpack('N', fread($fp, 4))[1];
                    $irEnd = ftell($fp) + $irLen;
                    
                    while (ftell($fp) < $irEnd) {
                        $rSig = fread($fp, 4);
                        if ($rSig !== '8BIM' && $rSig !== 'MeSa') break;
                        
                        $resId = unpack('n', fread($fp, 2))[1];
                        $nameLen = ord(fread($fp, 1));
                        if ($nameLen > 0) fseek($fp, $nameLen, SEEK_CUR);
                        if (($nameLen + 1) % 2 !== 0) fseek($fp, 1, SEEK_CUR);
                        
                        $dataLen = unpack('N', fread($fp, 4))[1];
                        
                        if ($resId === 1033 || $resId === 1036) {
                            $thumbHeader = fread($fp, 28);
                            $format = unpack('N', substr($thumbHeader, 0, 4))[1];
                            if ($format === 1) { // kJpegRGB
                                $jpegLen = $dataLen - 28;
                                if ($jpegLen > 0 && $jpegLen < 10 * 1024 * 1024) {
                                    $jpegData = fread($fp, $jpegLen);
                                    $fastImg = @imagecreatefromstring($jpegData);
                                }
                            }
                            break;
                        }
                        
                        $skip = $dataLen;
                        if ($skip % 2 !== 0) $skip++;
                        fseek($fp, $skip, SEEK_CUR);
                    }
                }
            }
        } finally {
            fclose($fp);
        }
    }
    
    // 2. If fast extraction failed, fallback to full composite layer (SLOW)
    $img = $fastImg ?: extract_psd_full($src);
    
    if ($img) {
        $ow = imagesx($img); $oh = imagesy($img);
        $ratio = min(THUMB_MAX_W / $ow, THUMB_MAX_H / $oh, 1);
        $nw = max(1, round($ow * $ratio));
        $nh = max(1, round($oh * $ratio));
        $thumb = scale_image_for_jpeg($img, $nw, $nh, IMG_BILINEAR_FIXED);
        if (!$thumb) {
            imagedestroy($img);
            return false;
        }
        $ok = imagejpeg($thumb, $dest, THUMB_QUALITY);
        imagedestroy($img);
        imagedestroy($thumb);
        return $ok;
    }
    return false;
}


function extract_sai_full($src) {
    if (!extension_loaded('gd')) return false;

    $data = read_from_binary_marker($src, 'jssf', MAX_SOURCE_SIZE);
    if ($data === false) {
        $data = @file_get_contents($src, false, null, 0, 5 * 1024 * 1024);
    }
    if (!$data) return false;
    
    // Try SAI2 (jssf)
    $pos = strpos($data, "jssf");
    if ($pos !== false) {
        $offset = $pos + 4;
        
        $wArr = unpack('v', substr($data, $offset, 2)); $offset += 2;
        $hArr = unpack('v', substr($data, $offset, 2)); $offset += 2;
        $cArr = unpack('v', substr($data, $offset, 2)); $offset += 2;
        
        if ($wArr && $hArr && $cArr) {
            $jssfW = $wArr[1];
            $jssfH = $hArr[1];
            $jssfC = $cArr[1];
            
            $lumaQuant = substr($data, $offset, 64); $offset += 64;
            $chromaQuant = '';
            if ($jssfC > 1) {
                $chromaQuant = substr($data, $offset, 64); $offset += 64;
            }
            
            $jpeg = "\xFF\xD8";
            
            $jpeg .= "\xFF\xDB";
            $dqtLen = ($jssfC > 1 ? 65 : 0) + 67;
            $jpeg .= pack('n', $dqtLen);
            $jpeg .= "\x00"; 
            $jpeg .= $lumaQuant;
            if ($jssfC > 1) {
                $jpeg .= "\x01"; 
                $jpeg .= $chromaQuant;
            }
            
            $huff0 = "\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0A\x0B";
            $huff1 = "\x10\x00\x02\x01\x03\x03\x02\x04\x03\x05\x05\x04\x04\x00\x00\x01\x7D\x01\x02\x03\x00\x04\x11\x05\x12\x21\x31\x41\x06\x13\x51\x61\x07\x22\x71\x14\x32\x81\x91\xA1\x08\x23\x42\xB1\xC1\x15\x52\xD1\xF0\x24\x33\x62\x72\x82\x09\x0A\x16\x17\x18\x19\x1A\x25\x26\x27\x28\x29\x2A\x34\x35\x36\x37\x38\x39\x3A\x43\x44\x45\x46\x47\x48\x49\x4A\x53\x54\x55\x56\x57\x58\x59\x5A\x63\x64\x65\x66\x67\x68\x69\x6A\x73\x74\x75\x76\x77\x78\x79\x7A\x83\x84\x85\x86\x87\x88\x89\x8A\x92\x93\x94\x95\x96\x97\x98\x99\x9A\xA2\xA3\xA4\xA5\xA6\xA7\xA8\xA9\xAA\xB2\xB3\xB4\xB5\xB6\xB7\xB8\xB9\xBA\xC2\xC3\xC4\xC5\xC6\xC7\xC8\xC9\xCA\xD2\xD3\xD4\xD5\xD6\xD7\xD8\xD9\xDA\xE1\xE2\xE3\xE4\xE5\xE6\xE7\xE8\xE9\xEA\xF1\xF2\xF3\xF4\xF5\xF6\xF7\xF8\xF9\xFA";
            $huff2 = "\x01\x00\x03\x01\x01\x01\x01\x01\x01\x01\x01\x01\x00\x00\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0A\x0B";
            $huff3 = "\x11\x00\x02\x01\x02\x04\x04\x03\x04\x07\x05\x04\x04\x00\x01\x02\x77\x00\x01\x02\x03\x11\x04\x05\x21\x31\x06\x12\x41\x51\x07\x61\x71\x13\x22\x32\x81\x08\x14\x42\x91\xA1\xB1\xC1\x09\x23\x33\x52\xF0\x15\x62\x72\xD1\x0A\x16\x24\x34\xE1\x25\xF1\x17\x18\x19\x1A\x26\x27\x28\x29\x2A\x35\x36\x37\x38\x39\x3A\x43\x44\x45\x46\x47\x48\x49\x4A\x53\x54\x55\x56\x57\x58\x59\x5A\x63\x64\x65\x66\x67\x68\x69\x6A\x73\x74\x75\x76\x77\x78\x79\x7A\x82\x83\x84\x85\x86\x87\x88\x89\x8A\x92\x93\x94\x95\x96\x97\x98\x99\x9A\xA2\xA3\xA4\xA5\xA6\xA7\xA8\xA9\xAA\xB2\xB3\xB4\xB5\xB6\xB7\xB8\xB9\xBA\xC2\xC3\xC4\xC5\xC6\xC7\xC8\xC9\xCA\xD2\xD3\xD4\xD5\xD6\xD7\xD8\xD9\xDA\xE2\xE3\xE4\xE5\xE6\xE7\xE8\xE9\xEA\xF2\xF3\xF4\xF5\xF6\xF7\xF8\xF9\xFA";
            
            $jpeg .= "\xFF\xC4";
            $huffLen = 2 + strlen($huff0) + strlen($huff1) + ($jssfC > 1 ? strlen($huff2) + strlen($huff3) : 0);
            $jpeg .= pack('n', $huffLen);
            $jpeg .= $huff0 . $huff1;
            if ($jssfC > 1) {
                $jpeg .= $huff2 . $huff3;
            }
            
            $jpeg .= "\xFF\xC0";
            $jpeg .= pack('n', 8 + ($jssfC * 3));
            $jpeg .= "\x08";
            $jpeg .= pack('n', $jssfH);
            $jpeg .= pack('n', $jssfW);
            $jpeg .= chr($jssfC);
            $jpeg .= "\x01\x11\x00"; 
            if ($jssfC > 1) {
                $jpeg .= "\x02\x11\x01";
                $jpeg .= "\x03\x11\x01";
            }
            
            $jpeg .= "\xFF\xDD";
            $jpeg .= pack('n', 4);
            $jpeg .= pack('n', (int)(ceil($jssfW / 8.0)));
            
            $jpeg .= "\xFF\xDA";
            $jpeg .= pack('n', 6 + ($jssfC * 2));
            $jpeg .= chr($jssfC);
            $jpeg .= "\x01\x00";
            if ($jssfC > 1) {
                $jpeg .= "\x02\x11";
                $jpeg .= "\x03\x11";
            }
            $jpeg .= "\x00\x3F\x00";
            
            $mcuCount = (int)(ceil($jssfH / 8.0));
            for ($row = 0; $row < $mcuCount; $row++) {
                $rowSizeArr = unpack('v', substr($data, $offset, 2));
                if (!$rowSizeArr) break;
                $rowSize = $rowSizeArr[1];
                $offset += 2;
                $rowData = substr($data, $offset, $rowSize); $offset += $rowSize;
                $jpeg .= $rowData;
                if ($row < $mcuCount - 1) {
                    $jpeg .= "\xFF" . chr(0xD0 | ($row & 0b111));
                }
            }
            
            $jpeg .= "\xFF\xD9";
            
            $img = @imagecreatefromstring($jpeg);
            if ($img) return $img;
        }
    }
    
    // Try SAI1 (embedded BMP)
    if (strpos($data, 'SAI') === 0) {
        $bmpPos = strpos($data, 'BM');
        if ($bmpPos !== false && $bmpPos < 1024) {
            $bmpData = substr($data, $bmpPos);
            $img = @imagecreatefromstring($bmpData);
            if ($img) return $img;
        }
    }
    
    return false;
}

function read_from_binary_marker($path, $marker, $maxResultBytes) {
    $stream = @fopen($path, 'rb');
    if (!$stream) return false;
    $overlap = '';
    $absoluteOffset = 0;
    $markerLength = strlen($marker);
    while (!feof($stream)) {
        $chunk = fread($stream, 1024 * 1024);
        if ($chunk === false || $chunk === '') break;
        $search = $overlap . $chunk;
        $position = strpos($search, $marker);
        if ($position !== false) {
            $markerOffset = $absoluteOffset - strlen($overlap) + $position;
            fseek($stream, $markerOffset);
            $data = stream_get_contents($stream, $maxResultBytes);
            fclose($stream);
            return $data === false ? false : $data;
        }
        $overlap = substr($search, -max(0, $markerLength - 1));
        $absoluteOffset += strlen($chunk);
    }
    fclose($stream);
    return false;
}

function generate_sai_thumb($src, $dest) {
    $img = extract_sai_full($src);
    if ($img) {
        $ow = imagesx($img); $oh = imagesy($img);
        $ratio = min(THUMB_MAX_W / $ow, THUMB_MAX_H / $oh, 1);
        $nw = max(1, round($ow * $ratio));
        $nh = max(1, round($oh * $ratio));
        $thumb = scale_image_for_jpeg($img, $nw, $nh, IMG_BILINEAR_FIXED);
        if (!$thumb) {
            imagedestroy($img);
            return false;
        }
        $ok = imagejpeg($thumb, $dest, THUMB_QUALITY);
        imagedestroy($img);
        imagedestroy($thumb);
        return $ok;
    }
    return false;
}

function load_image_downscaled($src, $ext, $targetW, $targetH) {
    $info = @getimagesize($src);
    if (!$info) return null;
    list($ow, $oh) = $info;
    if (!ensure_memory_for_image($ow, $oh)) return null;
    return ($ext === 'jpg' || $ext === 'jpeg') ? @imagecreatefromjpeg($src) : null;
}

function scale_image_for_jpeg($image, $width, $height, $interpolation) {
    $scaled = @imagescale($image, $width, $height, $interpolation);
    if (!$scaled) return false;
    $canvas = imagecreatetruecolor($width, $height);
    if (!$canvas) {
        imagedestroy($scaled);
        return false;
    }
    $white = imagecolorallocate($canvas, 255, 255, 255);
    imagefill($canvas, 0, 0, $white);
    imagealphablending($canvas, true);
    imagecopy($canvas, $scaled, 0, 0, 0, 0, $width, $height);
    imagedestroy($scaled);
    return $canvas;
}

function generate_image_thumb($src, $ext, $dest) {
    if (!extension_loaded('gd')) return false;

    // Get dimensions without loading full image
    $info = @getimagesize($src);
    if (!$info) return false;
    list($ow, $oh) = $info;

    // Ensure enough memory for source + destination
    if (!ensure_memory_for_image($ow, $oh)) return false;

    $img = null;
    switch ($ext) {
        case 'jpg': case 'jpeg':
            $img = load_image_downscaled($src, $ext, THUMB_MAX_W, THUMB_MAX_H);
            if (!$img) $img = @imagecreatefromjpeg($src);
            break;
        case 'png':              $img = @imagecreatefrompng($src);  break;
        case 'gif':              $img = @imagecreatefromgif($src);  break;
        case 'bmp':              $img = @imagecreatefrombmp($src);  break;
        case 'webp':             $img = @imagecreatefromwebp($src); break;
        case 'avif':
            if (function_exists('imagecreatefromavif')) $img = @imagecreatefromavif($src);
            break;
        case 'psd': case 'psb':
            return generate_psd_thumb($src, $dest);
        case 'sai': case 'sai2':
            return generate_sai_thumb($src, $dest);
    }
    if (!$img) return false;

    $ow = imagesx($img);
    $oh = imagesy($img);
    $ratio = min(THUMB_MAX_W / $ow, THUMB_MAX_H / $oh, 1);
    $nw = max(1, round($ow * $ratio));
    $nh = max(1, round($oh * $ratio));

    $thumb = scale_image_for_jpeg($img, $nw, $nh, IMG_BILINEAR_FIXED);
    if (!$thumb) {
        imagedestroy($img);
        return false;
    }
    $ok = imagejpeg($thumb, $dest, THUMB_QUALITY);
    imagedestroy($img);
    imagedestroy($thumb);
    return $ok;
}

function generate_hq_preview($src, $ext, $dest) {
    if (!extension_loaded('gd')) return false;

    $info = @getimagesize($src);
    if (!$info) return false;
    list($ow, $oh) = $info;

    if (($ext === 'jpg' || $ext === 'jpeg') && $ow <= HQ_MAX_W && $oh <= HQ_MAX_H) {
        return copy($src, $dest);
    }

    if (!ensure_memory_for_image($ow, $oh)) return false;

    $img = null;
    switch ($ext) {
        case 'jpg': case 'jpeg':
            $img = load_image_downscaled($src, $ext, HQ_MAX_W, HQ_MAX_H);
            if (!$img) $img = @imagecreatefromjpeg($src);
            break;
        case 'png':              $img = @imagecreatefrompng($src);  break;
        case 'gif':              $img = @imagecreatefromgif($src);  break;
        case 'bmp':              $img = @imagecreatefrombmp($src);  break;
        case 'webp':             $img = @imagecreatefromwebp($src); break;
        case 'avif':
            if (function_exists('imagecreatefromavif')) $img = @imagecreatefromavif($src);
            break;
        case 'psd': case 'psb':
            $img = generate_psd_thumb($src, $dest);
            if ($img) return $img;
            return false;
        case 'sai': case 'sai2':
            $img = generate_sai_thumb($src, $dest);
            if ($img) return $img;
            return false;
    }
    if (!$img) return false;

    $ow = imagesx($img);
    $oh = imagesy($img);
    $ratio = min(HQ_MAX_W / $ow, HQ_MAX_H / $oh, 1);
    $nw = max(1, round($ow * $ratio));
    $nh = max(1, round($oh * $ratio));

    $preview = scale_image_for_jpeg($img, $nw, $nh, IMG_BICUBIC_FIXED);
    if (!$preview) {
        imagedestroy($img);
        return false;
    }
    $ok = imagejpeg($preview, $dest, 90);
    imagedestroy($img);
    imagedestroy($preview);
    return $ok;
}

function load_collage_image($src, $e, $maxDim) {
    $info = @getimagesize($src);
    if (!$info) return null;
    list($ow, $oh) = $info;

    if (!ensure_memory_for_image($ow, $oh)) return null;

    switch ($e) {
        case 'jpg': case 'jpeg': return @imagecreatefromjpeg($src);
        case 'png':              return @imagecreatefrompng($src);
        case 'gif':              return @imagecreatefromgif($src);
        case 'bmp':              return @imagecreatefrombmp($src);
        case 'webp':             return @imagecreatefromwebp($src);
        case 'avif':
            return function_exists('imagecreatefromavif') ? @imagecreatefromavif($src) : null;
    }
    return null;
}

function find_folder_thumb_images($dir) {
    $imageExts = ['jpg','jpeg','png','gif','bmp','webp','avif'];
    $images = [];
    $items = @scandir($dir);
    if (!$items) return $images;
    foreach ($items as $item) {
        if ($item === '.' || $item === '..') continue;
        $path = $dir . DIRECTORY_SEPARATOR . $item;
        if (is_file($path) && in_array(strtolower(pathinfo($item, PATHINFO_EXTENSION)), $imageExts, true)) {
            $images[] = $path;
            if (count($images) >= 4) break;
        }
    }
    return $images;
}

function generate_folder_thumb($dir, $dest, $images = null) {
    if (!extension_loaded('gd')) return false;
    $images = $images ?: find_folder_thumb_images($dir);
    if (empty($images)) return false;

    $cols = min(2, count($images));
    $rows = min(2, (int)ceil(count($images) / $cols));
    $gw = THUMB_MAX_W;
    $gh = THUMB_MAX_H;

    $canvas = imagecreatetruecolor($gw, $gh);
    $bg = imagecolorallocate($canvas, 240, 240, 240);
    imagefill($canvas, 0, 0, $bg);

    $cw = $gw / $cols;
    $ch = $gh / $rows;

    foreach ($images as $i => $src) {
        $e = strtolower(pathinfo($src, PATHINFO_EXTENSION));
        $img = load_collage_image($src, $e, (int)max($cw, $ch));
        if (!$img) continue;

        $ow = imagesx($img);
        $oh = imagesy($img);
        $ratio = max($cw / $ow, $ch / $oh);
        $tw = (int)round($ow * $ratio);
        $th = (int)round($oh * $ratio);

        $thumb = scale_image_for_jpeg($img, $tw, $th, IMG_BILINEAR_FIXED);
        if (!$thumb) {
            imagedestroy($img);
            continue;
        }

        $col = $i % $cols;
        $row = (int)($i / $cols);
        $dx = (int)($col * $cw + ($cw - $tw) / 2);
        $dy = (int)($row * $ch + ($ch - $th) / 2);

        imagecopy($canvas, $thumb, $dx, $dy, 0, 0, $tw, $th);
        imagedestroy($img);
        imagedestroy($thumb);
    }

    $ok = imagejpeg($canvas, $dest, 70);
    imagedestroy($canvas);
    return $ok;
}

function generate_video_thumb($src, $dest) {
    return false;
}

function serve_image($path, $key = null) {
    while (ob_get_level()) ob_end_clean();

    $mime = known_mime_type(pathinfo($path, PATHINFO_EXTENSION)) ?: 'image/jpeg';
    $etag = '"' . ($key ?: md5_file($path)) . '"';
    $versioned = isset($_GET['v']) && $_GET['v'] !== '';
    header('Content-Type: ' . $mime);
    header('Cache-Control: ' . ($versioned ? 'private, max-age=31536000, immutable' : 'private, no-cache'));
    header('Vary: Authorization');
    header('ETag: ' . $etag);
    header('X-Content-Type-Options: nosniff');

    if (isset($_SERVER['HTTP_IF_NONE_MATCH'])) {
        $clientEtag = trim($_SERVER['HTTP_IF_NONE_MATCH']);
        if ($clientEtag === $etag || $clientEtag === 'W/' . $etag) {
            http_response_code(304);
            exit;
        }
    }

    header('Content-Length: ' . filesize($path));

    readfile($path);
    exit;
}
