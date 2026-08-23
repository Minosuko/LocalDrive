<?php
/**
 * CloudDrive standalone HTTP and WebDAV server.
 *
 * Usage: php router.php
 * Web UI: http://localhost:8080/
 * Drive:  http://localhost:8080/drive/
 *
 * Server settings are read from config.json. Environment variables override them.
 */

if (PHP_SAPI === 'cli' && ($argv[1] ?? '') === '--issue-local-tls') {
    router_issue_local_tls($argv[2] ?? '');
    exit;
}

if (PHP_SAPI === 'cgi-fcgi' || PHP_SAPI === 'cli-server') {
    router_dispatch_request();
    exit;
}

if (PHP_SAPI !== 'cli') {
    http_response_code(500);
    echo 'Run this server with: php router.php';
    exit(1);
}

if (($argv[1] ?? '') === '--connection-worker') {
    router_run_connection_worker($argv[2] ?? '', $argv[3] ?? '0.0.0.0', (int)($argv[4] ?? 8080));
    exit;
}

if (($argv[1] ?? '') === '--ssdp-worker') {
    router_run_ssdp_worker($argv[2] ?? '', $argv[3] ?? '0.0.0.0', (int)($argv[4] ?? 8080), (int)($argv[5] ?? 0));
    exit;
}

if (($argv[1] ?? '') === '--http-worker') {
    router_run_http_worker($argv[2] ?? '', $argv[3] ?? '0.0.0.0', (int)($argv[4] ?? 8080), (int)($argv[5] ?? 0));
    exit;
}

router_run_server();

function router_run_server()
{
    $config = router_load_config();
    $environmentHost = getenv('CLOUDDRIVE_HOST');
    $environmentPort = getenv('CLOUDDRIVE_PORT');
    $environmentHttpsPort = getenv('CLOUDDRIVE_HTTPS_PORT');
    $host = $environmentHost !== false && $environmentHost !== '' ? $environmentHost : (string)($config['host'] ?? '0.0.0.0');
    $port = (int)($environmentPort !== false && $environmentPort !== '' ? $environmentPort : ($config['port'] ?? 8080));
    $httpsEnabled = !empty($config['https_enabled']);
    $httpsPort = (int)($environmentHttpsPort !== false && $environmentHttpsPort !== ''
        ? $environmentHttpsPort
        : ($config['https_port'] ?? 8443));
    $maxConnections = max(1, min(128, (int)($config['max_connections'] ?? 32)));
    $workerCount = max(1, min(32, (int)($config['workers'] ?? 4)));
    if ($host === '' || preg_match('/[\s\/:]/', $host)) {
        fwrite(STDERR, "Invalid host in config.json: $host\n");
        exit(1);
    }
    if ($port < 1 || $port > 65535) {
        fwrite(STDERR, "Invalid CLOUDDRIVE_PORT: $port\n");
        exit(1);
    }
    if ($httpsEnabled && ($httpsPort < 1 || $httpsPort > 65535 || $httpsPort === $port)) {
        fwrite(STDERR, "Invalid HTTPS port in config.json: $httpsPort\n");
        exit(1);
    }

    $cgiBinary = router_find_cgi_binary();
    if ($cgiBinary === null) {
        fwrite(STDERR, "php-cgi is required. Install it or set PHP_CGI_BINARY.\n");
        exit(1);
    }

    $errorNumber = 0;
    $errorMessage = '';
    $httpServer = @stream_socket_server("tcp://$host:$port", $errorNumber, $errorMessage);
    if ($httpServer === false) {
        fwrite(STDERR, "Could not listen on $host:$port: $errorMessage ($errorNumber)\n");
        exit(1);
    }
    $listeners = [['stream' => $httpServer, 'scheme' => 'http', 'port' => $port, 'tls' => false, 'crypto_method' => null]];

    if ($httpsEnabled) {
        $certificate = router_ensure_tls_certificate($config);
        if ($certificate === null) {
            fwrite(STDERR, "Could not create the HTTPS certificate; HTTP remains available.\n");
        } else {
            $cryptoMethod = STREAM_CRYPTO_METHOD_TLSv1_2_SERVER;
            if (defined('STREAM_CRYPTO_METHOD_TLSv1_3_SERVER')) {
                $cryptoMethod |= constant('STREAM_CRYPTO_METHOD_TLSv1_3_SERVER');
            }
            $tlsContext = stream_context_create(['ssl' => [
                'local_cert' => $certificate['local_certificate'] ?? $certificate['certificate'],
                'local_pk' => $certificate['private_key'],
                'verify_peer' => false,
                'allow_self_signed' => true,
                'disable_compression' => true,
                'honor_cipher_order' => true,
                'crypto_method' => $cryptoMethod,
            ]]);
            $tlsNumber = 0;
            $tlsMessage = '';
            $httpsServer = @stream_socket_server(
                "tcp://$host:$httpsPort",
                $tlsNumber,
                $tlsMessage,
                STREAM_SERVER_BIND | STREAM_SERVER_LISTEN,
                $tlsContext
            );
            if ($httpsServer === false) {
                fwrite(STDERR, "Could not listen on HTTPS $host:$httpsPort: $tlsMessage ($tlsNumber); HTTP remains available.\n");
            } else {
                $listeners[] = [
                    'stream' => $httpsServer,
                    'scheme' => 'https',
                    'port' => $httpsPort,
                    'tls' => true,
                    'crypto_method' => $cryptoMethod,
                ];
            }
        }
    }

    echo "CloudDrive running at http://localhost:$port/\n";
    if (count($listeners) > 1) echo "CloudDrive TLS at https://localhost:$httpsPort/\n";
    echo "Root account: http://localhost:$port/account.html\n";
    echo "Network drive: http://localhost:$port/network-drive/ (use root credentials)\n";
    if (count($listeners) > 1) echo "Secure network drive: https://localhost:$httpsPort/network-drive/\n";
    if ($host === '0.0.0.0') {
        echo "LAN access: http://<this-computer-ip>:$port/\n";
    }
    echo "Press Ctrl+C to stop.\n";

    $httpWorkers = router_start_http_worker_pool($workerCount, $host, $port);
    if (count($httpWorkers) !== $workerCount) {
        fwrite(STDERR, "Could not start the HTTP worker pool.\n");
        foreach ($listeners as $listener) fclose($listener['stream']);
        exit(1);
    }
    echo "HTTP workers: $workerCount, connection limit: $maxConnections.\n";

    $ssdpWorker = null;
    if (!empty($config['media_device'])) {
        $ssdpWorker = router_start_ssdp_worker($host, $port);
        echo $ssdpWorker === null
            ? "Media-device discovery unavailable (UDP port 1900 may be in use).\n"
            : "Media device discovery enabled as " . ($config['friendly_name'] ?? 'CloudDrive') . ".\n";
    }

    foreach ($listeners as $listener) stream_set_blocking($listener['stream'], false);
    router_run_connection_loop($listeners, $maxConnections, $httpWorkers);

    foreach ($listeners as $listener) fclose($listener['stream']);
}

function router_start_http_worker_pool($count, $host, $port)
{
    $workers = [];
    $pending = [];
    $null = DIRECTORY_SEPARATOR === '\\' ? 'NUL' : '/dev/null';
    $descriptors = [
        0 => ['file', $null, 'r'],
        1 => ['file', $null, 'a'],
        2 => ['file', $null, 'a'],
    ];

    for ($index = 0; $index < $count; $index++) {
        $controlServer = @stream_socket_server('tcp://127.0.0.1:0', $errorNumber, $errorMessage);
        if ($controlServer === false) {
            break;
        }
        $controlAddress = stream_socket_get_name($controlServer, false);
        $process = @proc_open(
            [PHP_BINARY, __FILE__, '--http-worker', $controlAddress, $host, (string)$port, (string)getmypid()],
            $descriptors,
            $pipes,
            __DIR__,
            null,
            ['bypass_shell' => true]
        );
        if (!is_resource($process)) {
            fclose($controlServer);
            break;
        }
        $pending[] = ['server' => $controlServer, 'process' => $process];
    }

    foreach ($pending as $worker) {
        $control = @stream_socket_accept($worker['server'], 5);
        fclose($worker['server']);
        if ($control === false) {
            proc_terminate($worker['process']);
            proc_close($worker['process']);
            continue;
        }
        stream_set_timeout($control, 5);
        $line = trim((string)fgets($control));
        if (strpos($line, 'READY ') !== 0) {
            fclose($control);
            proc_terminate($worker['process']);
            proc_close($worker['process']);
            continue;
        }
        stream_set_blocking($control, false);
        $workers[] = [
            'address' => substr($line, 6),
            'control' => $control,
            'process' => $worker['process'],
        ];
    }
    return $workers;
}

function router_run_http_worker($controlAddress, $host, $port, $parentPid)
{
    $control = @stream_socket_client('tcp://' . $controlAddress, $errorNumber, $errorMessage, 5);
    $server = @stream_socket_server('tcp://127.0.0.1:0', $serverError, $serverMessage);
    if ($control === false || $server === false) {
        if (is_resource($control)) {
            fwrite($control, "ERROR\n");
            fclose($control);
        }
        exit(1);
    }
    $cgiBinary = router_find_cgi_binary();
    if ($cgiBinary === null) {
        fwrite($control, "ERROR\n");
        fclose($control);
        fclose($server);
        exit(1);
    }
    $applicationBackend = router_start_application_backend();
    if ($applicationBackend !== null) {
        $GLOBALS['router_application_backend'] = $applicationBackend;
    }
    stream_set_blocking($control, false);
    fwrite($control, 'READY ' . stream_socket_get_name($server, false) . "\n");

    $lastParentCheck = 0;
    while (true) {
        if (time() - $lastParentCheck >= 3) {
            if (!router_process_is_running($parentPid)) {
                break;
            }
            $lastParentCheck = time();
        }
        @fread($control, 1);
        if (feof($control)) {
            break;
        }
        $read = [$control, $server];
        $write = $except = [];
        if (@stream_select($read, $write, $except, 1) <= 0) {
            continue;
        }
        if (in_array($control, $read, true)) {
            @fread($control, 8192);
            if (feof($control)) {
                break;
            }
        }
        if (in_array($server, $read, true)) {
            $client = @stream_socket_accept($server, 0);
            if ($client !== false) {
                stream_set_timeout($client, 300);
                router_optimize_stream($client);
                try {
                    router_handle_connection($client, $cgiBinary, $host, $port);
                } catch (Throwable $error) {
                    router_write_simple_response($client, 500, 'Internal Server Error');
                }
                fclose($client);
            }
        }
    }
    if (isset($GLOBALS['router_application_backend'])) {
        router_stop_application_backend($GLOBALS['router_application_backend']);
        unset($GLOBALS['router_application_backend']);
    }
    fclose($server);
    fclose($control);
}

function router_start_ssdp_worker($host, $port)
{
    if (!extension_loaded('sockets')) {
        return null;
    }
    $controlServer = @stream_socket_server('tcp://127.0.0.1:0', $errorNumber, $errorMessage);
    if ($controlServer === false) {
        return null;
    }
    $address = stream_socket_get_name($controlServer, false);
    $null = DIRECTORY_SEPARATOR === '\\' ? 'NUL' : '/dev/null';
    $descriptors = [
        0 => ['file', $null, 'r'],
        1 => ['file', $null, 'a'],
        2 => ['file', $null, 'a'],
    ];
    $process = @proc_open(
        [PHP_BINARY, __FILE__, '--ssdp-worker', $address, $host, (string)$port, (string)getmypid()],
        $descriptors,
        $pipes,
        __DIR__,
        null,
        ['bypass_shell' => true]
    );
    if (!is_resource($process)) {
        fclose($controlServer);
        return null;
    }
    $control = @stream_socket_accept($controlServer, 5);
    fclose($controlServer);
    if ($control === false) {
        proc_terminate($process);
        proc_close($process);
        return null;
    }
    stream_set_timeout($control, 5);
    $status = trim((string)fgets($control));
    stream_set_blocking($control, false);
    if ($status !== 'READY') {
        fclose($control);
        proc_terminate($process);
        proc_close($process);
        return null;
    }
    return ['process' => $process, 'control' => $control];
}

function router_run_ssdp_worker($controlAddress, $host, $port, $parentPid)
{
    $control = @stream_socket_client('tcp://' . $controlAddress, $errorNumber, $errorMessage, 5);
    if ($control === false) {
        exit(1);
    }
    $socket = @socket_create(AF_INET, SOCK_DGRAM, SOL_UDP);
    if ($socket === false) {
        fwrite($control, "ERROR\n");
        fclose($control);
        exit(1);
    }
    @socket_set_option($socket, SOL_SOCKET, SO_REUSEADDR, 1);
    if (!@socket_bind($socket, '0.0.0.0', 1900)) {
        fwrite($control, "ERROR\n");
        socket_close($socket);
        fclose($control);
        exit(1);
    }
    @socket_set_option($socket, IPPROTO_IP, MCAST_JOIN_GROUP, ['group' => '239.255.255.250', 'interface' => 0]);
    socket_set_nonblock($socket);
    stream_set_blocking($control, false);
    fwrite($control, "READY\n");

    $uuid = router_device_uuid();
    $lastAnnouncement = 0;
    $lastParentCheck = 0;
    while (true) {
        if (time() - $lastParentCheck >= 3) {
            if (!router_process_is_running($parentPid)) {
                router_ssdp_notify($socket, $host, $port, $uuid, 'ssdp:byebye');
                break;
            }
            $lastParentCheck = time();
        }
        @fread($control, 1);
        if (feof($control)) {
            router_ssdp_notify($socket, $host, $port, $uuid, 'ssdp:byebye');
            break;
        }
        if (time() - $lastAnnouncement >= 900) {
            router_ssdp_notify($socket, $host, $port, $uuid, 'ssdp:alive');
            $lastAnnouncement = time();
        }

        $read = [$socket];
        $write = $except = [];
        if (@socket_select($read, $write, $except, 1) > 0) {
            $request = '';
            $remoteAddress = '';
            $remotePort = 0;
            if (@socket_recvfrom($socket, $request, 65535, 0, $remoteAddress, $remotePort) !== false
                && stripos($request, 'M-SEARCH * HTTP/1.1') === 0
                && preg_match('/^ST:\s*(.+)$/mi', $request, $match)) {
                $localAddress = $host === '0.0.0.0' ? router_local_address_for($remoteAddress) : $host;
                router_ssdp_respond($socket, trim($match[1]), $remoteAddress, $remotePort, $localAddress, $port, $uuid);
            }
        }
    }
    socket_close($socket);
    fclose($control);
}

