<?php
/**
 * CloudDrive API — Search Files
 * Recursively searches for files matching a query.
 * Supports:
 *   - Multi-word AND search (space separated)
 *   - type:xxx filter (image, video, audio, document, text, code, archive, folder)
 *   - Glob/wildcard patterns (* ?)
 *   - Relevance ranking: exact match > prefix > substring > path match
 */
require_once __DIR__ . '/../../_init.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    error_response('Method not allowed', 405);
}

$q = $_GET['q'] ?? '';
if (!$q) error_response('Query required', 400);

$realPath = get_real_path('/');
if (!is_dir($realPath)) {
    json_response(['files' => []]);
}

// Parse query parts and type filters
$typeFilter = null;
$terms = [];
$isGlob = false;

$parts = preg_split('/\s+/', $q);
foreach ($parts as $part) {
    if (preg_match('/^type:(.+)$/i', $part, $m)) {
        $typeFilter = strtolower($m[1]);
    } else {
        $terms[] = $part;
        if (strpbrk($part, '*?') !== false) $isGlob = true;
    }
}

$qLower = strtolower(implode(' ', $terms));
$multiTerms = count($terms) > 1 ? array_map('strtolower', $terms) : null;
$globRegexes = [];
if ($isGlob) {
    foreach ($terms as $term) {
        $globRegexes[] = '/^' . str_replace(['\\*', '\\?'], ['.*', '.'], preg_quote($term, '/')) . '$/i';
    }
}

$results = [];
$seen = [];

try {
    $directory = new RecursiveDirectoryIterator($realPath, RecursiveDirectoryIterator::SKIP_DOTS);
    $filtered = new RecursiveCallbackFilterIterator($directory, static function ($current) {
        if (is_internal_storage_name($current->getFilename())) {
            cleanup_stale_internal_entry($current->getPathname());
            return false;
        }
        return true;
    });
    $iter = new RecursiveIteratorIterator(
        $filtered,
        RecursiveIteratorIterator::SELF_FIRST
    );

    $count = 0;
    $maxResults = 200;

    foreach ($iter as $file) {
        if ($count >= $maxResults) break;

        $filename = $file->getFilename();
        $isDir = $file->isDir();
        $ext = $isDir ? '' : strtolower($file->getExtension());

        // Type filter
        if ($typeFilter) {
            if ($typeFilter === 'folder' && !$isDir) continue;
            if ($typeFilter !== 'folder' && $isDir) continue;
            if ($typeFilter !== 'folder' && get_file_type($ext) !== $typeFilter) continue;
        }

        $nameLower = strtolower($filename);
        $matched = false;

        if ($isGlob) {
            foreach ($globRegexes as $regex) {
                if (preg_match($regex, $filename)) { $matched = true; break; }
            }
        } elseif ($multiTerms) {
            // Multi-word AND matching
            $matched = true;
            foreach ($multiTerms as $term) {
                if (strpos($nameLower, $term) === false) {
                    $matched = false;
                    break;
                }
            }
        } else {
            // Single term substring match
            $matched = strpos($nameLower, $qLower) !== false;
        }

        if (!$matched) continue;

        $rp = $file->getRealPath();

        // Generate relative path
        $rel = substr($rp, strlen($realPath));
        $rel = str_replace('\\', '/', $rel);
        if ($rel === '' || $rel[0] !== '/') $rel = '/' . ltrim($rel, '/');

        // Deduplicate by path
        if (isset($seen[$rel])) continue;
        $seen[$rel] = true;

        $entry = [
            'name' => $filename,
            'path' => $rel,
            'type' => $isDir ? 'folder' : 'file',
            'size' => $isDir ? 0 : $file->getSize(),
            'modified' => $file->getMTime(),
            'extension' => $ext,
            'icon' => $isDir ? 'folder' : get_file_type($ext),
        ];

        // Calculate relevance score
        $score = 0;
        if (!$isGlob && !$multiTerms) {
            if (strcasecmp($filename, $q) === 0) $score = 100; // exact match
            elseif (stripos($filename, $q) === 0) $score = 80; // prefix match
            elseif (stripos($filename, $q) !== false) $score = 60; // substring
            elseif (stripos($rel, '/' . $q) !== false) $score = 40; // path segment
            elseif (stripos($rel, $q) !== false) $score = 20; // path contains
        }
        $entry['_score'] = $score;

        $results[] = $entry;
        $count++;
    }
} catch (Exception $e) {
    // Ignore permissions/read errors inside iterator
}

// Sort: folders first, then by relevance score desc, then natural name
usort($results, function($a, $b) {
    if ($a['type'] !== $b['type']) return $a['type'] === 'folder' ? -1 : 1;
    $scoreDiff = ($b['_score'] ?? 0) - ($a['_score'] ?? 0);
    if ($scoreDiff !== 0) return $scoreDiff;
    return strnatcasecmp($a['name'], $b['name']);
});

// Strip internal score from response
$output = array_map(function($item) {
    unset($item['_score']);
    return $item;
}, $results);

json_response(['success' => true, 'data' => ['files' => $output]]);
