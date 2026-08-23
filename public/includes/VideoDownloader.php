<?php

class VideoDownloader {
    
    public static function getDownloadUrl($url) {
        if (strpos($url, 'youtube.com') !== false || strpos($url, 'youtu.be') !== false) {
            return self::getYouTubeUrl($url);
        } elseif (strpos($url, 'tiktok.com') !== false) {
            return self::getTikTokUrl($url);
        }
        
        throw new Exception("Unsupported platform for direct pure-PHP download.");
    }
    
    private static function getYouTubeUrl($url) {
        preg_match('/v=([a-zA-Z0-9_-]+)/', $url, $matches);
        if (!$matches) {
            preg_match('/youtu\.be\/([a-zA-Z0-9_-]+)/', $url, $matches);
        }
        if (!$matches) throw new Exception("Invalid YouTube URL");
        
        $vid = $matches[1];
        
        $opts = [
            'http' => [
                'method' => 'GET',
                'header' => "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\r\n" .
                            "Accept-Language: en-US,en;q=0.9\r\n"
            ]
        ];
        $context = stream_context_create($opts);
        $html = file_get_contents('https://www.youtube.com/watch?v=' . $vid, false, $context);
        
        if (!$html) throw new Exception("Failed to fetch YouTube page");
        
        $start = strpos($html, 'ytInitialPlayerResponse = {');
        if ($start !== false) {
            $start += strlen('ytInitialPlayerResponse = ');
            $end = strpos($html, '};', $start) + 1;
            $json = substr($html, $start, $end - $start);
            $playerResponse = json_decode($json, true);
        } else {
            // Try another format
            preg_match('/ytInitialPlayerResponse\s*=\s*(\{.+?\});/s', $html, $matches);
            if (!empty($matches[1])) {
                $playerResponse = json_decode($matches[1], true);
            }
        }
        
        if (empty($playerResponse['streamingData']['formats'])) {
            throw new Exception("No streaming data found or video is protected/live.");
        }
        
        $formats = $playerResponse['streamingData']['formats'];
        usort($formats, function($a, $b) {
            return ($b['height'] ?? 0) - ($a['height'] ?? 0);
        });
        
        $bestFormat = $formats[0];
        
        if (isset($bestFormat['url'])) {
            return [
                'url' => $bestFormat['url'],
                'title' => $playerResponse['videoDetails']['title'] ?? 'youtube_video',
                'ext' => 'mp4'
            ];
        }
        
        // Requires deciphering
        if (isset($bestFormat['signatureCipher'])) {
            parse_str($bestFormat['signatureCipher'], $cipherParams);
            $s = $cipherParams['s'] ?? '';
            $sp = $cipherParams['sp'] ?? 'sig';
            $streamUrl = urldecode($cipherParams['url'] ?? '');
            
            if (!$s || !$streamUrl) throw new Exception("Invalid cipher data");
            
            // Get player JS
            if (!preg_match('/"jsUrl":"([^"]+)"/', $html, $matches)) {
                preg_match('/src="([^"]*?\/base\.js)"/', $html, $matches);
            }
            if (empty($matches[1])) throw new Exception("Could not find player JS");
            
            $playerUrl = 'https://www.youtube.com' . str_replace('\/', '/', $matches[1]);
            $js = file_get_contents($playerUrl, false, $context);
            
            $decryptedSig = self::decipherSignature($s, $js);
            if (!$decryptedSig) throw new Exception("Failed to decipher signature");
            
            return [
                'url' => $streamUrl . '&' . $sp . '=' . $decryptedSig,
                'title' => $playerResponse['videoDetails']['title'] ?? 'youtube_video',
                'ext' => 'mp4'
            ];
        }
        
        throw new Exception("Format not supported");
    }
    