function router_process_is_running($pid)
{
    if ($pid <= 0) {
        return false;
    }
    if (DIRECTORY_SEPARATOR !== '\\' && function_exists('posix_kill')) {
        return @posix_kill($pid, 0);
    }
    if (DIRECTORY_SEPARATOR === '\\' && class_exists('FFI')) {
        try {
            static $kernel32 = null;
            if ($kernel32 === null) {
                $kernel32 = FFI::cdef(
                    'void* OpenProcess(unsigned long access, int inheritHandle, unsigned long processId); int CloseHandle(void* handle);',
                    'kernel32.dll'
                );
            }
            $handle = $kernel32->OpenProcess(0x1000, 0, $pid);
            if (FFI::isNull($handle)) {
                return false;
            }
            $kernel32->CloseHandle($handle);
            return true;
        } catch (Throwable $error) {
        }
    }
    if (DIRECTORY_SEPARATOR === '\\' && function_exists('exec')) {
        $output = [];
        $exitCode = 0;
        @exec('tasklist /FI "PID eq ' . (int)$pid . '" /NH', $output, $exitCode);
        return $exitCode === 0 && preg_match('/\b' . (int)$pid . '\b/', implode("\n", $output)) === 1;
    }
    return true;
}

function router_local_address_for($remoteAddress)
{
    $probe = @socket_create(AF_INET, SOCK_DGRAM, SOL_UDP);
    if ($probe !== false && @socket_connect($probe, $remoteAddress, 1900)) {
        $localAddress = '';
        if (@socket_getsockname($probe, $localAddress)) {
            socket_close($probe);
            return $localAddress;
        }
        socket_close($probe);
    }
    return '127.0.0.1';
}

function router_ssdp_targets($uuid)
{
    return [
        'upnp:rootdevice' => $uuid . '::upnp:rootdevice',
        $uuid => $uuid,
        'urn:schemas-upnp-org:device:MediaServer:1' => $uuid . '::urn:schemas-upnp-org:device:MediaServer:1',
        'urn:schemas-upnp-org:service:ContentDirectory:1' => $uuid . '::urn:schemas-upnp-org:service:ContentDirectory:1',
        'urn:schemas-upnp-org:service:ConnectionManager:1' => $uuid . '::urn:schemas-upnp-org:service:ConnectionManager:1',
    ];
}

function router_ssdp_respond($socket, $searchTarget, $remoteAddress, $remotePort, $localAddress, $port, $uuid)
{
    $targets = router_ssdp_targets($uuid);
    $responses = [];
    if (strcasecmp($searchTarget, 'ssdp:all') === 0) {
        $responses = $targets;
    } else {
        foreach ($targets as $target => $usn) {
            if (strcasecmp($searchTarget, $target) === 0) {
                $responses[$target] = $usn;
                break;
            }
        }
    }
    foreach ($responses as $target => $usn) {
        $response = "HTTP/1.1 200 OK\r\n"
            . "CACHE-CONTROL: max-age=1800\r\n"
            . 'DATE: ' . gmdate('D, d M Y H:i:s') . " GMT\r\n"
            . "EXT:\r\n"
            . "LOCATION: http://$localAddress:$port/upnp/device.xml\r\n"
            . "SERVER: PHP/" . PHP_VERSION . " UPnP/1.0 CloudDrive/1.0\r\n"
            . "ST: $target\r\n"
            . "USN: $usn\r\n\r\n";
        @socket_sendto($socket, $response, strlen($response), 0, $remoteAddress, $remotePort);
    }
}

function router_ssdp_notify($socket, $host, $port, $uuid, $subtype)
{
    if ($host === '0.0.0.0') {
        $host = gethostbyname(php_uname('n'));
        if ($host === php_uname('n') || $host === '127.0.0.1') {
            return;
        }
    }
    foreach (router_ssdp_targets($uuid) as $target => $usn) {
        $message = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
            . "NT: $target\r\nNTS: $subtype\r\nUSN: $usn\r\n";
        if ($subtype === 'ssdp:alive') {
            $message .= "CACHE-CONTROL: max-age=1800\r\nLOCATION: http://$host:$port/upnp/device.xml\r\n"
                . "SERVER: PHP/" . PHP_VERSION . " UPnP/1.0 CloudDrive/1.0\r\n";
        }
        $message .= "\r\n";
        @socket_sendto($socket, $message, strlen($message), 0, '239.255.255.250', 1900);
    }
}

function router_device_uuid()
{
    $hash = sha1(__DIR__ . '|' . php_uname('n'));
    return 'uuid:' . substr($hash, 0, 8) . '-' . substr($hash, 8, 4) . '-5' . substr($hash, 13, 3)
        . '-a' . substr($hash, 17, 3) . '-' . substr($hash, 20, 12);
}

function router_run_connection_worker($address, $host, $port)
{
    if ($address === '') {
        exit(1);
    }
    $connection = @stream_socket_client('tcp://' . $address, $errorNumber, $errorMessage, 5);
    if ($connection === false) {
        fwrite(STDERR, "Worker connection failed: $errorMessage ($errorNumber)\n");
        exit(1);
    }
    router_optimize_stream($connection);
    stream_set_timeout($connection, 300);
    $cgiBinary = router_find_cgi_binary();
    if ($cgiBinary === null) {
        router_write_simple_response($connection, 500, 'php-cgi is required');
    } else {
        router_handle_connection(
            $connection,
            $cgiBinary,
            $host,
            $port
        );
    }
    fclose($connection);
}

function router_run_connection_loop($listeners, $maxConnections, $httpWorkers)
{
    $connections = [];
    $nextId = 1;
    $nextWorker = 0;

    while (true) {
        $read = [];
        $write = [];
        $readMap = [];
        $writeMap = [];
        foreach ($listeners as $listenerId => $listener) {
            $read[] = $listener['stream'];
            $readMap[(int)$listener['stream']] = ['server', $listenerId];
        }

        foreach ($connections as $id => $connection) {
            if (is_resource($connection['listener'])) {
                $read[] = $connection['listener'];
                $readMap[(int)$connection['listener']] = ['listener', $id];
            }
            if (is_resource($connection['client']) && !$connection['client_eof']
                && is_resource($connection['worker']) && strlen($connection['to_worker']) < 32 * 1024 * 1024) {
                $read[] = $connection['client'];
                $readMap[(int)$connection['client']] = ['client', $id];
            }
            if (is_resource($connection['worker']) && !$connection['worker_eof']
                && strlen($connection['to_client']) < 32 * 1024 * 1024) {
                $read[] = $connection['worker'];
                $readMap[(int)$connection['worker']] = ['worker', $id];
            }
            if (is_resource($connection['worker']) && $connection['to_worker'] !== '') {
                $write[] = $connection['worker'];
                $writeMap[(int)$connection['worker']] = ['worker', $id];
            }
            if (is_resource($connection['client']) && $connection['to_client'] !== '') {
                $write[] = $connection['client'];
                $writeMap[(int)$connection['client']] = ['client', $id];
            }
        }

        $except = null;
        $selectTimeout = $connections ? 10000 : 100000;
        if (@stream_select($read, $write, $except, 0, $selectTimeout) === false) {
            continue;
        }

        foreach ($read as $stream) {
            [$type, $id] = $readMap[(int)$stream];
            if ($type === 'server') {
                $listener = $listeners[$id];
                while ($client = @stream_socket_accept($listener['stream'], 0)) {
                    if (!empty($listener['tls'])) {
                        stream_set_blocking($client, true);
                        stream_set_timeout($client, 3);
                        if (@stream_socket_enable_crypto($client, true, $listener['crypto_method']) !== true) {
                            fclose($client);
                            continue;
                        }
                    }
                    if (count($connections) >= $maxConnections) {
                        router_write_simple_response($client, 503, 'Too many connections');
                        fclose($client);
                        continue;
                    }
                    $workerAddress = $httpWorkers[$nextWorker++ % count($httpWorkers)]['address'];
                    $connection = router_connect_http_worker($client, $workerAddress, $listener['scheme'], $listener['port']);
                    if ($connection === null) {
                        router_write_simple_response($client, 500, 'Could not start connection worker');
                        fclose($client);
                        continue;
                    }
                    $connections[$nextId++] = $connection;
                }
                continue;
            }
            if (!isset($connections[$id])) {
                continue;
            }
            if ($type === 'listener') {
                $worker = @stream_socket_accept($connections[$id]['listener'], 0);
                if ($worker !== false) {
                    stream_set_blocking($worker, false);
                    router_optimize_stream($worker);
                    fclose($connections[$id]['listener']);
                    $connections[$id]['listener'] = null;
                    $connections[$id]['worker'] = $worker;
                }
                continue;
            }

            $chunk = @fread($stream, 4 * 1024 * 1024);
            if ($chunk === false || ($chunk === '' && feof($stream))) {
                $connections[$id][$type . '_eof'] = true;
            } elseif ($chunk !== '') {
                $buffer = $type === 'client' ? 'to_worker' : 'to_client';
                $connections[$id][$buffer] .= $chunk;
            }
        }

        foreach ($write as $stream) {
            [$type, $id] = $writeMap[(int)$stream];
            if (!isset($connections[$id])) {
                continue;
            }
            $buffer = $type === 'client' ? 'to_client' : 'to_worker';
            $written = @fwrite($stream, $connections[$id][$buffer]);
            if ($written === false) {
                $connections[$id][$type . '_eof'] = true;
            } elseif ($written > 0) {
                $connections[$id][$buffer] = (string)substr($connections[$id][$buffer], $written);
            }
        }

        foreach (array_keys($connections) as $id) {
            $connection = &$connections[$id];
            if ($connection['client_eof'] && $connection['to_worker'] === '' && is_resource($connection['worker'])) {
                @stream_socket_shutdown($connection['worker'], STREAM_SHUT_WR);
            }
            $workerTimedOut = is_resource($connection['listener']) && microtime(true) - $connection['started_at'] > 5;
            $responseComplete = $connection['worker_eof'] && $connection['to_client'] === '';
            if ($responseComplete && !$connection['response_shutdown'] && is_resource($connection['client'])) {
                if (empty($connection['tls'])) @stream_socket_shutdown($connection['client'], STREAM_SHUT_WR);
                $connection['response_shutdown'] = true;
                $connection['response_finished_at'] = microtime(true);
            }
            $finished = $workerTimedOut || ($responseComplete && $connection['response_shutdown']
                && ($connection['client_eof'] || microtime(true) - $connection['response_finished_at'] > 0.1));
            if ($finished) {
                router_close_proxied_connection($connection);
                unset($connections[$id]);
            }
            unset($connection);
        }
    }
}

function router_connect_http_worker($client, $address, $scheme = 'http', $port = 80)
{
    $worker = @stream_socket_client('tcp://' . $address, $errorNumber, $errorMessage, 2);
    if ($worker === false) {
        return null;
    }
    stream_set_blocking($client, false);
    stream_set_blocking($worker, false);
    router_optimize_stream($client);
    router_optimize_stream($worker);
    return [
        'client' => $client,
        'listener' => null,
        'worker' => $worker,
        'process' => null,
        'to_worker' => 'CLOUDDRIVE-PROXY ' . $scheme . ' ' . (int)$port . "\r\n",
        'to_client' => '',
        'client_eof' => false,
        'worker_eof' => false,
        'response_shutdown' => false,
        'response_finished_at' => 0,
        'tls' => $scheme === 'https',
        'started_at' => microtime(true),
    ];
}

function router_start_connection_worker($client, $host, $port)
{
    $errorNumber = 0;
    $errorMessage = '';
    $listener = @stream_socket_server('tcp://127.0.0.1:0', $errorNumber, $errorMessage);
    if ($listener === false) {
        return null;
    }
    stream_set_blocking($listener, false);
    stream_set_blocking($client, false);
    router_optimize_stream($client);
    $address = stream_socket_get_name($listener, false);
    $null = DIRECTORY_SEPARATOR === '\\' ? 'NUL' : '/dev/null';
    $descriptors = [
        0 => ['file', $null, 'r'],
        1 => ['file', $null, 'a'],
        2 => ['file', $null, 'a'],
    ];
    $process = @proc_open(
        [PHP_BINARY, __FILE__, '--connection-worker', $address, $host, (string)$port],
        $descriptors,
        $pipes,
        __DIR__,
        null,
        ['bypass_shell' => true]
    );
    if (!is_resource($process)) {
        fclose($listener);
        return null;
    }

    return [
        'client' => $client,
        'listener' => $listener,
        'worker' => null,
        'process' => $process,
        'to_worker' => '',
        'to_client' => '',
        'client_eof' => false,
        'worker_eof' => false,
        'response_shutdown' => false,
        'response_finished_at' => 0,
        'tls' => false,
        'started_at' => microtime(true),
    ];
}

function router_optimize_stream($stream)
{
    if (!extension_loaded('sockets') || !is_resource($stream)) {
        return;
    }
    $socket = @socket_import_stream($stream);
    if ($socket !== false && defined('SOL_TCP') && defined('TCP_NODELAY')) {
        @socket_set_option($socket, SOL_TCP, TCP_NODELAY, 1);
    }
}

function router_close_proxied_connection(&$connection)
{
    foreach (['client', 'listener', 'worker'] as $key) {
        if (is_resource($connection[$key])) {
            if ($key === 'client' && !empty($connection['tls'])) {
                stream_set_blocking($connection[$key], true);
                @stream_socket_enable_crypto($connection[$key], false);
            }
            fclose($connection[$key]);
        }
        $connection[$key] = null;
    }
    if (is_resource($connection['process'])) {
        $status = proc_get_status($connection['process']);
        if ($status['running']) {
            proc_terminate($connection['process']);
        }
        proc_close($connection['process']);
    }
}

