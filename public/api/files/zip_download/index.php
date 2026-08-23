<?php
/**
 * CloudDrive API — Download multiple files as ZIP
 */
require_once __DIR__ . '/../../_init.php';
require_once BASE_DIR . '/includes/files.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') error_response('Method not allowed', 405);

$contentType = $_SERVER["CONTENT_TYPE"] ?? '';
if (strpos($contentType, 'application/json') !== false) {
    $data = json_decode(file_get_contents('php://input'), true);
    $paths = $data['paths'] ?? [];
} else {
    $paths = $_POST['paths'] ?? [];
}

if (empty($paths) || !is_array($paths)) {
    error_response('No paths provided', 400);
}

// Streaming ZIP generator for massive performance & zero disk usage
class ZipStreamer {
    private $files = [];
    private $offset = 0;

    public function __construct($filename = 'CloudDrive_Download.zip') {
        if (ob_get_level()) ob_end_clean();
        header('Content-Type: application/zip');
        header('Content-Disposition: attachment; filename="' . $filename . '"');
        header('Cache-Control: no-cache, no-store, must-revalidate');
        header('Pragma: no-cache');
        header('Expires: 0');
    }

    public function addFile($realPath, $zipLocalPath) {
        $zipLocalPath = str_replace('\\', '/', $zipLocalPath);
        $nameLen = strlen($zipLocalPath);
        
        $mtime = @filemtime($realPath) ?: time();
        $d = getdate($mtime);
        $dosTime = (($d['year'] - 1980) << 25) | ($d['mon'] << 21) | ($d['mday'] << 16) |
                   ($d['hours'] << 11) | ($d['minutes'] << 5) | ($d['seconds'] >> 1);

        $gpbf = 0x0008 | 0x0800; // Data descriptor flag | UTF-8 flag
        $compMethod = 8; // DEFLATE
        
        $header = pack('VvvvvvVVVvv', 
            0x04034b50, 20, $gpbf, $compMethod, 
            $dosTime & 0xFFFF, ($dosTime >> 16) & 0xFFFF, 
            0, 0, 0, $nameLen, 0 
        );
        $header .= $zipLocalPath;
        
        echo $header;
        $this->offset += strlen($header);
        
        $ctx = hash_init('crc32b');
        $fp = @fopen($realPath, 'rb');
        $size = 0;
        $compSize = 0;
        $deflate = deflate_init(ZLIB_ENCODING_RAW, ['level' => 1]);
        if ($fp) {
            while (!feof($fp) && !connection_aborted()) {
                $chunk = fread($fp, 8192 * 4);
                if ($chunk === false || $chunk === '') break;
                hash_update($ctx, $chunk);
                $size += strlen($chunk);
                
                $compressed = deflate_add($deflate, $chunk, ZLIB_NO_FLUSH);
                echo $compressed;
                $compSize += strlen($compressed);
            }
            fclose($fp);
        }
        $compressed = deflate_add($deflate, '', ZLIB_FINISH);
        echo $compressed;
        $compSize += strlen($compressed);
        
        $this->offset += $compSize;
        
        $crc = hexdec(hash_final($ctx));
        
        $desc = pack('VVVV', 0x08074b50, $crc, $compSize, $size);
        echo $desc;
        $this->offset += strlen($desc);
        
        $this->files[] = [
            'name' => $zipLocalPath,
            'time' => $dosTime,
            'crc'  => $crc,
            'size' => $size,
            'comp_size' => $compSize,
            'comp_method' => $compMethod,
            'offset' => $this->offset - strlen($header) - $compSize - strlen($desc),
            'gpbf' => $gpbf
        ];
    }

    public function addDir($zipLocalPath) {
        $zipLocalPath = rtrim(str_replace('\\', '/', $zipLocalPath), '/') . '/';
        $nameLen = strlen($zipLocalPath);
        
        $mtime = time();
        $d = getdate($mtime);
        $dosTime = (($d['year'] - 1980) << 25) | ($d['mon'] << 21) | ($d['mday'] << 16) |
                   ($d['hours'] << 11) | ($d['minutes'] << 5) | ($d['seconds'] >> 1);
        $gpbf = 0x0800; // UTF-8 flag
                   
        $header = pack('VvvvvvVVVvv', 
            0x04034b50, 20, $gpbf, 0, 
            $dosTime & 0xFFFF, ($dosTime >> 16) & 0xFFFF, 
            0, 0, 0, $nameLen, 0
        );
        $header .= $zipLocalPath;
        echo $header;
        $this->offset += strlen($header);
        
        $this->files[] = [
            'name' => $zipLocalPath,
            'time' => $dosTime,
            'crc'  => 0,
            'size' => 0,
            'comp_size' => 0,
            'comp_method' => 0,
            'offset' => $this->offset - strlen($header),
            'gpbf' => $gpbf
        ];
    }

    public function finish() {
        $cdOffset = $this->offset;
        $cdSize = 0;
        
        foreach ($this->files as $f) {
            $name = $f['name'];
            $extAttr = (substr($name, -1) === '/') ? 0x10 : 0x20;
            
            $cd = pack('VvvvvvvVVVvvvvvVV',
                0x02014b50, 20, 20, 
                $f['gpbf'], $f['comp_method'], 
                $f['time'] & 0xFFFF, ($f['time'] >> 16) & 0xFFFF,
                $f['crc'], $f['comp_size'], $f['size'],
                strlen($name), 0, 0, 0, 0,
                $extAttr, $f['offset']
            );
            $cd .= $name;
            echo $cd;
            $cdSize += strlen($cd);
        }
        
        $eocd = pack('VvvvvVVv',
            0x06054b50, 0, 0, 
            count($this->files), count($this->files),
            $cdSize, $cdOffset, 0
        );
        echo $eocd;
    }
}

$streamer = new ZipStreamer();

function stream_add_to_zip($streamer, $realPath, $zipLocalPath) {
    if (is_dir($realPath)) {
        $streamer->addDir($zipLocalPath);
        $files = array_diff(scandir($realPath), ['.', '..']);
        foreach ($files as $file) {
            stream_add_to_zip($streamer, $realPath . DIRECTORY_SEPARATOR . $file, $zipLocalPath . '/' . $file);
        }
    } else {
        $streamer->addFile($realPath, $zipLocalPath);
    }
}

foreach ($paths as $path) {
    $real = get_real_path($path);
    if (file_exists($real)) {
        $baseName = basename($real);
        stream_add_to_zip($streamer, $real, $baseName);
    }
}

$streamer->finish();
exit;
