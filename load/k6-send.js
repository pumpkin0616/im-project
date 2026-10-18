import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const fallbackToken = __ENV.ACCESS_TOKEN;
const identities = JSON.parse(__ENV.IM_IDENTITIES || '[]');
const profile = __ENV.PROFILE || 'acceptance';
const committed = new Counter('im_messages_committed');

if (identities.length === 0) throw new Error('IM_IDENTITIES must contain {conversationId, accessToken?} records');
if (!fallbackToken && identities.some((identity) => !identity.accessToken)) {
  throw new Error('set ACCESS_TOKEN or accessToken on every identity');
}

const profiles = {
  smoke: { smoke: arrival('smoke', Number(__ENV.SMOKE_RATE || 50), __ENV.SMOKE_DURATION || '30s', Number(__ENV.SMOKE_VUS || 20), 0) },
  normal: { normal: arrival('normal', Number(__ENV.NORMAL_RATE || 3000), __ENV.NORMAL_DURATION || '10m', Number(__ENV.NORMAL_VUS || 500), 0) },
  peak: { peak: arrival('peak', Number(__ENV.PEAK_RATE || 9000), __ENV.PEAK_DURATION || '15m', Number(__ENV.PEAK_VUS || 1500), 0) },
  acceptance: {
    normal: arrival('normal', Number(__ENV.NORMAL_RATE || 3000), __ENV.NORMAL_DURATION || '10m', Number(__ENV.NORMAL_VUS || 500), 0),
    peak: arrival('peak', Number(__ENV.PEAK_RATE || 9000), __ENV.PEAK_DURATION || '15m', Number(__ENV.PEAK_VUS || 1500), __ENV.NORMAL_DURATION || '10m'),
  },
  soak: { soak: arrival('soak', Number(__ENV.SOAK_RATE || 3000), __ENV.SOAK_DURATION || '24h', Number(__ENV.SOAK_VUS || 750), 0) },
};
if (!profiles[profile]) throw new Error(`unknown PROFILE: ${profile}`);

export const options = {
  scenarios: profiles[profile],
  thresholds: {
    http_req_failed: ['rate<0.001'], checks: ['rate>0.999'],
    http_req_duration: ['p(95)<200', 'p(99)<1000'], dropped_iterations: ['count==0'],
  },
  discardResponseBodies: true,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const identity = identities[exec.scenario.iterationInTest % identities.length];
  const response = http.post(`${baseUrl}/api/v1/messages`, JSON.stringify({
    conversationId: identity.conversationId, clientMsgId: uuidV7(), type: 'TEXT',
    content: { text: `load-${exec.scenario.name}-${exec.scenario.iterationInTest}` }, attachmentIds: [],
  }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${identity.accessToken || fallbackToken}` },
    tags: { operation: 'send-message' }, timeout: __ENV.REQUEST_TIMEOUT || '5s',
  });
  if (check(response, { 'message committed': (result) => result.status === 201 })) committed.add(1);
}

function arrival(name, rate, duration, vus, startTime) {
  return { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration,
    preAllocatedVUs: vus, maxVUs: Number(__ENV.MAX_VUS || 5000), gracefulStop: '30s', startTime,
    tags: { load_profile: name } };
}

function uuidV7() {
  const timestamp = Date.now().toString(16).padStart(12, '0');
  const random = Array.from({ length: 19 }, randomHex).join('');
  const variant = (8 + Math.floor(Math.random() * 4)).toString(16);
  const hex = `${timestamp}7${random.slice(0, 3)}${variant}${random.slice(4, 19)}`;
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function randomHex() { return Math.floor(Math.random() * 16).toString(16); }