function router_load_config()
{
    static $cached = null;
    static $digest = null;
    static $lastCheck = 0.0;
    $file = __DIR__ . DIRECTORY_SEPARATOR . 'config.json';
    if (!is_file($file)) {
        fwrite(STDERR, "Missing config.json\n");
        exit(1);
    }
    $now = microtime(true);
    if ($cached !== null && $now - $lastCheck < 1.0) {
        return $cached;
    }
    $contents = (string)file_get_contents($file);
    $currentDigest = hash('sha256', $contents);
    $lastCheck = $now;
    if ($cached !== null && hash_equals((string)$digest, $currentDigest)) {
        return $cached;
    }
    $config = json_decode($contents, true);
    if (!is_array($config)) {
        fwrite(STDERR, "Invalid JSON in config.json\n");
        exit(1);
    }
    $digest = $currentDigest;
    return $cached = $config;
}

function router_issue_local_tls($bundleDirectory)
{
    $bundleDirectory = realpath($bundleDirectory) ?: '';
    if ($bundleDirectory === '' || !is_dir($bundleDirectory)) {
        fwrite(STDERR, "Usage: php router.php --issue-local-tls <FoxyCABundle directory>\n");
        exit(1);
    }
    $parentCertificate = $bundleDirectory . DIRECTORY_SEPARATOR . 'tls-intermediate-ca.crt';
    $parentPrivateKey = $bundleDirectory . DIRECTORY_SEPARATOR . 'tls-intermediate-ca.key';
    if (!is_file($parentCertificate) || !is_file($parentPrivateKey)) {
        fwrite(STDERR, "The bundle must contain tls-intermediate-ca.crt and tls-intermediate-ca.key.\n");
        exit(1);
    }

    $issuanceConfig = router_load_config();
    foreach (['tls_certificate', 'tls_private_key', 'tls_fullchain', 'tls_trust_certificate'] as $key) {
        unset($issuanceConfig[$key]);
    }
    $issuanceConfig['tls_issuing_ca_certificate'] = $parentCertificate;
    $issuanceConfig['tls_issuing_ca_private_key'] = $parentPrivateKey;
    $issuanceConfig['tls_chain_certificates'] = [$parentCertificate];
    $rootCertificate = $bundleDirectory . DIRECTORY_SEPARATOR . 'root-ca.crt';
    $issuanceConfig['tls_trust_certificate'] = is_file($rootCertificate) ? $rootCertificate : $parentCertificate;
    $certificate = router_ensure_tls_certificate($issuanceConfig);
    if ($certificate === null) exit(1);
    echo "Issued the CloudDrive HTTPS leaf certificate.\n";
    echo 'Leaf: ' . $certificate['certificate'] . "\n";
    echo 'Trust: ' . $certificate['trust_certificate'] . "\n";
}

function router_ensure_tls_certificate($config = null)
{
    if (!extension_loaded('openssl')) return null;
    $config = is_array($config) ? $config : router_load_config();
    $defaultDirectory = __DIR__ . DIRECTORY_SEPARATOR . 'storage' . DIRECTORY_SEPARATOR . 'system' . DIRECTORY_SEPARATOR . 'tls';
    $certificatePath = router_resolve_config_path((string)($config['tls_certificate'] ?? ($defaultDirectory . DIRECTORY_SEPARATOR . 'clouddrive.crt')));
    $privateKeyPath = router_resolve_config_path((string)($config['tls_private_key'] ?? ($defaultDirectory . DIRECTORY_SEPARATOR . 'clouddrive.key')));
    $fullchainPath = router_resolve_config_path((string)($config['tls_fullchain'] ?? ($defaultDirectory . DIRECTORY_SEPARATOR . 'clouddrive-fullchain.pem')));
    $usesConfiguredLeaf = !empty($config['tls_certificate']) && !empty($config['tls_private_key']);
    if ($usesConfiguredLeaf) {
        $certificate = is_file($certificatePath) ? @openssl_x509_read('file://' . $certificatePath) : false;
        $privateKey = is_file($privateKeyPath) ? @openssl_pkey_get_private('file://' . $privateKeyPath) : false;
        $details = $certificate ? @openssl_x509_parse($certificate) : false;
        if (!$certificate || !$privateKey || !@openssl_x509_check_private_key($certificate, $privateKey)
            || !is_array($details) || (int)($details['validTo_time_t'] ?? 0) <= time() + 30 * 86400) {
            return router_tls_failure('The configured HTTPS leaf certificate is missing, expired, or does not match its private key');
        }
        $trustCertificatePath = !empty($config['tls_trust_certificate'])
            ? router_resolve_config_path((string)$config['tls_trust_certificate'])
            : $certificatePath;
        return [
            'certificate' => $certificatePath,
            'local_certificate' => is_file($fullchainPath) ? $fullchainPath : $certificatePath,
            'private_key' => $privateKeyPath,
            'trust_certificate' => $trustCertificatePath,
        ];
    }
    $profilePath = $certificatePath . '.profile';
    $caCertificatePath = !empty($config['tls_issuing_ca_certificate'])
        ? router_resolve_config_path((string)$config['tls_issuing_ca_certificate'])
        : null;
    $caPrivateKeyPath = !empty($config['tls_issuing_ca_private_key'])
        ? router_resolve_config_path((string)$config['tls_issuing_ca_private_key'])
        : null;
    $trustCertificatePath = !empty($config['tls_trust_certificate'])
        ? router_resolve_config_path((string)$config['tls_trust_certificate'])
        : $certificatePath;
    $chainPaths = [];
    foreach (($config['tls_chain_certificates'] ?? []) as $chainPath) {
        if (is_string($chainPath) && $chainPath !== '') $chainPaths[] = router_resolve_config_path($chainPath);
    }

    $hostname = gethostname() ?: php_uname('n');
    $subjectName = trim((string)($config['tls_certificate_name'] ?? $hostname));
    if ($subjectName === '' || preg_match('/[\r\n]/', $subjectName)) return router_tls_failure('Invalid TLS certificate name');
    $configuredDnsNames = $config['tls_dns_names'] ?? null;
    $dnsNames = is_array($configuredDnsNames) ? $configuredDnsNames : ['localhost', $hostname];
    $dnsNames = array_values(array_unique(array_filter(array_map('trim', $dnsNames), static function ($name) {
        return is_string($name) && preg_match('/^(?:\*\.)?[A-Za-z0-9.-]+$/', $name);
    })));
    $configuredIpAddresses = $config['tls_ip_addresses'] ?? null;
    if (is_array($configuredIpAddresses)) {
        $ipAddresses = $configuredIpAddresses;
    } else {
        $ipAddresses = ['127.0.0.1', '::1'];
        foreach (gethostbynamel($hostname) ?: [] as $address) $ipAddresses[] = $address;
        $configuredHost = getenv('CLOUDDRIVE_HOST') ?: ($config['host'] ?? '');
        if (!in_array($configuredHost, ['0.0.0.0', '::'], true)) $ipAddresses[] = $configuredHost;
    }
    $ipAddresses = array_values(array_unique(array_filter(array_map('trim', $ipAddresses), static function ($address) {
        return is_string($address) && filter_var($address, FILTER_VALIDATE_IP);
    })));
    if (!$dnsNames && !$ipAddresses) return router_tls_failure('At least one valid TLS subject alternative name is required');

    $profile = hash('sha256', json_encode([
        'version' => 2,
        'subject' => $subjectName,
        'dns' => $dnsNames,
        'ip' => $ipAddresses,
        'ca_certificate' => $caCertificatePath,
        'ca_fingerprint' => is_file($caCertificatePath ?? '') ? hash_file('sha256', $caCertificatePath) : null,
        'chain' => $chainPaths,
        'trust' => $trustCertificatePath,
    ], JSON_UNESCAPED_SLASHES));
    if (is_file($certificatePath) && is_file($privateKeyPath) && is_file($fullchainPath)
        && hash_equals($profile, trim((string)@file_get_contents($profilePath)))) {
        $certificate = @openssl_x509_read('file://' . $certificatePath);
        $privateKey = @openssl_pkey_get_private('file://' . $privateKeyPath);
        $details = $certificate ? @openssl_x509_parse($certificate) : false;
        if ($certificate && $privateKey && @openssl_x509_check_private_key($certificate, $privateKey)
            && is_array($details) && (int)($details['validTo_time_t'] ?? 0) > time() + 30 * 86400) {
            return [
                'certificate' => $certificatePath,
                'local_certificate' => $fullchainPath,
                'private_key' => $privateKeyPath,
                'trust_certificate' => $trustCertificatePath,
            ];
        }
    }

    foreach (array_unique([dirname($certificatePath), dirname($privateKeyPath), dirname($fullchainPath)]) as $directory) {
        if (!is_dir($directory) && !@mkdir($directory, 0700, true) && !is_dir($directory)) return null;
    }

    $alternativeNames = [];
    foreach ($dnsNames as $index => $name) $alternativeNames[] = 'DNS.' . ($index + 1) . ' = ' . $name;
    foreach ($ipAddresses as $index => $address) $alternativeNames[] = 'IP.' . ($index + 1) . ' = ' . $address;
    $opensslConfig = "[ req ]\n"
        . "prompt = no\n"
        . "distinguished_name = distinguished_name\n"
        . "req_extensions = request_extensions\n"
        . "[ distinguished_name ]\n"
        . "CN = " . $hostname . "\n"
        . "O = CloudDrive\n"
        . "[ request_extensions ]\n"
        . "subjectAltName = @alternative_names\n"
        . "[ certificate_extensions ]\n"
        . "subjectAltName = @alternative_names\n"
        . "basicConstraints = critical, CA:FALSE\n"
        . "keyUsage = critical, digitalSignature, keyEncipherment\n"
        . "extendedKeyUsage = serverAuth\n"
        . "subjectKeyIdentifier = hash\n"
        . ($caCertificatePath ? "authorityKeyIdentifier = keyid, issuer\n" : '')
        . "[ alternative_names ]\n"
        . implode("\n", $alternativeNames) . "\n";
    $configPath = tempnam(dirname($certificatePath), '.openssl-');
    if ($configPath === false || file_put_contents($configPath, $opensslConfig, LOCK_EX) === false) {
        if ($configPath) @unlink($configPath);
        return null;
    }

    try {
        $arguments = [
            'config' => $configPath,
            'digest_alg' => 'sha256',
            'private_key_bits' => 2048,
            'private_key_type' => OPENSSL_KEYTYPE_RSA,
        ];
        $privateKey = @openssl_pkey_new($arguments);
        if ($privateKey === false) return router_tls_failure('Could not generate the private key');
        $requestArguments = $arguments + ['req_extensions' => 'request_extensions'];
        $request = @openssl_csr_new([
            'commonName' => $subjectName,
            'organizationName' => 'CloudDrive',
        ], $privateKey, $requestArguments);
        if ($request === false) return router_tls_failure('Could not generate the certificate request');
        $issuerCertificate = null;
        $signingKey = $privateKey;
        if ($caCertificatePath || $caPrivateKeyPath) {
            if (!$caCertificatePath || !$caPrivateKeyPath || !is_file($caCertificatePath) || !is_file($caPrivateKeyPath)) {
                return router_tls_failure('The configured TLS issuing CA is unavailable');
            }
            $issuerCertificate = @openssl_x509_read('file://' . $caCertificatePath);
            $signingKey = @openssl_pkey_get_private('file://' . $caPrivateKeyPath);
            if (!$issuerCertificate || !$signingKey || !@openssl_x509_check_private_key($issuerCertificate, $signingKey)) {
                return router_tls_failure('The TLS issuing CA certificate and private key do not match');
            }
        }
        $certificateArguments = $arguments + ['x509_extensions' => 'certificate_extensions'];
        $certificate = @openssl_csr_sign($request, $issuerCertificate, $signingKey, 825, $certificateArguments, random_int(1, 0x7fffffff));
        if ($certificate === false) return router_tls_failure('Could not sign the TLS certificate');
        if (!@openssl_x509_export($certificate, $certificatePem) || !@openssl_pkey_export($privateKey, $privateKeyPem, null, $arguments)) {
            return router_tls_failure('Could not export the TLS certificate');
        }
        $fullchainPem = trim($certificatePem) . "\n";
        foreach ($chainPaths as $chainPath) {
            $chainCertificate = @file_get_contents($chainPath);
            if (!is_string($chainCertificate) || strpos($chainCertificate, 'BEGIN CERTIFICATE') === false) {
                return router_tls_failure('A configured TLS chain certificate is unavailable');
            }
            $fullchainPem .= trim($chainCertificate) . "\n";
        }
        if (!router_atomic_write_file($privateKeyPath, $privateKeyPem)) return null;
        @chmod($privateKeyPath, 0600);
        if (!router_atomic_write_file($certificatePath, $certificatePem)) return null;
        if (!router_atomic_write_file($fullchainPath, $fullchainPem)) return null;
        if (!router_atomic_write_file($profilePath, $profile)) return null;
        return [
            'certificate' => $certificatePath,
            'local_certificate' => $fullchainPath,
            'private_key' => $privateKeyPath,
            'trust_certificate' => $trustCertificatePath,
        ];
    } finally {
        @unlink($configPath);
    }
}

function router_resolve_config_path($path)
{
    return router_path_is_absolute($path) ? $path : __DIR__ . DIRECTORY_SEPARATOR . $path;
}

function router_path_is_absolute($path)
{
    if ($path === '') return false;
    if ($path[0] === '/' || $path[0] === '\\') return true;
    return strlen($path) >= 3 && ctype_alpha($path[0]) && $path[1] === ':'
        && ($path[2] === '/' || $path[2] === '\\');
}

function router_tls_failure($message)
{
    $errors = [];
    while ($error = openssl_error_string()) $errors[] = $error;
    fwrite(STDERR, '[tls] ' . $message . ($errors ? ': ' . implode('; ', $errors) : '') . "\n");
    return null;
}

function router_atomic_write_file($path, $contents)
{
    $temporary = $path . '.' . bin2hex(random_bytes(6)) . '.tmp';
    if (file_put_contents($temporary, $contents, LOCK_EX) === false || !@rename($temporary, $path)) {
        @unlink($temporary);
        return false;
    }
    return true;
}

