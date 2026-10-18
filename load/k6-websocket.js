import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const origin = __ENV.ORIGIN || 'http://127.0.0.1:3000';
const identities = JSON.parse(__ENV.WS_IDENTITIES || '[]');
const connections = Number(__ENV.CONNECTIONS || 1000);
const connectionDuration = Number(__ENV.CONNECTION_DURATION_SECONDS || 600);
const ready = new Counter('im_ws_ready');
const protocolErrors = new Counter('im_ws_protocol_errors');

if (identities.length < connections) throw new Error(`WS_IDENTITIES needs ${connections} access tokens with distinct device sessions`);

export const options = {
  scenarios: { connections: { executor: 'per-vu-iterations', vus: connections, iterations: 1, maxDuration: __ENV.MAX_DURATION || '15m', gracefulStop: '30s' } },
  thresholds: { checks: ['rate>0.999'], im_ws_ready: [`count>=${connections}`], im_ws_protocol_errors: ['count==0'], ws_connecting: ['p(95)<2000'] },
};

export default function () {
  const identity = identities[exec.vu.idInTest - 1];
  const ticket = http.post(`${baseUrl}/api/v1/ws/tickets`, null, {
    headers: { Authorization: `Bearer ${identity.accessToken}` }, timeout: '5s', tags: { operation: 'ws-ticket' },
  });
  if (!check(ticket, { 'ticket created': (response) => response.status === 201 })) return;
  const response = ws.connect(rewriteWebsocketUrl(ticket.json('websocketUrl')), { headers: { Origin: origin } }, (socket) => {
    socket.on('message', (raw) => {
      const frame = JSON.parse(raw);
      if (frame.op === 'READY') ready.add(1);
      if (frame.op === 'ERROR') protocolErrors.add(1);
    });
    socket.setInterval(() => socket.send(JSON.stringify({ v: 1, op: 'PING', requestId: uuid(), data: {} })), 25000);
    socket.setTimeout(() => socket.close(), connectionDuration * 1000);
  });
  check(response, { 'websocket upgraded': (result) => result && result.status === 101 });
}

function rewriteWebsocketUrl(value) {
  const override = __ENV.WS_BASE_URL;
  if (!override) return value;
  const query = value.includes('?') ? value.slice(value.indexOf('?')) : '';
  return `${override}${query}`;
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (value) => {
    const random = Math.random() * 16 | 0;
    return (value === 'x' ? random : (random & 0x3 | 0x8)).toString(16);
  });
}
