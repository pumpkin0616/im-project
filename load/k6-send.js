import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const accessToken = __ENV.ACCESS_TOKEN;
const identities = JSON.parse(__ENV.IM_IDENTITIES || '[]');

if (identities.length === 0) {
  throw new Error('IM_IDENTITIES must be a JSON array of {conversationId,userId}');
}

export const options = {
  scenarios: {
    normal: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.NORMAL_RATE || 3000),
      timeUnit: '1s',
      duration: __ENV.NORMAL_DURATION || '10m',
      preAllocatedVUs: Number(__ENV.NORMAL_VUS || 500),
      maxVUs: Number(__ENV.MAX_VUS || 3000),
      gracefulStop: '30s',
    },
    peak: {
      executor: 'constant-arrival-rate',
      startTime: __ENV.NORMAL_DURATION || '10m',
      rate: Number(__ENV.PEAK_RATE || 9000),
      timeUnit: '1s',
      duration: __ENV.PEAK_DURATION || '15m',
      preAllocatedVUs: Number(__ENV.PEAK_VUS || 1500),
      maxVUs: Number(__ENV.MAX_VUS || 3000),
      gracefulStop: '30s',
    },
  },
  thresholds: {
    'http_req_failed{scenario:normal}': ['rate<0.001'],
    'http_req_duration{scenario:normal}': ['p(95)<200', 'p(99)<500'],
    'http_req_failed{scenario:peak}': ['rate<0.001'],
    'http_req_duration{scenario:peak}': ['p(99)<1000'],
    dropped_iterations: ['count==0'],
  },
  discardResponseBodies: true,
};

export default function () {
  const identity = identities[exec.scenario.iterationInTest % identities.length];
  const body = JSON.stringify({
    conversationId: identity.conversationId,
    clientMsgId: uuidV7(),
    type: 'TEXT',
    content: {
      text: `load-${exec.scenario.name}-${exec.scenario.iterationInTest}`,
    },
    attachmentIds: [],
  });
  const response = http.post(`${baseUrl}/api/v1/messages`, body, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
    tags: { operation: 'send-message' },
  });
  check(response, { 'message committed': (result) => result.status === 201 });
}

function uuidV7() {
  const timestamp = Date.now().toString(16).padStart(12, '0');
  const random = Array.from({ length: 19 }, randomHex).join('');
  const variant = (8 + Math.floor(Math.random() * 4)).toString(16);
  const hex = `${timestamp}7${random.slice(0, 3)}${variant}${random.slice(4, 19)}`;
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function randomHex() {
  return Math.floor(Math.random() * 16).toString(16);
}