function router_start_application_backend()
{
    $reservation = @stream_socket_server('tcp://127.0.0.1:0', $errorNumber, $errorMessage);
    if ($reservation === false) return null;
    $address = stream_socket_get_name($reservation, false);
    fclose($reservation);
    if (!$address) return null;

    $config = router_load_config();
    $uploadLimit = max(8, (int)($config['chunk_size'] ?? 16) + 1) . 'M';
    $memoryLimit = max(128, (int)($config['memory_limit'] ?? 128)) . 'M';
    $null = DIRECTORY_SEPARATOR === '\\' ? 'NUL' : '/dev/null';
    $descriptors = [
        0 => ['file', $null, 'r'],
        1 => ['file', $null, 'a'],
        2 => ['file', $null, 'a'],
    ];
    $command = [
        PHP_BINARY,
        '-d', 'memory_limit=' . $memoryLimit,
        '-d', 'post_max_size=' . $uploadLimit,
        '-d', 'upload_max_filesize=' . $uploadLimit,
        '-d', 'output_buffering=0',
        '-d', 'opcache.enable_cli=1',
        '-S', $address,
        __FILE__,
    ];
    $process = @proc_open($command, $descriptors, $pipes, __DIR__, null, ['bypass_shell' => true]);
    if (!is_resource($process)) return null;

    for ($attempt = 0; $attempt < 50; $attempt++) {
        $status = proc_get_status($process);
        if (!$status['running']) break;
        $probe = @stream_socket_client('tcp://' . $address, $probeNumber, $probeMessage, 0.1);
        if ($probe !== false) {
            fclose($probe);
            return [
                'address' => $address,
                'process' => $process,
                'upload_limit' => $uploadLimit,
                'memory_limit' => $memoryLimit,
            ];
        }
        usleep(20000);
    }

    @proc_terminate($process);
    @proc_close($process);
    return null;
}

function router_stop_application_backend($backend)
{
    if (!is_array($backend) || !is_resource($backend['process'] ?? null)) return;
    $status = proc_get_status($backend['process']);
    if ($status['running']) @proc_terminate($backend['process']);
    @proc_close($backend['process']);
}

function router_find_cgi_binary()
{
    $configured = getenv('PHP_CGI_BINARY');
    if ($configured && is_file($configured)) {
        return $configured;
    }

    $directory = dirname(PHP_BINARY);
    $filename = DIRECTORY_SEPARATOR === '\\' ? 'php-cgi.exe' : 'php-cgi';
    $sibling = $directory . DIRECTORY_SEPARATOR . $filename;
    if (is_file($sibling)) {
        return $sibling;
    }

    foreach (explode(PATH_SEPARATOR, getenv('PATH') ?: '') as $pathDirectory) {
        $candidate = rtrim($pathDirectory, '/\\') . DIRECTORY_SEPARATOR . $filename;
        if (is_file($candidate)) {
            return $candidate;
        }
    }
    return null;
}

function router_handle_connection($client, $cgiBinary, $host, $port)
{
    $headerBlock = router_read_headers($client);
    if ($headerBlock === null) {
        return;
    }

    $lines = preg_split('/\r?\n/', $headerBlock);
    $requestLine = array_shift($lines);
    $requestScheme = 'http';
    $requestPort = $port;
    if (preg_match('/^CLOUDDRIVE-PROXY\s+(https?)\s+(\d+)$/', $requestLine, $proxyMatch)) {
        $requestScheme = $proxyMatch[1];
        $requestPort = max(1, min(65535, (int)$proxyMatch[2]));
        $requestLine = array_shift($lines);
    }
    if (!preg_match('#^([A-Z]+)\s+(\S+)\s+HTTP/(1\.[01])$#', $requestLine, $match)) {
        router_write_simple_response($client, 400, 'Bad Request');
        return;
    }

    $method = $match[1];
    $requestTarget = $match[2];
    $protocol = $match[3];
    $headers = [];
    foreach ($lines as $line) {
        if ($line === '' || strpos($line, ':') === false) {
            continue;
        }
        [$name, $value] = explode(':', $line, 2);
        $name = strtolower(trim($name));
        $value = trim($value);
        $headers[$name] = isset($headers[$name]) ? $headers[$name] . ', ' . $value : $value;
    }

    if (($headers['expect'] ?? '') !== '' && stripos($headers['expect'], '100-continue') !== false) {
        fwrite($client, "HTTP/1.1 100 Continue\r\n\r\n");
    }

    $hasBody = stripos($headers['transfer-encoding'] ?? '', 'chunked') !== false
        || (int)($headers['content-length'] ?? 0) > 0;
    if (!$hasBody) {
        unset($GLOBALS['router_body_remainder']);
        router_execute_cgi($client, $cgiBinary, $method, $requestTarget, $protocol, $headers, null, 0, $host, $requestPort, $requestScheme);
        return;
    }

    $bodyFile = tempnam(sys_get_temp_dir(), 'clouddrive_request_');
    if ($bodyFile === false) {
        router_write_simple_response($client, 500, 'Could not buffer request');
        return;
    }

    try {
        $bodyLength = router_read_body($client, $headers, $bodyFile);
        if ($bodyLength === null) {
            router_write_simple_response($client, 400, 'Invalid request body');
            return;
        }
        router_execute_cgi($client, $cgiBinary, $method, $requestTarget, $protocol, $headers, $bodyFile, $bodyLength, $host, $requestPort, $requestScheme);
    } finally {
        @unlink($bodyFile);
    }
}

function router_read_headers($client)
{
    $data = '';
    while (strpos($data, "\r\n\r\n") === false) {
        $chunk = fread($client, 8192);
        if ($chunk === false || $chunk === '') {
            return null;
        }
        $data .= $chunk;
        if (strlen($data) > 65536) {
            return null;
        }
    }

    [$headers, $remainder] = explode("\r\n\r\n", $data, 2);
    $GLOBALS['router_body_remainder'] = $remainder;
    return $headers;
}

function router_read_body($client, $headers, $bodyFile)
{
    $output = fopen($bodyFile, 'wb');
    if ($output === false) {
        return null;
    }

    $buffer = $GLOBALS['router_body_remainder'] ?? '';
    unset($GLOBALS['router_body_remainder']);
    $length = 0;

    if (stripos($headers['transfer-encoding'] ?? '', 'chunked') !== false) {
        while (true) {
            $line = router_read_line($client, $buffer);
            if ($line === null || !preg_match('/^([0-9a-fA-F]+)(?:;.*)?$/', trim($line), $match)) {
                fclose($output);
                return null;
            }
            $chunkLength = hexdec($match[1]);
            if ($chunkLength === 0) {
                while (($trailer = router_read_line($client, $buffer)) !== null && $trailer !== '') {
                }
                break;
            }
            $copied = router_copy_exact($client, $buffer, $output, $chunkLength);
            $ending = router_read_exact($client, $buffer, 2);
            if (!$copied || $ending !== "\r\n") {
                fclose($output);
                return null;
            }
            $length += $chunkLength;
        }
    } else {
        $contentLength = (int)($headers['content-length'] ?? 0);
        if ($contentLength < 0) {
            fclose($output);
            return null;
        }
        if (!router_copy_exact($client, $buffer, $output, $contentLength)) {
            fclose($output);
            return null;
        }
        $length = $contentLength;
    }

    fclose($output);
    return $length;
}

function router_read_line($client, &$buffer)
{
    while (($position = strpos($buffer, "\r\n")) === false) {
        $chunk = fread($client, 8192);
        if ($chunk === false || $chunk === '') {
            return null;
        }
        $buffer .= $chunk;
    }
    $line = substr($buffer, 0, $position);
    $buffer = substr($buffer, $position + 2);
    return $line;
}

function router_read_exact($client, &$buffer, $length)
{
    while (strlen($buffer) < $length) {
        $chunk = fread($client, min(4 * 1024 * 1024, $length - strlen($buffer)));
        if ($chunk === false || $chunk === '') {
            return null;
        }
        $buffer .= $chunk;
    }
    $result = substr($buffer, 0, $length);
    $buffer = substr($buffer, $length);
    return $result;
}

function router_copy_exact($client, &$buffer, $output, $length)
{
    $remaining = $length;
    if ($remaining > 0 && $buffer !== '') {
        $bytes = min($remaining, strlen($buffer));
        if (fwrite($output, substr($buffer, 0, $bytes)) !== $bytes) {
            return false;
        }
        $buffer = substr($buffer, $bytes);
        $remaining -= $bytes;
    }

    while ($remaining > 0) {
        $chunk = fread($client, min(4 * 1024 * 1024, $remaining));
        if ($chunk === false || $chunk === '') {
            return false;
        }
        if (fwrite($output, $chunk) !== strlen($chunk)) {
            return false;
        }
        $remaining -= strlen($chunk);
    }
    return true;
}

function router_execute_cgi($client, $cgiBinary, $method, $requestTarget, $protocol, $headers, $bodyFile, $bodyLength, $host, $port, $scheme = 'http')
{
    $url = parse_url($requestTarget);
    if ($url === false || !isset($url['path'])) {
        router_write_simple_response($client, 400, 'Bad Request');
        return;
    }

    $config = router_load_config();
    $uploadLimit = max(8, (int)($config['chunk_size'] ?? 16) + 1) . 'M';
    $memoryLimit = max(128, (int)($config['memory_limit'] ?? 128)) . 'M';
    $backend = $GLOBALS['router_application_backend'] ?? null;
    if (is_array($backend)
        && (($backend['upload_limit'] ?? '') !== $uploadLimit || ($backend['memory_limit'] ?? '') !== $memoryLimit)) {
        router_stop_application_backend($backend);
        $backend = router_start_application_backend();
        $GLOBALS['router_application_backend'] = $backend;
    }
    if (is_array($backend)) {
        if (router_execute_application_backend($client, $backend['address'], $method, $url, $protocol, $headers, $bodyFile, $bodyLength, $scheme, $port)) {
            return;
        }
        router_stop_application_backend($backend);
        $backend = router_start_application_backend();
        $GLOBALS['router_application_backend'] = $backend;
        if (is_array($backend)
            && router_execute_application_backend($client, $backend['address'], $method, $url, $protocol, $headers, $bodyFile, $bodyLength, $scheme, $port)) {
            return;
        }
    }

    $remote = stream_socket_get_name($client, true) ?: '';
    $remoteAddress = preg_replace('/:\d+$/', '', $remote);
    static $baseEnvironment = null;
    if ($baseEnvironment === null) $baseEnvironment = is_array(getenv()) ? getenv() : [];
    $environment = $baseEnvironment;
    $environment = array_merge($environment, [
        'REDIRECT_STATUS' => '200',
        'GATEWAY_INTERFACE' => 'CGI/1.1',
        'SERVER_PROTOCOL' => 'HTTP/' . $protocol,
        'SERVER_SOFTWARE' => 'CloudDrive Socket Server',
        'SERVER_NAME' => $headers['host'] ?? $host,
        'SERVER_ADDR' => $host,
        'SERVER_PORT' => (string)$port,
        'REQUEST_SCHEME' => $scheme,
        'HTTPS' => $scheme === 'https' ? 'on' : 'off',
        'REMOTE_ADDR' => $remoteAddress,
        'REQUEST_METHOD' => $method,
        'REQUEST_URI' => $requestTarget,
        'QUERY_STRING' => $url['query'] ?? '',
        'DOCUMENT_ROOT' => __DIR__ . DIRECTORY_SEPARATOR . 'public',
        'SCRIPT_FILENAME' => __FILE__,
        'SCRIPT_NAME' => '/router.php',
        'CONTENT_TYPE' => $headers['content-type'] ?? '',
        'CONTENT_LENGTH' => (string)$bodyLength,
    ]);

    foreach ($headers as $name => $value) {
        if ($name === 'content-type' || $name === 'content-length') {
            continue;
        }
        $key = 'HTTP_' . strtoupper(str_replace('-', '_', $name));
        $environment[$key] = $value;
    }

    $descriptors = [
        0 => ['file', $bodyFile ?: (DIRECTORY_SEPARATOR === '\\' ? 'NUL' : '/dev/null'), 'rb'],
        1 => ['pipe', 'wb'],
        2 => ['pipe', 'wb'],
    ];
    $command = [$cgiBinary, '-d', 'post_max_size=' . $uploadLimit, '-d', 'upload_max_filesize=' . $uploadLimit, '-d', 'output_buffering=0'];
    $process = proc_open($command, $descriptors, $pipes, __DIR__, $environment, ['bypass_shell' => true]);
    if (!is_resource($process)) {
        router_write_simple_response($client, 502, 'Could not start PHP CGI');
        return;
    }

    $cgiHeaders = '';
    while (strpos($cgiHeaders, "\r\n\r\n") === false && strlen($cgiHeaders) <= 65536 && !feof($pipes[1])) {
        $chunk = fread($pipes[1], 8192);
        if ($chunk === false) {
            break;
        }
        $cgiHeaders .= $chunk;
    }

    $separator = strpos($cgiHeaders, "\r\n\r\n");
    if ($separator === false) {
        $error = stream_get_contents($pipes[2]);
        fclose($pipes[1]);
        fclose($pipes[2]);
        proc_close($process);
        router_write_simple_response($client, 502, 'Invalid response from PHP CGI');
        if ($error !== '') {
            fwrite(STDERR, '[cgi] ' . trim($error) . "\n");
        }
        return;
    }

    $headerText = substr($cgiHeaders, 0, $separator);
    $initialBody = substr($cgiHeaders, $separator + 4);
    [$status, $responseHeaders] = router_parse_cgi_headers($headerText);
    $reason = router_status_reason($status);
    fwrite($client, "HTTP/1.1 $status $reason\r\n");
    foreach ($responseHeaders as $header) {
        fwrite($client, $header . "\r\n");
    }
    fwrite($client, "Connection: close\r\n\r\n");
    if ($initialBody !== '') {
        fwrite($client, $initialBody);
    }
    while (!feof($pipes[1])) {
        $chunk = fread($pipes[1], 4 * 1024 * 1024);
        if ($chunk === false || $chunk === '') {
            break;
        }
        fwrite($client, $chunk);
    }

    $error = stream_get_contents($pipes[2]);
    fclose($pipes[1]);
    fclose($pipes[2]);
    $exitCode = proc_close($process);
    if ($exitCode !== 0 && $error !== '') {
        fwrite(STDERR, '[cgi] ' . trim($error) . "\n");
    }
}

