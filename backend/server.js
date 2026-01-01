const WebSocket = require('ws');
const fs = require('fs');

const port = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port });
const DEVICES_FILE = 'devices.json';

// Persistent Device Store
let knownDevices = {};
if (fs.existsSync(DEVICES_FILE)) {
    try {
        knownDevices = JSON.parse(fs.readFileSync(DEVICES_FILE, 'utf8'));
    } catch(e) { console.error("Error reading devices.json", e); }
}

function saveDevices() {
    try {
        fs.writeFileSync(DEVICES_FILE, JSON.stringify(knownDevices, null, 2));
    } catch(e) { console.error("Error saving devices.json", e); }
}

// Store connected clients. 
// Structure: { 'deviceID': { camera: wsConnection, viewers: [wsConnection, ...] } }
const rooms = {};

console.log(`MKL Relay Server started on port ${port}`);

function heartbeat() {
  this.isAlive = true;
}

wss.on('connection', (ws, req) => {
    console.log('New client connected');
    ws.isAlive = true;
    ws.on('pong', heartbeat);

    ws.send(JSON.stringify({ type: 'status', message: 'Welcome to MKL Relay' }));

    let type = null;
    let deviceId = null;

    ws.on('message', (message, isBinary) => {
        // console.log('Received:', message.toString()); 
        try {
            // If binary data, it's a video/audio frame. Relay it immediately.
            if (isBinary) {
                if (type === 'camera' && deviceId && rooms[deviceId]) {
                    rooms[deviceId].viewers.forEach(viewer => {
                        if (viewer.readyState === WebSocket.OPEN) {
                            viewer.send(message, { binary: true });
                        }
                    });
                }
                return;
            }

            // If text data, it's a command or handshake.
            const data = JSON.parse(message.toString());
            
            if (data.type === 'register_camera') {
                type = 'camera';
                deviceId = data.deviceId;
                const deviceName = data.deviceName || "Unknown Device";
                
                // Save to persistent store
                knownDevices[deviceId] = { name: deviceName, lastSeen: Date.now() };
                saveDevices();
                
                if (!rooms[deviceId]) {
                    rooms[deviceId] = { camera: null, viewers: [] };
                }
                // Close old camera connection if exists AND it's a different socket
                if (rooms[deviceId].camera && rooms[deviceId].camera !== ws) {
                    try { rooms[deviceId].camera.close(); } catch(e){}
                }
                rooms[deviceId].camera = ws;
                console.log(`Camera registered: ${deviceId} (${deviceName})`);
                ws.send(JSON.stringify({ type: 'status', message: 'registered' }));
                
                // Notify waiting viewers that camera is online
                if (rooms[deviceId].viewers && rooms[deviceId].viewers.length > 0) {
                    rooms[deviceId].viewers.forEach(v => {
                        if (v.readyState === WebSocket.OPEN) {
                            v.send(JSON.stringify({ type: 'status', message: 'camera_online' }));
                        }
                    });
                }
            } 
            else if (data.type === 'register_viewer') {
                type = 'viewer';
                deviceId = data.deviceId;
                if (!rooms[deviceId]) {
                    rooms[deviceId] = { camera: null, viewers: [] };
                }
                rooms[deviceId].viewers.push(ws);
                console.log(`Viewer joined: ${deviceId}`);
                
                // If camera is already online, tell viewer
                if (rooms[deviceId].camera && rooms[deviceId].camera.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({ type: 'status', message: 'camera_online' }));
                } else {
                    ws.send(JSON.stringify({ type: 'status', message: 'camera_offline' }));
                }
            }
            else if (data.type === 'get_devices') {
                const deviceList = [];
                // Return all known devices, marking them as online/offline
                for (const id in knownDevices) {
                    const isOnline = rooms[id] && rooms[id].camera && rooms[id].camera.readyState === WebSocket.OPEN;
                    deviceList.push({
                        id: id,
                        name: knownDevices[id].name,
                        online: isOnline
                    });
                }
                ws.send(JSON.stringify({ type: 'device_list', devices: deviceList }));
            }
            else if (data.type === 'command') {
                // Command from Viewer -> Camera
                if (type === 'viewer' && deviceId && rooms[deviceId] && rooms[deviceId].camera) {
                    if (rooms[deviceId].camera.readyState === WebSocket.OPEN) {
                        // Ensure we send as TEXT, not Buffer/Binary
                        rooms[deviceId].camera.send(message.toString()); 
                    }
                }
            }
            else if (['status_update', 'contacts_list', 'gallery_list', 'gallery_image'].includes(data.type)) {
                 // Status/Data from Camera -> Viewers
                 if (type === 'camera' && deviceId && rooms[deviceId]) {
                    rooms[deviceId].viewers.forEach(viewer => {
                        if (viewer.readyState === WebSocket.OPEN) {
                            viewer.send(message);
                        }
                    });
                 }
            }

        } catch (e) {
            console.error("Error processing message:", e);
        }
    });

    ws.on('close', () => {
        if (type === 'camera' && deviceId && rooms[deviceId] && rooms[deviceId].camera === ws) {
            rooms[deviceId].camera = null;
            console.log(`Camera disconnected: ${deviceId}`);
            // Notify viewers
            rooms[deviceId].viewers.forEach(viewer => {
                if (viewer.readyState === WebSocket.OPEN) {
                    viewer.send(JSON.stringify({ type: 'status', message: 'camera_offline' }));
                }
            });
        } 
        else if (type === 'viewer' && deviceId && rooms[deviceId]) {
            rooms[deviceId].viewers = rooms[deviceId].viewers.filter(v => v !== ws);
            console.log(`Viewer left: ${deviceId}`);
        }
    });
});

const interval = setInterval(function ping() {
  wss.clients.forEach(function each(ws) {
    if (ws.isAlive === false) return ws.terminate();

    ws.isAlive = false;
    ws.ping();
  });
}, 30000);

wss.on('close', function close() {
  clearInterval(interval);
});
