const WebSocket = require('ws');
const http = require('http');

const PORT = process.env.PORT || 8081;

// Create HTTP server
const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('Kids Monitor WebSocket Relay Server is Running\n');
});

// Attach WebSocket server
const wss = new WebSocket.Server({ server });

// Track connected targets and viewers
// Map<deviceId, WebSocket>
const targetDevices = new Map();
// Map<deviceId, Set<WebSocket>>
const viewers = new Map();

wss.on('connection', (ws) => {
    let clientType = null; // 'target' or 'viewer'
    let clientDeviceId = null;

    ws.on('message', (message) => {
        try {
            // Handle binary data (video/audio frames)
            if (Buffer.isBuffer(message) || message instanceof ArrayBuffer) {
                if (clientType === 'target' && clientDeviceId) {
                    const targetViewers = viewers.get(clientDeviceId);
                    if (targetViewers) {
                        targetViewers.forEach(v => {
                            if (v.readyState === WebSocket.OPEN) {
                                v.send(message);
                            }
                        });
                    }
                }
                return;
            }

            // Handle JSON text messages
            const msgStr = message.toString();
            const json = JSON.parse(msgStr);

            // 1. Target Device Registration
            if (json.type === 'register_camera') {
                clientType = 'target';
                clientDeviceId = json.deviceId;
                targetDevices.set(clientDeviceId, ws);
                console.log(`[+] Target Device Registered: ${clientDeviceId} (${json.deviceName || 'Unnamed'})`);

                // Notify connected viewers that target is online
                broadcastStatus(clientDeviceId, 'camera_online');
            }

            // 2. Viewer Registration
            else if (json.type === 'register_viewer') {
                clientType = 'viewer';
                clientDeviceId = json.targetDeviceId;

                if (!viewers.has(clientDeviceId)) {
                    viewers.set(clientDeviceId, new Set());
                }
                viewers.get(clientDeviceId).add(ws);
                console.log(`[+] Viewer Connected for Target Device: ${clientDeviceId}`);

                const isOnline = targetDevices.has(clientDeviceId) && targetDevices.get(clientDeviceId).readyState === WebSocket.OPEN;
                ws.send(JSON.stringify({
                    type: 'status',
                    message: isOnline ? 'camera_online' : 'camera_offline'
                }));
            }

            // 3. Command Relay (Viewer -> Target)
            else if (json.type === 'command') {
                if (clientType === 'viewer' && clientDeviceId) {
                    const targetWs = targetDevices.get(clientDeviceId);
                    if (targetWs && targetWs.readyState === WebSocket.OPEN) {
                        targetWs.send(msgStr);
                        console.log(`[->] Command relayed to ${clientDeviceId}:`, json.data ? json.data.type : 'unknown');
                    } else {
                        ws.send(JSON.stringify({
                            type: 'status',
                            message: 'Error: Target Device is Offline'
                        }));
                    }
                }
            }

            // 4. Data / Status Relay (Target -> Viewers)
            else if (clientType === 'target' && clientDeviceId) {
                const targetViewers = viewers.get(clientDeviceId);
                if (targetViewers) {
                    targetViewers.forEach(v => {
                        if (v.readyState === WebSocket.OPEN) {
                            v.send(msgStr);
                        }
                    });
                }
            }

            // 5. Active Device List Scanner Request
            else if (json.type === 'get_devices') {
                const deviceList = [];
                targetDevices.forEach((targetWs, devId) => {
                    const isOnline = targetWs.readyState === WebSocket.OPEN;
                    deviceList.push({
                        id: devId,
                        name: `Device ${devId}`,
                        online: isOnline
                    });
                });
                ws.send(JSON.stringify({
                    type: 'device_list',
                    devices: deviceList
                }));
            }

        } catch (err) {
            console.error('[-] Error processing message:', err.message);
        }
    });

    ws.on('close', () => {
        if (clientType === 'target' && clientDeviceId) {
            targetDevices.delete(clientDeviceId);
            console.log(`[-] Target Device Disconnected: ${clientDeviceId}`);
            broadcastStatus(clientDeviceId, 'camera_offline');
        } else if (clientType === 'viewer' && clientDeviceId) {
            const targetViewers = viewers.get(clientDeviceId);
            if (targetViewers) {
                targetViewers.delete(ws);
                if (targetViewers.size === 0) viewers.delete(clientDeviceId);
            }
            console.log(`[-] Viewer Disconnected for Device: ${clientDeviceId}`);
        }
    });
});

function broadcastStatus(deviceId, statusMsg) {
    const targetViewers = viewers.get(deviceId);
    if (targetViewers) {
        const payload = JSON.stringify({ type: 'status', message: statusMsg });
        targetViewers.forEach(v => {
            if (v.readyState === WebSocket.OPEN) {
                v.send(payload);
            }
        });
    }
}

server.listen(PORT, () => {
    console.log(`==================================================`);
    console.log(`🚀 Relay WebSocket Server running on port ${PORT}`);
    console.log(`Local Access: ws://localhost:${PORT}`);
    console.log(`==================================================`);
});