function router_execute_application_backend($client, $address, $method, $url, $protocol, $headers, $bodyFile, $bodyLength, $scheme, $port)
{
    $backend = @stream_socket_client('tcp://' . $address, $errorNumber, $errorMessage, 1);
    if ($backend === false) return false;
    stream_set_timeout($backend, 300);
    router_optimize_stream($backend);

    $target = $url['path'] ?? '/';
    if (isset($url['query']) && $url['query'] !== '') $target .= '?' . $url['query'];
    $request = $method . ' ' . $target . ' HTTP/' . $protocol . "\r\n";
    foreach ($headers as $name => $value) {
        if (in_array($name, ['connection', 'content-length', 'expect', 'transfer-encoding', 'x-clouddrive-remote-addr', 'x-clouddrive-proto', 'x-clouddrive-port'], true)) {
            continue;
        }
        $request .= $name . ': ' . $value . "\r\n";
    }
    if (!isset($headers['host'])) $request .= 'host: localhost' . "\r\n";
    $remote = stream_socket_get_name($client, true) ?: '';
    $remoteAddress = preg_replace('/:\d+$/', '', $remote);
    $request .= 'x-clouddrive-remote-addr: ' . $remoteAddress . "\r\n";
    $request .= 'x-clouddrive-proto: ' . $scheme . "\r\n";
    $request .= 'x-clouddrive-port: ' . (int)$port . "\r\n";
    $request .= 'content-length: ' . $bodyLength . "\r\n";
    $request .= "connection: close\r\n\r\n";

    if (!router_write_all($backend, $request)) {
        fclose($backend);
        router_write_simple_response($client, 502, 'Application backend unavailable');
        return true;
    }
    if ($bodyLength > 0) {
        $input = @fopen($bodyFile, 'rb');
        if ($input === false) {
            fclose($backend);
            router_write_simple_response($client, 502, 'Could not read request body');
            return true;
        }
        $remaining = $bodyLength;
        while ($remaining > 0 && !feof($input)) {
            $chunk = fread($input, min(1024 * 1024, $remaining));
            if ($chunk === false || $chunk === '' || !router_write_all($backend, $chunk)) break;
            $remaining -= strlen($chunk);
        }
        fclose($input);
        if ($remaining !== 0) {
            fclose($backend);
            router_write_simple_response($client, 502, 'Could not forward request body');
            return true;
        }
    }

    $received = false;
    while (!feof($backend)) {
        $chunk = fread($backend, 4 * 1024 * 1024);
        if ($chunk === false || $chunk === '') break;
        $received = true;
        if (!router_write_all($client, $chunk)) break;
    }
    fclose($backend);
    if (!$received) router_write_simple_response($client, 502, 'Application backend returned no response');
    return true;
}

function router_write_all($stream, $data)
{
    $offset = 0;
    $length = strlen($data);
    while ($offset < $length) {
        $written = @fwrite($stream, substr($data, $offset));
        if ($written === false || $written === 0) return false;
        $offset += $written;
    }
    return true;
}

function router_parse_cgi_headers($headerText)
{
    $status = 200;
    $headers = [];
    foreach (preg_split('/\r?\n/', $headerText) as $line) {
        if (stripos($line, 'Status:') === 0) {
            $status = (int)trim(substr($line, 7));
            continue;
        }
        if (stripos($line, 'Connection:') === 0 || stripos($line, 'Transfer-Encoding:') === 0) {
            continue;
        }
        if ($line !== '' && strpos($line, ':') !== false) {
            $headers[] = $line;
        }
    }
    return [$status ?: 200, $headers];
}

function router_write_simple_response($client, $status, $message)
{
    if (!is_resource($client)) {
        return;
    }
    $body = $message . "\n";
    $reason = router_status_reason($status);
    @fwrite($client, "HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " . strlen($body) . "\r\nConnection: close\r\n\r\n$body");
}

function router_status_reason($status)
{
    $reasons = [
        200 => 'OK', 201 => 'Created', 204 => 'No Content', 206 => 'Partial Content',
        207 => 'Multi-Status', 301 => 'Moved Permanently', 302 => 'Found', 304 => 'Not Modified',
        400 => 'Bad Request', 401 => 'Unauthorized', 403 => 'Forbidden', 404 => 'Not Found',
        405 => 'Method Not Allowed', 409 => 'Conflict', 412 => 'Precondition Failed',
        413 => 'Content Too Large', 415 => 'Unsupported Media Type', 416 => 'Range Not Satisfiable',
        423 => 'Locked', 429 => 'Too Many Requests', 500 => 'Internal Server Error', 502 => 'Bad Gateway', 503 => 'Service Unavailable',
    ];
    return $reasons[$status] ?? 'Response';
}

function router_is_public_auth_path($requestPath)
{
    $path = rtrim($requestPath, '/');
    if ($path === '') $path = '/';
    return in_array($path, [
        '/account.html',
        '/assets/style.css',
        '/assets/account.js',
        '/favicon.ico',
        '/clouddrive.crt',
        '/api/server-info',
        '/api/mobile/v1/auth/login',
        '/api/mobile/v1/auth/register',
        '/api/mobile/v1/auth/refresh',
    ], true);
}

function router_is_dav_or_media_request($requestPath, $requestMethod)
{
    $davMethods = ['OPTIONS', 'PROPFIND', 'PROPPATCH', 'PUT', 'MKCOL', 'DELETE', 'MOVE', 'COPY', 'LOCK', 'UNLOCK'];
    if (in_array($requestMethod, $davMethods, true)) return true;
    foreach (['/drive', '/network-drive', '/CloudDrive', '/media', '/upnp'] as $mount) {
        if ($requestPath === $mount || strpos($requestPath, $mount . '/') === 0) return true;
    }
    return false;
}

function router_require_root_access($requestPath, $requestMethod)
{
    require_once __DIR__ . DIRECTORY_SEPARATOR . 'public' . DIRECTORY_SEPARATOR . 'includes' . DIRECTORY_SEPARATOR . 'mobile.php';
    $principal = mobile_request_principal(true, true);
    if ($principal && ($principal['role'] ?? '') === 'root') {
        mobile_prepare_root_storage($principal);
        return $principal;
    }

    header('Cache-Control: no-store');
    if ($requestPath === '/api' || strpos($requestPath, '/api/') === 0) {
        http_response_code(401);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['success' => false, 'error' => 'Root sign-in required']);
        exit;
    }
    if (router_is_dav_or_media_request($requestPath, $requestMethod)) {
        http_response_code(401);
        header('WWW-Authenticate: Basic realm="CloudDrive"');
        header('Content-Type: text/plain; charset=utf-8');
        echo "Root sign-in required\n";
        exit;
    }
    if ($requestMethod === 'GET' || $requestMethod === 'HEAD') {
        $next = $_SERVER['REQUEST_URI'] ?? '/';
        header('Location: /account.html?next=' . rawurlencode($next), true, 302);
        exit;
    }
    http_response_code(401);
    header('Content-Type: text/plain; charset=utf-8');
    echo "Root sign-in required\n";
    exit;
}

function router_dispatch_request()
{
    $socketRemote = $_SERVER['REMOTE_ADDR'] ?? '';
    $forwardedRemote = $_SERVER['HTTP_X_CLOUDDRIVE_REMOTE_ADDR'] ?? '';
    if (in_array($socketRemote, ['127.0.0.1', '::1'], true) && filter_var($forwardedRemote, FILTER_VALIDATE_IP)) {
        $_SERVER['REMOTE_ADDR'] = $forwardedRemote;
        $forwardedScheme = strtolower((string)($_SERVER['HTTP_X_CLOUDDRIVE_PROTO'] ?? ''));
        $forwardedPort = (int)($_SERVER['HTTP_X_CLOUDDRIVE_PORT'] ?? 0);
        if (in_array($forwardedScheme, ['http', 'https'], true) && $forwardedPort >= 1 && $forwardedPort <= 65535) {
            $_SERVER['REQUEST_SCHEME'] = $forwardedScheme;
            $_SERVER['SERVER_PORT'] = (string)$forwardedPort;
            $_SERVER['HTTPS'] = $forwardedScheme === 'https' ? 'on' : 'off';
        }
    }
    unset($_SERVER['HTTP_X_CLOUDDRIVE_REMOTE_ADDR'], $_SERVER['HTTP_X_CLOUDDRIVE_PROTO'], $_SERVER['HTTP_X_CLOUDDRIVE_PORT']);

    $publicRoot = __DIR__ . DIRECTORY_SEPARATOR . 'public';
    $requestPath = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
    $requestPath = is_string($requestPath) ? rawurldecode($requestPath) : '/';

    $requestMethod = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');
    if ($requestPath === '/accounts.html') {
        header('Location: /account.html', true, 301);
        return;
    }
    if ($requestPath === '/api/server-info') {
        $config = router_load_config();
        $httpPort = getenv('CLOUDDRIVE_PORT');
        $httpsPort = getenv('CLOUDDRIVE_HTTPS_PORT');
        $payload = json_encode([
            'http_port' => (int)($httpPort !== false && $httpPort !== '' ? $httpPort : ($config['port'] ?? 8080)),
            'https_enabled' => !empty($config['https_enabled']),
            'https_port' => (int)($httpsPort !== false && $httpsPort !== '' ? $httpsPort : ($config['https_port'] ?? 8443)),
            'certificate_url' => '/clouddrive.crt',
        ], JSON_UNESCAPED_SLASHES);
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: no-store');
        header('Content-Length: ' . strlen($payload));
        echo $payload;
        return;
    }
    if ($requestPath === '/clouddrive.crt') {
        $certificate = router_ensure_tls_certificate(router_load_config());
        $downloadCertificate = $certificate['trust_certificate'] ?? ($certificate['certificate'] ?? '');
        if ($certificate === null || !is_file($downloadCertificate)) {
            http_response_code(404);
            return;
        }
        $etag = '"' . hash_file('sha256', $downloadCertificate) . '"';
        header('Content-Type: application/x-x509-ca-cert');
        header('Content-Disposition: attachment; filename="clouddrive.crt"');
        header('Cache-Control: public, max-age=3600');
        header('ETag: ' . $etag);
        if (trim($_SERVER['HTTP_IF_NONE_MATCH'] ?? '') === $etag) {
            http_response_code(304);
            return;
        }
        header('Content-Length: ' . filesize($downloadCertificate));
        readfile($downloadCertificate);
        return;
    }
    $mobileApiRequest = $requestPath === '/api/mobile/v1' || strpos($requestPath, '/api/mobile/v1/') === 0;
    if (!$mobileApiRequest && !router_is_public_auth_path($requestPath)) {
        router_require_root_access($requestPath, $requestMethod);
    }
    $davMethods = ['OPTIONS', 'PROPFIND', 'PROPPATCH', 'PUT', 'MKCOL', 'DELETE', 'MOVE', 'COPY', 'LOCK', 'UNLOCK'];
    if ($requestPath === '/' && in_array($requestMethod, $davMethods, true)) {
        require_once $publicRoot . DIRECTORY_SEPARATOR . 'includes' . DIRECTORY_SEPARATOR . 'helpers.php';
        handle_webdav('/', '/');
        return;
    }

    if ($requestPath === '/upnp' || strpos($requestPath, '/upnp/') === 0) {
        require_once $publicRoot . DIRECTORY_SEPARATOR . 'includes' . DIRECTORY_SEPARATOR . 'helpers.php';
        router_handle_upnp($requestPath);
        return;
    }

    if ($requestPath === '/media' || strpos($requestPath, '/media/') === 0) {
        require_once $publicRoot . DIRECTORY_SEPARATOR . 'includes' . DIRECTORY_SEPARATOR . 'helpers.php';
        router_handle_media($requestPath);
        return;
    }

    if ($requestPath === '/drive' || strpos($requestPath, '/drive/') === 0) {
        require_once $publicRoot . DIRECTORY_SEPARATOR . 'includes' . DIRECTORY_SEPARATOR . 'helpers.php';
        handle_webdav($requestPath);
        return;
    }

    if ($requestPath === '/network-drive' || strpos($requestPath, '/network-drive/') === 0) {
        require_once $publicRoot . DIRECTORY_SEPARATOR . 'includes' . DIRECTORY_SEPARATOR . 'helpers.php';
        handle_webdav($requestPath, '/network-drive');
        return;
    }

    if ($requestPath === '/CloudDrive' || strpos($requestPath, '/CloudDrive/') === 0) {
        require_once $publicRoot . DIRECTORY_SEPARATOR . 'includes' . DIRECTORY_SEPARATOR . 'helpers.php';
        handle_webdav($requestPath, '/CloudDrive');
        return;
    }

    if ($requestPath === '/api/mobile/v1/dav' || strpos($requestPath, '/api/mobile/v1/dav/') === 0) {
        require_once $publicRoot . DIRECTORY_SEPARATOR . 'includes' . DIRECTORY_SEPARATOR . 'mobile.php';
        mobile_prepare_account_storage();
        require_once $publicRoot . DIRECTORY_SEPARATOR . 'includes' . DIRECTORY_SEPARATOR . 'helpers.php';
        handle_webdav($requestPath, '/api/mobile/v1/dav');
        return;
    }

    if ($requestPath === '/api' || strpos($requestPath, '/api/') === 0) {
        $apiPath = router_public_path($publicRoot, $requestPath);
        if ($apiPath !== null && is_dir($apiPath)) {
            $apiPath .= DIRECTORY_SEPARATOR . 'index.php';
        } else {
            $apiPath = null;
        }
        if ($apiPath !== null && is_file($apiPath) && strtolower(pathinfo($apiPath, PATHINFO_EXTENSION)) === 'php') {
            $_SERVER['SCRIPT_NAME'] = $requestPath;
            $_SERVER['SCRIPT_FILENAME'] = $apiPath;
            require $apiPath;
            return;
        }
        http_response_code(404);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['success' => false, 'error' => 'API endpoint not found']);
        return;
    }

    if (in_array($requestMethod, $davMethods, true)) {
        require_once $publicRoot . DIRECTORY_SEPARATOR . 'includes' . DIRECTORY_SEPARATOR . 'helpers.php';
        handle_webdav($requestPath, '/');
        return;
    }

    $protectedPath = $requestPath === '/includes' || strpos($requestPath, '/includes/') === 0
        || $requestPath === '/storage' || strpos($requestPath, '/storage/') === 0;
    if ($protectedPath) {
        http_response_code(404);
        echo 'Not Found';
        return;
    }
    $staticFile = router_public_path($publicRoot, $requestPath);
    if ($requestPath !== '/' && $staticFile !== null && is_file($staticFile)
        && strtolower(pathinfo($staticFile, PATHINFO_EXTENSION)) !== 'php') {
        router_send_file($staticFile, ($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'HEAD');
        return;
    }

    $indexFile = $publicRoot . DIRECTORY_SEPARATOR . 'index.html';
    if (is_file($indexFile)) {
        header('Content-Type: text/html; charset=utf-8');
        header('Content-Length: ' . filesize($indexFile));
        if (($_SERVER['REQUEST_METHOD'] ?? 'GET') !== 'HEAD') {
            readfile($indexFile);
        }
        return;
    }

    http_response_code(404);
    echo 'Not Found';
}

