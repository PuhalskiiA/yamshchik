// Нагрузочный тест приёма писем: POST /api/v1/emails с письмом без вложений.
// Запуск (приложение на http://localhost:8080):
//   docker run --rm -i -v "$PWD/docs/sspi/k6:/scripts" grafana/k6:2.3.0 run /scripts/submit-email.js
// Адрес меняется переменной: -e BASE_URL=http://host.docker.internal:8080
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

const EMAIL = JSON.stringify({
  to: [{ address: 'user@example.org', name: 'Нагрузочный тест' }],
  subject: 'k6',
  body: { text: 'Письмо нагрузочного теста.' },
});

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '1m', target: 50 },
    { duration: '1m', target: 50 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export default function () {
  const res = http.post(`${BASE_URL}/api/v1/emails`, {
    email: http.file(EMAIL, 'email.json', 'application/json'),
  });
  check(res, { 'принято (202)': (r) => r.status === 202 });
}
