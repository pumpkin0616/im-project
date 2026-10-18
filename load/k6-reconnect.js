import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const origin = __ENV.ORIGIN || 'http://127.0.0.1:3000';
const identities = JSON.parse(__ENV.WS_IDENTITIES || '[]');
const vus = Number(__ENV.RECONNECT_VUS || 200);
const ready = new Counter('im_reconnect_ready');

if (identities.length < vus) throw new Error(`WS_IDENTITIES needs at least ${vus} distinct device sessions`);

export const options = {
  scenarios: { storm: { executor: 'constant-vus', vus, duration: __ENV.RECONNECT_DURATION || '5m' } },
  thresholds: { checks: ['rate>0.995'], im_reconnect_ready: ['count>0'], ws_connecting: ['p(99)<3000'] },
};

export default function () {
  const identity = identities[(exec.vu.idInTest - 1) % identities.length];
  const ticket = http.post(`${baseUrl}/api/v1/ws/tickets`, null, { headers: { Authorization: `Bearer ${identity.accessToken}` }, timeout: '5s' });
  if (!check(ticket, { 'ticket created': (response) => response.status === 201 })) { sleep(1); return; }
  const response = ws.connect(rewriteWebsocketUrl(ticket.json('websocketUrl')), { headers: { Origin: origin } }, (socket) => {
    socket.on('message', (raw) => { if (JSON.parse(raw).op === 'READY') ready.add(1); });
    socket.setTimeout(() => socket.close(), Number(__ENV.RECONNECT_HOLD_MS || 2000));
  });
  check(response, { 'websocket upgraded': (result) => result && result.status === 101 });
  sleep(Math.random());
}

function rewriteWebsocketUrl(value) {
  const override = __ENV.WS_BASE_URL;
  if (!override) return value;
  const query = value.includes('?') ? value.slice(value.indexOf('?')) : '';
  return `${override}${query}`;
}