function router_handle_upnp($path)
{
    $method = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');
    if ($path === '/upnp/device.xml' && ($method === 'GET' || $method === 'HEAD')) {
        $config = get_config();
        $host = preg_replace('/[^A-Za-z0-9.\-:\[\]]/', '', $_SERVER['HTTP_HOST'] ?? 'localhost:8080');
        $name = (string)($config['friendly_name'] ?? 'CloudDrive');
        $uuid = router_device_uuid();
        $xml = '<?xml version="1.0" encoding="utf-8"?>'
            . '<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">'
            . '<specVersion><major>1</major><minor>0</minor></specVersion>'
            . '<URLBase>http://' . webdav_xml($host) . '/</URLBase><device>'
            . '<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>'
            . '<friendlyName>' . webdav_xml($name) . '</friendlyName>'
            . '<manufacturer>CloudDrive</manufacturer><manufacturerURL>http://' . webdav_xml($host) . '/</manufacturerURL>'
            . '<modelDescription>CloudDrive network file and media server</modelDescription>'
            . '<modelName>CloudDrive Media Server</modelName><modelNumber>1.0</modelNumber>'
            . '<serialNumber>' . substr(sha1(__DIR__), 0, 12) . '</serialNumber><UDN>' . $uuid . '</UDN>'
            . '<dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC><serviceList>'
            . router_upnp_service_description('ContentDirectory', 'ContentDirectory', '/upnp/content-directory.xml', '/upnp/control/content-directory', '/upnp/event/content-directory')
            . router_upnp_service_description('ConnectionManager', 'ConnectionManager', '/upnp/connection-manager.xml', '/upnp/control/connection-manager', '/upnp/event/connection-manager')
            . '</serviceList><presentationURL>/</presentationURL></device></root>';
        router_upnp_xml_response($xml, $method === 'HEAD');
        return;
    }

    if ($path === '/upnp/content-directory.xml' && ($method === 'GET' || $method === 'HEAD')) {
        router_upnp_xml_response(router_content_directory_scpd(), $method === 'HEAD');
        return;
    }
    if ($path === '/upnp/connection-manager.xml' && ($method === 'GET' || $method === 'HEAD')) {
        router_upnp_xml_response(router_connection_manager_scpd(), $method === 'HEAD');
        return;
    }
    if ($path === '/upnp/control/content-directory' && $method === 'POST') {
        router_upnp_content_directory_action();
        return;
    }
    if ($path === '/upnp/control/connection-manager' && $method === 'POST') {
        router_upnp_connection_manager_action();
        return;
    }
    if (strpos($path, '/upnp/event/') === 0 && ($method === 'SUBSCRIBE' || $method === 'UNSUBSCRIBE')) {
        if ($method === 'SUBSCRIBE') {
            header('SID: ' . ($_SERVER['HTTP_SID'] ?? router_device_uuid()));
            header('TIMEOUT: Second-1800');
        }
        header('Content-Length: 0');
        http_response_code(200);
        return;
    }
    http_response_code(404);
    echo 'Not Found';
}

function router_upnp_service_description($type, $id, $scpd, $control, $event)
{
    return '<service><serviceType>urn:schemas-upnp-org:service:' . $type . ':1</serviceType>'
        . '<serviceId>urn:upnp-org:serviceId:' . $id . '</serviceId>'
        . '<SCPDURL>' . $scpd . '</SCPDURL><controlURL>' . $control . '</controlURL>'
        . '<eventSubURL>' . $event . '</eventSubURL></service>';
}

function router_upnp_xml_response($xml, $head = false)
{
    header('Content-Type: text/xml; charset="utf-8"');
    header('Content-Length: ' . strlen($xml));
    header('Cache-Control: no-cache');
    if (!$head) {
        echo $xml;
    }
}

function router_content_directory_scpd()
{
    return '<?xml version="1.0" encoding="utf-8"?>'
        . '<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>'
        . router_upnp_action_xml('Browse', [['ObjectID','in','A_ARG_TYPE_ObjectID'],['BrowseFlag','in','A_ARG_TYPE_BrowseFlag'],['Filter','in','A_ARG_TYPE_Filter'],['StartingIndex','in','A_ARG_TYPE_Index'],['RequestedCount','in','A_ARG_TYPE_Count'],['SortCriteria','in','A_ARG_TYPE_SortCriteria'],['Result','out','A_ARG_TYPE_Result'],['NumberReturned','out','A_ARG_TYPE_Count'],['TotalMatches','out','A_ARG_TYPE_Count'],['UpdateID','out','A_ARG_TYPE_UpdateID']])
        . router_upnp_action_xml('GetSearchCapabilities', [['SearchCaps','out','SearchCapabilities']])
        . router_upnp_action_xml('GetSortCapabilities', [['SortCaps','out','SortCapabilities']])
        . router_upnp_action_xml('GetSystemUpdateID', [['Id','out','SystemUpdateID']])
        . '</actionList><serviceStateTable>'
        . router_upnp_state_xml('A_ARG_TYPE_ObjectID','string')
        . router_upnp_state_xml('A_ARG_TYPE_BrowseFlag','string',['BrowseMetadata','BrowseDirectChildren'])
        . router_upnp_state_xml('A_ARG_TYPE_Filter','string') . router_upnp_state_xml('A_ARG_TYPE_Index','ui4')
        . router_upnp_state_xml('A_ARG_TYPE_Count','ui4') . router_upnp_state_xml('A_ARG_TYPE_SortCriteria','string')
        . router_upnp_state_xml('A_ARG_TYPE_Result','string') . router_upnp_state_xml('A_ARG_TYPE_UpdateID','ui4')
        . router_upnp_state_xml('SearchCapabilities','string') . router_upnp_state_xml('SortCapabilities','string')
        . router_upnp_state_xml('SystemUpdateID','ui4',[],true)
        . '</serviceStateTable></scpd>';
}

function router_connection_manager_scpd()
{
    return '<?xml version="1.0" encoding="utf-8"?>'
        . '<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>'
        . router_upnp_action_xml('GetProtocolInfo', [['Source','out','SourceProtocolInfo'],['Sink','out','SinkProtocolInfo']])
        . router_upnp_action_xml('GetCurrentConnectionIDs', [['ConnectionIDs','out','CurrentConnectionIDs']])
        . router_upnp_action_xml('GetCurrentConnectionInfo', [['ConnectionID','in','A_ARG_TYPE_ConnectionID'],['RcsID','out','A_ARG_TYPE_RcsID'],['AVTransportID','out','A_ARG_TYPE_AVTransportID'],['ProtocolInfo','out','A_ARG_TYPE_ProtocolInfo'],['PeerConnectionManager','out','A_ARG_TYPE_ConnectionManager'],['PeerConnectionID','out','A_ARG_TYPE_ConnectionID'],['Direction','out','A_ARG_TYPE_Direction'],['Status','out','A_ARG_TYPE_ConnectionStatus']])
        . '</actionList><serviceStateTable>'
        . router_upnp_state_xml('SourceProtocolInfo','string',[],true) . router_upnp_state_xml('SinkProtocolInfo','string',[],true)
        . router_upnp_state_xml('CurrentConnectionIDs','string',[],true) . router_upnp_state_xml('A_ARG_TYPE_ConnectionID','i4')
        . router_upnp_state_xml('A_ARG_TYPE_RcsID','i4') . router_upnp_state_xml('A_ARG_TYPE_AVTransportID','i4')
        . router_upnp_state_xml('A_ARG_TYPE_ProtocolInfo','string') . router_upnp_state_xml('A_ARG_TYPE_ConnectionManager','string')
        . router_upnp_state_xml('A_ARG_TYPE_Direction','string',['Input','Output'])
        . router_upnp_state_xml('A_ARG_TYPE_ConnectionStatus','string',['OK','ContentFormatMismatch','InsufficientBandwidth','UnreliableChannel','Unknown'])
        . '</serviceStateTable></scpd>';
}

function router_upnp_action_xml($name, $arguments)
{
    $xml = '<action><name>' . $name . '</name><argumentList>';
    foreach ($arguments as $argument) {
        $xml .= '<argument><name>' . $argument[0] . '</name><direction>' . $argument[1]
            . '</direction><relatedStateVariable>' . $argument[2] . '</relatedStateVariable></argument>';
    }
    return $xml . '</argumentList></action>';
}

function router_upnp_state_xml($name, $type, $allowed = [], $evented = false)
{
    $xml = '<stateVariable sendEvents="' . ($evented ? 'yes' : 'no') . '"><name>' . $name . '</name><dataType>' . $type . '</dataType>';
    if ($allowed) {
        $xml .= '<allowedValueList>';
        foreach ($allowed as $value) {
            $xml .= '<allowedValue>' . $value . '</allowedValue>';
        }
        $xml .= '</allowedValueList>';
    }
    return $xml . '</stateVariable>';
}

function router_upnp_content_directory_action()
{
    $action = router_upnp_action_name();
    $updateId = @filemtime(STORAGE_DIR) ?: 1;
    if ($action === 'GetSearchCapabilities') {
        router_upnp_soap_response('ContentDirectory', $action, ['SearchCaps' => '']);
    }
    if ($action === 'GetSortCapabilities') {
        router_upnp_soap_response('ContentDirectory', $action, ['SortCaps' => '']);
    }
    if ($action === 'GetSystemUpdateID') {
        router_upnp_soap_response('ContentDirectory', $action, ['Id' => $updateId]);
    }
    if ($action !== 'Browse') {
        router_upnp_fault(401, 'Invalid Action');
    }

    $body = file_get_contents('php://input');
    $objectId = router_upnp_xml_value($body, 'ObjectID') ?? '0';
    $browseFlag = router_upnp_xml_value($body, 'BrowseFlag') ?? 'BrowseDirectChildren';
    $startingIndex = max(0, (int)(router_upnp_xml_value($body, 'StartingIndex') ?? 0));
    $requestedCount = max(0, (int)(router_upnp_xml_value($body, 'RequestedCount') ?? 0));
    $virtualPath = router_upnp_decode_id($objectId);
    if ($virtualPath === null) {
        router_upnp_fault(701, 'No Such Object');
    }
    $realPath = get_real_path($virtualPath);
    if (!file_exists($realPath)) {
        router_upnp_fault(701, 'No Such Object');
    }

    $objects = [];
    $total = 1;
    if ($browseFlag === 'BrowseMetadata') {
        $objects[] = [$realPath, $virtualPath];
    } elseif ($browseFlag === 'BrowseDirectChildren' && is_dir($realPath)) {
        $children = array_values(array_filter(scandir($realPath) ?: [], function ($name) {
            return $name !== '.' && $name !== '..';
        }));
        natcasesort($children);
        $children = array_values($children);
        $total = count($children);
        $limit = $requestedCount === 0 ? null : $requestedCount;
        foreach (array_slice($children, $startingIndex, $limit) as $name) {
            $childVirtual = rtrim($virtualPath, '/') . '/' . $name;
            $objects[] = [$realPath . DIRECTORY_SEPARATOR . $name, $childVirtual];
        }
    } else {
        router_upnp_fault(710, 'Seek Mode Not Supported');
    }

    $didl = '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
        . 'xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">';
    foreach ($objects as $object) {
        $didl .= router_upnp_didl_object($object[0], $object[1]);
    }
    $didl .= '</DIDL-Lite>';
    router_upnp_soap_response('ContentDirectory', 'Browse', [
        'Result' => $didl,
        'NumberReturned' => count($objects),
        'TotalMatches' => $total,
        'UpdateID' => $updateId,
    ]);
}

function router_upnp_connection_manager_action()
{
    $action = router_upnp_action_name();
    if ($action === 'GetProtocolInfo') {
        router_upnp_soap_response('ConnectionManager', $action, [
            'Source' => 'http-get:*:audio/*:*,http-get:*:video/*:*,http-get:*:image/*:*,http-get:*:application/octet-stream:*',
            'Sink' => '',
        ]);
    }
    if ($action === 'GetCurrentConnectionIDs') {
        router_upnp_soap_response('ConnectionManager', $action, ['ConnectionIDs' => '0']);
    }
    if ($action === 'GetCurrentConnectionInfo') {
        router_upnp_soap_response('ConnectionManager', $action, [
            'RcsID' => -1, 'AVTransportID' => -1, 'ProtocolInfo' => '',
            'PeerConnectionManager' => '', 'PeerConnectionID' => -1,
            'Direction' => 'Output', 'Status' => 'OK',
        ]);
    }
    router_upnp_fault(401, 'Invalid Action');
}

function router_upnp_action_name()
{
    $soapAction = trim($_SERVER['HTTP_SOAPACTION'] ?? '', " \t\n\r\0\x0B\"");
    if (strpos($soapAction, '#') !== false) {
        return substr($soapAction, strrpos($soapAction, '#') + 1);
    }
    $body = file_get_contents('php://input');
    return preg_match('/<u:([A-Za-z0-9_]+)/', $body, $match) ? $match[1] : '';
}

