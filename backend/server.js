const WebSocket = require('ws');

const port = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port });

// Store connected clients. 
// Structure: { 'deviceID': { camera: wsConnection, viewers: [wsConnection, ...] } }
const rooms = {};

console.log(`MKL Relay Server started on port ${port}`);

wss.on('connection', (ws, req) => {
    console.log('New client connected');
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
                if (!rooms[deviceId]) {
                    rooms[deviceId] = { camera: null, viewers: [] };
                }
                // Close old camera connection if exists
                if (rooms[deviceId].camera) {
                    try { rooms[deviceId].camera.close(); } catch(e){}
                }
                rooms[deviceId].camera = ws;
                console.log(`Camera registered: ${deviceId}`);
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
                const activeDevices = Object.keys(rooms).filter(id => 
                    rooms[id].camera && rooms[id].camera.readyState === WebSocket.OPEN
                );
                ws.send(JSON.stringify({ type: 'device_list', devices: activeDevices }));
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
            else if (data.type === 'status_update') {
                 // Status from Camera -> Viewers
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
        if (type === 'camera' && deviceId && rooms[deviceId]) {
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
