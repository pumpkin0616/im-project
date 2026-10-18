import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:18080';
const token = __ENV.ACCESS_TOKEN;
const conversationId = __ENV.CONVERSATION_ID;
if (!token || !conversationId) throw new Error('ACCESS_TOKEN and CONVERSATION_ID are required');

export const options = {
  scenarios: { hotspot: { executor: 'constant-arrival-rate', rate: Number(__ENV.HOT_RATE || 500), timeUnit: '1s', duration: __ENV.HOT_DURATION || '5m', preAllocatedVUs: 200, maxVUs: Number(__ENV.MAX_VUS || 2000), gracefulStop: '30s' } },
  thresholds: { http_req_failed: ['rate<0.001'], checks: ['rate>0.999'], http_req_duration: ['p(99)<1000'], dropped_iterations: ['count==0'] },
  discardResponseBodies: true,
};

export default function () {
  const response = http.post(`${baseUrl}/api/v1/messages`, JSON.stringify({
    conversationId, clientMsgId: uuidV7(), type: 'TEXT', content: { text: 'hot-conversation-test' }, attachmentIds: [],
  }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }, timeout: '5s' });
  check(response, { 'hot message committed': (result) => result.status === 201 });
}

function uuidV7() {
  const timestamp = Date.now().toString(16).padStart(12, '0');
  const random = Array.from({ length: 19 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
  const hex = `${timestamp}7${random.slice(0, 3)}${(8 + Math.floor(Math.random() * 4)).toString(16)}${random.slice(4, 19)}`;
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