function router_upnp_xml_value($xml, $name)
{
    $quoted = preg_quote($name, '/');
    return preg_match('/<(?:[A-Za-z0-9_]+:)?' . $quoted . '\b[^>]*>(.*?)<\/(?:[A-Za-z0-9_]+:)?' . $quoted . '>/si', $xml, $match)
        ? html_entity_decode(trim($match[1]), ENT_XML1 | ENT_QUOTES, 'UTF-8')
        : null;
}

function router_upnp_soap_response($service, $action, $values)
{
    $xml = '<?xml version="1.0" encoding="utf-8"?>'
        . '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        . '<s:Body><u:' . $action . 'Response xmlns:u="urn:schemas-upnp-org:service:' . $service . ':1">';
    foreach ($values as $name => $value) {
        $xml .= '<' . $name . '>' . webdav_xml($value) . '</' . $name . '>';
    }
    $xml .= '</u:' . $action . 'Response></s:Body></s:Envelope>';
    router_upnp_xml_response($xml);
    exit;
}

function router_upnp_fault($code, $description)
{
    http_response_code(500);
    $xml = '<?xml version="1.0" encoding="utf-8"?>'
        . '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>'
        . '<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>'
        . '<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>' . $code . '</errorCode>'
        . '<errorDescription>' . webdav_xml($description) . '</errorDescription></UPnPError>'
        . '</detail></s:Fault></s:Body></s:Envelope>';
    router_upnp_xml_response($xml);
    exit;
}

function router_upnp_encode_id($virtualPath)
{
    if ($virtualPath === '/' || $virtualPath === '') {
        return '0';
    }
    return 'id:' . rtrim(strtr(base64_encode($virtualPath), '+/', '-_'), '=');
}

function router_upnp_decode_id($id)
{
    if ($id === '0') {
        return '/';
    }
    if (strpos($id, 'id:') !== 0) {
        return null;
    }
    $encoded = substr($id, 3);
    $decoded = base64_decode(strtr($encoded, '-_', '+/'), true);
    return $decoded === false ? null : sanitize_path($decoded);
}

function router_upnp_didl_object($realPath, $virtualPath)
{
    $isDirectory = is_dir($realPath);
    $id = router_upnp_encode_id($virtualPath);
    $parentPath = $virtualPath === '/' ? '' : dirname(str_replace('\\', '/', $virtualPath));
    $parentId = $virtualPath === '/' ? '-1' : router_upnp_encode_id($parentPath === '.' ? '/' : $parentPath);
    $title = $virtualPath === '/' ? (get_config()['friendly_name'] ?? 'CloudDrive') : basename($realPath);
    if ($isDirectory) {
        $children = @scandir($realPath);
        $count = $children === false ? 0 : max(0, count($children) - 2);
        return '<container id="' . webdav_xml($id) . '" parentID="' . webdav_xml($parentId)
            . '" restricted="1" searchable="0" childCount="' . $count . '"><dc:title>' . webdav_xml($title)
            . '</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>';
    }

    $mime = get_metadata_mime_type($realPath);
    $class = strpos($mime, 'audio/') === 0 ? 'object.item.audioItem.musicTrack'
        : (strpos($mime, 'video/') === 0 ? 'object.item.videoItem'
        : (strpos($mime, 'image/') === 0 ? 'object.item.imageItem.photo' : 'object.item'));
    $host = preg_replace('/[^A-Za-z0-9.\-:\[\]]/', '', $_SERVER['HTTP_HOST'] ?? 'localhost:8080');
    $url = 'http://' . $host . router_media_href($virtualPath);
    return '<item id="' . webdav_xml($id) . '" parentID="' . webdav_xml($parentId) . '" restricted="1">'
        . '<dc:title>' . webdav_xml($title) . '</dc:title><upnp:class>' . $class . '</upnp:class>'
        . '<res protocolInfo="http-get:*:' . webdav_xml($mime) . ':DLNA.ORG_OP=01;DLNA.ORG_CI=0" size="'
        . (@filesize($realPath) ?: 0) . '">' . webdav_xml($url) . '</res></item>';
}

function router_media_href($virtualPath)
{
    $href = '/media';
    foreach (router_path_segments($virtualPath) ?: [] as $segment) {
        $href .= '/' . rawurlencode($segment);
    }
    return $href;
}

function router_handle_media($requestPath)
{
    $method = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');
    if ($method !== 'GET' && $method !== 'HEAD') webdav_status(405, 'Media device access is read-only');
    header('contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000');
    header('transferMode.dlna.org: Streaming');
    handle_webdav($requestPath, '/media');
}

function router_public_path($root, $path)
{
    $segments = router_path_segments($path);
    if ($segments === null) {
        return null;
    }

    $rootPath = realpath($root);
    if ($rootPath === false) {
        return null;
    }
    $file = rtrim($rootPath, '/\\') . ($segments ? DIRECTORY_SEPARATOR . implode(DIRECTORY_SEPARATOR, $segments) : '');
    $resolved = realpath($file);
    if ($resolved !== false && !router_is_inside_root($resolved, $rootPath)) {
        return null;
    }
    return $file;
}

function router_is_inside_root($path, $root)
{
    $path = rtrim(str_replace('\\', '/', $path), '/');
    $root = rtrim(str_replace('\\', '/', $root), '/');
    if (DIRECTORY_SEPARATOR === '\\') {
        return strcasecmp($path, $root) === 0 || stripos($path, $root . '/') === 0;
    }
    return $path === $root || strpos($path, $root . '/') === 0;
}

function router_path_segments($path)
{
    if (strpos($path, "\0") !== false) {
        return null;
    }

    $segments = [];
    foreach (explode('/', str_replace('\\', '/', $path)) as $segment) {
        if ($segment === '') {
            continue;
        }
        if ($segment === '.' || $segment === '..') {
            return null;
        }
        $segments[] = $segment;
    }
    return $segments;
}

function router_send_file($file, $head = false)
{
    $types = [
        'css' => 'text/css; charset=utf-8',
        'js' => 'application/javascript; charset=utf-8',
        'mjs' => 'application/javascript; charset=utf-8',
        'html' => 'text/html; charset=utf-8',
        'htm' => 'text/html; charset=utf-8',
        'json' => 'application/json; charset=utf-8',
        'map' => 'application/json; charset=utf-8',
        'txt' => 'text/plain; charset=utf-8',
        'xml' => 'application/xml; charset=utf-8',
        'svg' => 'image/svg+xml',
        'png' => 'image/png',
        'jpg' => 'image/jpeg',
        'jpeg' => 'image/jpeg',
        'gif' => 'image/gif',
        'webp' => 'image/webp',
        'avif' => 'image/avif',
        'ico' => 'image/x-icon',
        'woff' => 'font/woff',
        'woff2' => 'font/woff2',
        'ttf' => 'font/ttf',
        'otf' => 'font/otf',
        'wasm' => 'application/wasm',
        'pdf' => 'application/pdf',
    ];
    $extension = strtolower(pathinfo($file, PATHINFO_EXTENSION));
    $mime = $types[$extension] ?? false;
    if ($mime === false && function_exists('mime_content_type')) {
        $mime = @mime_content_type($file);
    }
    header('Content-Type: ' . ($mime ?: 'application/octet-stream'));
    header('X-Content-Type-Options: nosniff');
    header('Content-Length: ' . filesize($file));
    header('Last-Modified: ' . gmdate('D, d M Y H:i:s', filemtime($file)) . ' GMT');
    if (!$head) {
        readfile($file);
    }
}

function handle_webdav($requestPath, $mountPath = '/drive')
{
    $GLOBALS['webdav_mount_path'] = $mountPath;
    $method = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');
    if ($method === 'POST' && isset($_SERVER['HTTP_X_HTTP_METHOD_OVERRIDE'])) {
        $override = strtoupper(trim($_SERVER['HTTP_X_HTTP_METHOD_OVERRIDE']));
        if (in_array($override, ['PROPFIND', 'PROPPATCH', 'MKCOL', 'DELETE', 'MOVE', 'COPY', 'LOCK', 'UNLOCK'], true)) {
            $method = $override;
        }
    }
    $relativePath = substr($requestPath, strlen($mountPath));
    $target = webdav_path($relativePath);

    header('DAV: 1, 2');
    header('MS-Author-Via: DAV');
    header('X-MSDAVEXT: 1');
    header('Accept-Ranges: bytes');

    if ($target === null) {
        webdav_status(400, 'Invalid path');
    }

    switch ($method) {
        case 'OPTIONS':
            header('Allow: OPTIONS, PROPFIND, PROPPATCH, GET, HEAD, PUT, MKCOL, DELETE, MOVE, COPY, LOCK, UNLOCK');
            header('Content-Length: 0');
            http_response_code(200);
            return;

        case 'PROPFIND':
            webdav_propfind($target, $relativePath);
            return;

        case 'PROPPATCH':
            if (!file_exists($target)) {
                webdav_status(404, 'Not found');
            }
            webdav_proppatch($relativePath);
            return;

        case 'GET':
        case 'HEAD':
            if (!is_file($target)) {
                webdav_status(file_exists($target) ? 405 : 404, 'Not found');
            }
            webdav_send_file($target, $method === 'HEAD');
            return;

        case 'PUT':
            webdav_put($target);
            return;

        case 'MKCOL':
            webdav_reject_reserved_path($target);
            if ($relativePath === '' || $relativePath === '/') {
                webdav_status(405, 'The drive root already exists');
            }
            $destinationLock = webdav_destination_lock($target);
            if (file_exists($target)) {
                webdav_status(405, 'Already exists');
            }
            if (!is_dir(dirname($target))) {
                webdav_status(409, 'Parent folder does not exist');
            }
            if (!mkdir($target, 0755)) {
                webdav_status(500, 'Could not create folder');
            }
            webdav_changed(dirname($target));
            webdav_destination_unlock($destinationLock);
            http_response_code(201);
            return;

        case 'DELETE':
            webdav_reject_reserved_path($target);
            if ($relativePath === '' || $relativePath === '/') {
                webdav_status(403, 'The drive root cannot be deleted');
            }
            $destinationLock = webdav_destination_lock($target);
            if (!file_exists($target)) {
                webdav_status(404, 'Not found');
            }
            $parent = dirname($target);
            $deleted = is_dir($target) ? delete_directory($target) : @unlink($target);
            if (!$deleted) {
                webdav_status(500, 'Could not delete item');
            }
            webdav_changed($parent);
            webdav_destination_unlock($destinationLock);
            http_response_code(204);
            return;

        case 'MOVE':
        case 'COPY':
            webdav_move_or_copy($target, $relativePath, $method === 'MOVE');
            return;

        case 'LOCK':
            webdav_lock($target, $relativePath);
            return;

        case 'UNLOCK':
            http_response_code(204);
            return;
    }

    header('Allow: OPTIONS, PROPFIND, PROPPATCH, GET, HEAD, PUT, MKCOL, DELETE, MOVE, COPY, LOCK, UNLOCK');
    webdav_status(405, 'Method not allowed');
}

function webdav_path($relativePath)
{
    $segments = router_path_segments($relativePath);
    if ($segments === null) {
        return null;
    }

    $root = realpath(STORAGE_DIR);
    $path = rtrim(STORAGE_DIR, '/\\') . ($segments ? DIRECTORY_SEPARATOR . implode(DIRECTORY_SEPARATOR, $segments) : '');
    $existingPath = realpath($path);
    $existingParent = realpath(dirname($path));
    $resolved = $existingPath ?: $existingParent;

    if ($root === false || ($resolved !== false && !webdav_is_inside_root($resolved, $root))) {
        return null;
    }

    return $path;
}

function webdav_is_inside_root($path, $root)
{
    $path = rtrim(str_replace('\\', '/', $path), '/');
    $root = rtrim(str_replace('\\', '/', $root), '/');
    if (DIRECTORY_SEPARATOR === '\\') {
        return strcasecmp($path, $root) === 0 || stripos($path, $root . '/') === 0;
    }
    return $path === $root || strpos($path, $root . '/') === 0;
}

function webdav_href($relativePath, $isDirectory = false)
{
    $segments = router_path_segments($relativePath) ?: [];
    $href = rtrim($GLOBALS['webdav_mount_path'] ?? '/drive', '/');
    foreach ($segments as $segment) {
        $href .= '/' . rawurlencode($segment);
    }
    if ($href === '') {
        $href = '/';
    }
    if ($isDirectory && substr($href, -1) !== '/') {
        $href .= '/';
    }
    return $href;
}

function webdav_propfind($target, $relativePath)
{
    if (!file_exists($target)) {
        webdav_status(404, 'Not found');
    }

    $depth = strtolower(trim($_SERVER['HTTP_DEPTH'] ?? 'infinity'));
    if ($depth === 'infinity') {
        webdav_status(403, 'Infinite-depth requests are not supported');
    }
    if ($depth !== '0' && $depth !== '1') {
        webdav_status(400, 'Invalid Depth header');
    }

    $items = [[$target, $relativePath]];
    if ($depth === '1' && is_dir($target)) {
        $children = @scandir($target);
        if ($children === false) {
            webdav_status(500, 'Could not read folder');
        }
        foreach ($children as $name) {
            if ($name === '.' || $name === '..' || is_internal_storage_name($name)) {
                continue;
            }
            $childRelative = rtrim($relativePath, '/') . '/' . $name;
            $items[] = [$target . DIRECTORY_SEPARATOR . $name, $childRelative];
        }
    }

    $xml = '<?xml version="1.0" encoding="utf-8"?>'
        . '<D:multistatus xmlns:D="DAV:" xmlns:Z="urn:schemas-microsoft-com:">';
    foreach ($items as $item) {
        $xml .= webdav_property_response($item[0], $item[1]);
    }
    $xml .= '</D:multistatus>';
    header(($_SERVER['SERVER_PROTOCOL'] ?? 'HTTP/1.1') . ' 207 Multi-Status');
    header('Content-Type: application/xml; charset=utf-8');
    header('Content-Length: ' . strlen($xml));
    echo $xml;
}

