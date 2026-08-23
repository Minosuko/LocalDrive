<?php
/**
 * CloudDrive API — Image Converter
 * Converts images between formats. Supports PSD, PSB, SAI, SAI2.
 */
require_once __DIR__ . '/../_init.php';
require_once BASE_DIR . '/includes/thumbnail.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    error_response('Method not allowed', 405);
}

$data = json_decode(file_get_contents('php://input'), true);
$path = $data['path'] ?? '';
$format = strtolower($data['format'] ?? 'png');
$quality = max(1, min(100, intval($data['quality'] ?? 85)));

if (!$path) error_response('No path provided', 400);

$real = get_real_path($path);
if (!file_exists($real) || is_dir($real)) error_response('File not found', 404);

$ext = strtolower(pathinfo($real, PATHINFO_EXTENSION));
$validFormats = ['png', 'jpg', 'jpeg', 'webp', 'bmp', 'gif', 'avif', 'ico'];
if (!in_array($format, $validFormats)) error_response('Invalid target format', 400);

if (!extension_loaded('gd')) error_response('GD extension not available', 500);

// Load source image
$img = null;
switch ($ext) {
    case 'jpg': case 'jpeg': $img = @imagecreatefromjpeg($real); break;
    case 'png':              $img = @imagecreatefrompng($real);  break;
    case 'gif':              $img = @imagecreatefromgif($real);  break;
    case 'bmp':              $img = @imagecreatefrombmp($real);  break;
    case 'webp':             $img = @imagecreatefromwebp($real); break;
    case 'avif':
        if (function_exists('imagecreatefromavif')) $img = @imagecreatefromavif($real);
        break;
    case 'ico':              $img = @imagecreatefromstring(file_get_contents($real)); break;
    case 'psd': case 'psb':
        $img = extract_psd_full($real);
        break;
    case 'sai': case 'sai2':
        $img = extract_sai_full($real);
        break;
}

if (!$img) error_response('Failed to load source image', 500);

// Build output path
$dir = dirname($real);
$baseName = pathinfo($real, PATHINFO_FILENAME);
$outExt = ($format === 'jpeg') ? 'jpg' : $format;
$outPath = $dir . DIRECTORY_SEPARATOR . $baseName . '.' . $outExt;

// Deduplicate
if (file_exists($outPath)) {
    $c = 1;
    while (file_exists($dir . DIRECTORY_SEPARATOR . $baseName . " ($c)." . $outExt)) $c++;
    $outPath = $dir . DIRECTORY_SEPARATOR . $baseName . " ($c)." . $outExt;
}

// Handle transparency for formats that don't support it
if (in_array($format, ['jpg', 'jpeg', 'bmp', 'ico'])) {
    $w = imagesx($img); $h = imagesy($img);
    $flat = imagecreatetruecolor($w, $h);
    $white = imagecolorallocate($flat, 255, 255, 255);
    imagefill($flat, 0, 0, $white);
    imagecopy($flat, $img, 0, 0, 0, 0, $w, $h);
    imagedestroy($img);
    $img = $flat;
}

// Save in target format
$ok = false;
switch ($format) {
    case 'png':  $ok = imagepng($img, $outPath, 6); break;
    case 'jpg': case 'jpeg': $ok = imagejpeg($img, $outPath, $quality); break;
    case 'webp': $ok = imagewebp($img, $outPath, $quality); break;
    case 'bmp':  $ok = imagebmp($img, $outPath); break;
    case 'gif':  $ok = imagegif($img, $outPath); break;
    case 'avif':
        if (function_exists('imageavif')) $ok = imageavif($img, $outPath, $quality);
        break;
    case 'ico':  $ok = imagepng($img, $outPath); break; // ICO output as PNG
}
imagedestroy($img);

if (!$ok) error_response('Failed to save converted image', 500);

// Invalidate cache
invalidate_dir_cache($dir);
invalidate_tree_cache(); // Just to be safe

$outName = basename($outPath);
json_response([
    'success' => true,
    'file' => [
        'name'      => $outName,
        'type'      => 'file',
        'size'      => filesize($outPath),
        'modified'  => filemtime($outPath),
        'extension' => $outExt,
        'icon'      => get_file_type($outExt),
    ]
]);