    private static function decipherSignature($signature, $js) {
        $funcName = null;
        if (preg_match('@,\s*encodeURIComponent\((\w{2})@is', $js, $matches)) {
            $funcName = preg_quote($matches[1]);
        } else if (preg_match('@(?:\b|[^a-zA-Z0-9$])([a-zA-Z0-9$]{2,3})\s*=\s*function\(\s*a\s*\)\s*{\s*a\s*=\s*a\.split\(\s*""\s*\)@is', $js, $matches)) {
            $funcName = preg_quote($matches[1]);
        }
        
        // Alternative method for func name if above fails
        if (!$funcName) {
            // Find a=a.split("") and extract the function name manually
            if (preg_match('/([a-zA-Z0-9$]+)=function\([a-zA-Z0-9$]+\)\{[a-zA-Z0-9$]+=[a-zA-Z0-9$]+\.split\(""\);[^}]+?\.join\(""\)\}/', $js, $matches)) {
                $funcName = preg_quote($matches[1]);
            }
        }
        
        if (!$funcName) return null;
        
        if (preg_match('/' . $funcName . '=function\([a-zA-Z0-9$]+\)\{(.*?)\}/s', $js, $matches)) {
            $funcBody = $matches[1];
            
            if (preg_match_all('/([a-zA-Z0-9$]{2})\.([a-zA-Z0-9$]{2})\([^,]+,(\d+)\)/i', $funcBody, $matchesAll)) {
                $objName = $matchesAll[1][0];
                $funcList = array_unique($matchesAll[2]);
                
                preg_match_all('/(' . implode('|', $funcList) . '):function(.*?)\}/m', $js, $objMatches, PREG_SET_ORDER);
                
                $functions = [];
                foreach ($objMatches as $m) {
                    if (strpos($m[2], 'splice') !== false) {
                        $functions[$m[1]] = 'splice';
                    } elseif (strpos($m[2], 'a.length') !== false) {
                        $functions[$m[1]] = 'swap';
                    } elseif (strpos($m[2], 'reverse') !== false) {
                        $functions[$m[1]] = 'reverse';
                    }
                }
                
                $sigArr = str_split($signature);
                foreach ($matchesAll[2] as $index => $name) {
                    $val = (int)$matchesAll[3][$index];
                    $action = $functions[$name] ?? '';
                    
                    if ($action == 'swap') {
                        $temp = $sigArr[0];
                        $sigArr[0] = $sigArr[$val % count($sigArr)];
                        $sigArr[$val % count($sigArr)] = $temp;
                    } elseif ($action == 'splice') {
                        array_splice($sigArr, 0, $val);
                    } elseif ($action == 'reverse') {
                        $sigArr = array_reverse($sigArr);
                    }
                }
                
                return implode('', $sigArr);
            }
        }
        
        return null;
    }

    private static function getTikTokUrl($url) {
        $opts = [
            'http' => [
                'method' => 'GET',
                'header' => "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36\r\n"
            ]
        ];
        $context = stream_context_create($opts);
        $html = @file_get_contents($url, false, $context);
        
        if (!$html) throw new Exception("Failed to fetch TikTok page");
        
        if (preg_match('/<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)<\/script>/s', $html, $matches)) {
            $data = json_decode($matches[1], true);
            
            // Try to navigate TikTok's crazy deep JSON structure
            $videoData = $data['__DEFAULT_SCOPE__']['webapp.video-detail']['itemInfo']['itemStruct'] ?? null;
            if (!$videoData) {
                // Alternative path for different TikTok page structures
                foreach ($data as $key => $val) {
                    if (isset($val['itemInfo']['itemStruct'])) {
                        $videoData = $val['itemInfo']['itemStruct'];
                        break;
                    }
                }
            }
            
            if ($videoData && isset($videoData['video']['playAddr'])) {
                return [
                    'url' => $videoData['video']['playAddr'],
                    'title' => $videoData['desc'] ?? 'tiktok_video',
                    'ext' => 'mp4'
                ];
            }
        }
        
        throw new Exception("Could not extract TikTok video URL");
    }
}