function webdav_property_response($path, $relativePath)
{
    $isDirectory = is_dir($path);
    $name = basename($path);
    if ($path === STORAGE_DIR) {
        $name = (string)(get_config()['friendly_name'] ?? 'CloudDrive');
    }
    $modified = @filemtime($path) ?: time();
    $accessed = @fileatime($path) ?: $modified;
    $created = @filectime($path) ?: $modified;
    $size = $isDirectory ? 0 : (@filesize($path) ?: 0);
    $mime = $isDirectory ? 'httpd/unix-directory' : get_metadata_mime_type($path);
    $etag = '&quot;' . dechex($modified) . '-' . dechex($size) . '&quot;';
    static $quota = null;
    if ($quota === null) {
        $storage = get_storage_info_cached();
        $quota = ['used' => $storage['used_space'], 'free' => $storage['free_space']];
    }
    $used = $quota['used'];
    $free = $quota['free'];

    return '<D:response>'
        . '<D:href>' . webdav_xml(webdav_href($relativePath, $isDirectory)) . '</D:href>'
        . '<D:propstat><D:prop>'
        . '<D:displayname>' . webdav_xml($name) . '</D:displayname>'
        . '<D:resourcetype>' . ($isDirectory ? '<D:collection/>' : '') . '</D:resourcetype>'
        . '<D:getcontentlength>' . $size . '</D:getcontentlength>'
        . '<D:getcontenttype>' . webdav_xml($mime) . '</D:getcontenttype>'
        . '<D:getlastmodified>' . gmdate('D, d M Y H:i:s', $modified) . ' GMT</D:getlastmodified>'
        . '<D:creationdate>' . gmdate('Y-m-d\TH:i:s\Z', $modified) . '</D:creationdate>'
        . '<D:getetag>' . $etag . '</D:getetag>'
        . '<D:quota-used-bytes>' . max(0, (float)$used) . '</D:quota-used-bytes>'
        . '<D:quota-available-bytes>' . max(0, (float)$free) . '</D:quota-available-bytes>'
        . '<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>'
        . '<D:lockdiscovery/>'
        . '<Z:Win32CreationTime>' . gmdate('D, d M Y H:i:s', $created) . ' GMT</Z:Win32CreationTime>'
        . '<Z:Win32LastAccessTime>' . gmdate('D, d M Y H:i:s', $accessed) . ' GMT</Z:Win32LastAccessTime>'
        . '<Z:Win32LastModifiedTime>' . gmdate('D, d M Y H:i:s', $modified) . ' GMT</Z:Win32LastModifiedTime>'
        . '<Z:Win32FileAttributes>' . ($isDirectory ? '00000010' : '00000020') . '</Z:Win32FileAttributes>'
        . '</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>'
        . '</D:response>';
}

function webdav_proppatch($relativePath)
{
    $xml = '<?xml version="1.0" encoding="utf-8"?>'
        . '<D:multistatus xmlns:D="DAV:"><D:response>'
        . '<D:href>' . webdav_xml(webdav_href($relativePath)) . '</D:href>'
        . '<D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat>'
        . '</D:response></D:multistatus>';
    header(($_SERVER['SERVER_PROTOCOL'] ?? 'HTTP/1.1') . ' 207 Multi-Status');
    header('Content-Type: application/xml; charset=utf-8');
    header('Content-Length: ' . strlen($xml));
    echo $xml;
}

function webdav_destination_lock($target)
{
    $directory = CACHE_DIR . DIRECTORY_SEPARATOR . 'dav-locks';
    if (!is_dir($directory)) {
        @mkdir($directory, 0755, true);
    }
    $handle = @fopen($directory . DIRECTORY_SEPARATOR . hash('sha256', $target) . '.lock', 'c');
    if ($handle === false || !flock($handle, LOCK_EX)) {
        if (is_resource($handle)) fclose($handle);
        webdav_status(500, 'Could not lock destination');
    }
    return $handle;
}

function webdav_destination_unlock($handle)
{
    if (is_resource($handle)) {
        flock($handle, LOCK_UN);
        fclose($handle);
    }
}

function webdav_put($target)
{
    if (strtoupper($_SERVER['REQUEST_METHOD'] ?? '') !== 'PUT') {
        webdav_status(405, 'PUT method override is not supported');
    }
    webdav_reject_reserved_path($target);
    if (is_dir($target)) {
        webdav_status(405, 'Cannot overwrite a folder');
    }
    if (!is_dir(dirname($target))) {
        webdav_status(409, 'Parent folder does not exist');
    }

    $destinationLock = webdav_destination_lock($target);
    $existed = is_file($target);
    if ($existed && trim($_SERVER['HTTP_IF_NONE_MATCH'] ?? '') === '*') {
        webdav_status(412, 'Destination already exists');
    }
    $input = fopen('php://input', 'rb');
    $temp = dirname($target) . DIRECTORY_SEPARATOR . '.clouddrive-stage-upload-' . bin2hex(random_bytes(6));
    $output = @fopen($temp, 'xb');
    if ($input === false || $output === false) {
        if (is_resource($input)) {
            fclose($input);
        }
        @unlink($temp);
        webdav_status(500, 'Could not open destination');
    }
    $copied = stream_copy_to_stream($input, $output);
    fclose($input);
    fclose($output);
    $expected = isset($_SERVER['CONTENT_LENGTH']) ? (int)$_SERVER['CONTENT_LENGTH'] : null;
    if ($copied === false || ($expected !== null && $copied !== $expected)) {
        @unlink($temp);
        webdav_status(500, 'Could not write file');
    }

    if (!$existed && trim($_SERVER['HTTP_IF_NONE_MATCH'] ?? '') === '*' && file_exists($target)) {
        @unlink($temp);
        webdav_status(412, 'Destination already exists');
    }

    $backup = null;
    if ($existed) {
        $backup = dirname($target) . DIRECTORY_SEPARATOR . '.clouddrive-stage-backup-' . bin2hex(random_bytes(6));
        if (!@rename($target, $backup)) {
            @unlink($temp);
            webdav_status(500, 'Could not prepare destination');
        }
    }
    if (!@rename($temp, $target)) {
        if ($backup !== null) {
            @rename($backup, $target);
        }
        @unlink($temp);
        webdav_status(500, 'Could not finalize upload');
    }
    if ($backup !== null) {
        @unlink($backup);
    }

    webdav_changed(dirname($target));
    webdav_destination_unlock($destinationLock);
    http_response_code($existed ? 204 : 201);
    if (!$existed) {
        header('Location: ' . webdav_href(substr($target, strlen(STORAGE_DIR))));
    }
}

function webdav_move_or_copy($source, $sourceRelative, $move)
{
    webdav_reject_reserved_path($source);
    if (!file_exists($source)) {
        webdav_status(404, 'Source not found');
    }
    if ($sourceRelative === '' || $sourceRelative === '/') {
        webdav_status(403, 'The drive root cannot be changed');
    }

    $destinationHeader = $_SERVER['HTTP_DESTINATION'] ?? '';
    $destinationPath = parse_url($destinationHeader, PHP_URL_PATH);
    if (!is_string($destinationPath)) {
        webdav_status(400, 'Missing Destination header');
    }
    $destinationPath = rawurldecode($destinationPath);
    $mountPath = $GLOBALS['webdav_mount_path'] ?? '/drive';
    if ($mountPath === '/') {
        $destinationRelative = $destinationPath;
    } else {
        if ($destinationPath !== $mountPath && strpos($destinationPath, $mountPath . '/') !== 0) {
            webdav_status(403, 'Destination must be inside this drive');
        }
        $destinationRelative = substr($destinationPath, strlen($mountPath));
    }
    $destination = webdav_path($destinationRelative);
    webdav_reject_reserved_path($destination);
    if ($destination === null || !is_dir(dirname($destination))) {
        webdav_status(409, 'Destination parent does not exist');
    }
    $destinationLock = webdav_destination_lock($destination);
    if (strcasecmp(rtrim($source, '/\\'), rtrim($destination, '/\\')) === 0) {
        webdav_destination_unlock($destinationLock);
        http_response_code(204);
        return;
    }

    $sourceReal = realpath($source);
    $destinationParentReal = realpath(dirname($destination));
    if (is_dir($source) && $sourceReal && $destinationParentReal
        && ($destinationParentReal === $sourceReal || strpos($destinationParentReal, $sourceReal . DIRECTORY_SEPARATOR) === 0)) {
        webdav_status(409, 'Cannot place a folder inside itself');
    }

    $existed = file_exists($destination);
    $overwrite = strtoupper($_SERVER['HTTP_OVERWRITE'] ?? 'T') !== 'F';
    if ($existed && !$overwrite) {
        webdav_status(412, 'Destination exists');
    }
    if ($existed) {
        $temp = dirname($destination) . DIRECTORY_SEPARATOR . '.clouddrive-stage-replace-' . bin2hex(random_bytes(6));
        $prepared = $move ? @rename($source, $temp) : webdav_copy($source, $temp);
        if (!$prepared) {
            webdav_remove($temp);
            webdav_status(500, $move ? 'Could not prepare moved item' : 'Could not prepare copied item');
        }
        $backup = dirname($destination) . DIRECTORY_SEPARATOR . '.clouddrive-stage-backup-' . bin2hex(random_bytes(6));
        if (!@rename($destination, $backup)) {
            if ($move) @rename($temp, $source); else webdav_remove($temp);
            webdav_status(500, 'Could not prepare destination');
        }
        if (!@rename($temp, $destination)) {
            @rename($backup, $destination);
            if ($move) @rename($temp, $source); else webdav_remove($temp);
            webdav_status(500, $move ? 'Could not finalize moved item' : 'Could not finalize copied item');
        }
        webdav_remove($backup);
    } else {
        $success = $move ? @rename($source, $destination) : webdav_copy($source, $destination);
        if (!$success) {
            webdav_remove($destination);
            webdav_status(500, $move ? 'Could not move item' : 'Could not copy item');
        }
    }

    webdav_changed(dirname($source));
    webdav_changed(dirname($destination));
    webdav_destination_unlock($destinationLock);
    http_response_code($existed ? 204 : 201);
}

function webdav_copy($source, $destination)
{
    if (is_file($source)) {
        return @copy($source, $destination);
    }
    if (!@mkdir($destination, 0755)) {
        return false;
    }
    $children = @scandir($source);
    if ($children === false) {
        return false;
    }
    foreach ($children as $name) {
        if ($name === '.' || $name === '..' || is_internal_storage_name($name)) {
            continue;
        }
        if (!webdav_copy($source . DIRECTORY_SEPARATOR . $name, $destination . DIRECTORY_SEPARATOR . $name)) {
            return false;
        }
    }
    return true;
}

function webdav_remove($path)
{
    return is_dir($path) ? delete_directory($path) : @unlink($path);
}

function webdav_reject_reserved_path($path)
{
    if (has_reserved_storage_path(substr($path, strlen(STORAGE_DIR)))
        && trim($_SERVER['HTTP_X_CLOUDDRIVE_INTERNAL'] ?? '') !== '1') {
        webdav_status(403, 'Reserved file name');
    }
}

function webdav_lock($target, $relativePath)
{
    $exists = file_exists($target);
    if (!$exists && !is_dir(dirname($target))) {
        webdav_status(409, 'Parent folder does not exist');
    }

    $token = 'opaquelocktoken:' . sha1(webdav_href($relativePath));
    $xml = '<?xml version="1.0" encoding="utf-8"?>'
        . '<D:prop xmlns:D="DAV:"><D:lockdiscovery><D:activelock>'
        . '<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>'
        . '<D:depth>Infinity</D:depth><D:timeout>Second-3600</D:timeout>'
        . '<D:locktoken><D:href>' . $token . '</D:href></D:locktoken>'
        . '<D:lockroot><D:href>' . webdav_xml(webdav_href($relativePath, is_dir($target))) . '</D:href></D:lockroot>'
        . '</D:activelock></D:lockdiscovery></D:prop>';
    $isRefresh = isset($_SERVER['HTTP_IF']) || isset($_SERVER['HTTP_LOCK_TOKEN']);
    http_response_code($exists || $isRefresh ? 200 : 201);
    header('Lock-Token: <' . $token . '>');
    header('Content-Type: application/xml; charset=utf-8');
    header('Content-Length: ' . strlen($xml));
    echo $xml;
}

function webdav_send_file($file, $head)
{
    $size = filesize($file);
    $start = 0;
    $end = max(0, $size - 1);
    $range = $_SERVER['HTTP_RANGE'] ?? '';
    if ($range !== '' && preg_match('/^bytes=(\d*)-(\d*)$/', trim($range), $matches)) {
        if ($matches[1] === '' && $matches[2] !== '') {
            $length = min((int)$matches[2], $size);
            $start = $size - $length;
        } else {
            $start = (int)$matches[1];
            if ($matches[2] !== '') {
                $end = min((int)$matches[2], $end);
            }
        }
        if ($start < 0 || $start > $end || $start >= $size) {
            header('Content-Range: bytes */' . $size);
            webdav_status(416, 'Range not satisfiable');
        }
        http_response_code(206);
        header("Content-Range: bytes $start-$end/$size");
    }

    $length = $size === 0 ? 0 : $end - $start + 1;
    header('Content-Type: ' . get_mime_type($file));
    header('Content-Length: ' . $length);
    header('Last-Modified: ' . gmdate('D, d M Y H:i:s', filemtime($file)) . ' GMT');
    header('ETag: "' . dechex(filemtime($file)) . '-' . dechex($size) . '"');
    if ($head || $length === 0) {
        return;
    }

    $stream = fopen($file, 'rb');
    fseek($stream, $start);
    $remaining = $length;
    while ($remaining > 0 && !feof($stream)) {
        $chunk = fread($stream, min(4 * 1024 * 1024, $remaining));
        if ($chunk === false) {
            break;
        }
        echo $chunk;
        $remaining -= strlen($chunk);
    }
    fclose($stream);
}

function webdav_changed($directory)
{
    invalidate_dir_cache($directory);
    invalidate_tree_cache();
}

function webdav_xml($value)
{
    return htmlspecialchars((string)$value, ENT_XML1 | ENT_QUOTES, 'UTF-8');
}

function webdav_status($status, $message)
{
    http_response_code($status);
    header('Content-Type: text/plain; charset=utf-8');
    echo $message;
    exit;
}
