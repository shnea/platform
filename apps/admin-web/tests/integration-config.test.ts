import assert from 'node:assert/strict';
import test from 'node:test';
import {connectionEntries, connectionEnv, connectionUrl, defaultLogout} from '../src/features/projects/integration-config.ts';

const values = {projectId: 'project-uuid', environmentId: 'prod-uuid', apiKey: 'test-only$literal#key',
  issuer: 'https://platform.example/auth/realms/p-example', redirectUri: 'https://blog.example/auth/callback',
  logoutUri: 'https://blog.example/', adminSubject: 'member-uuid'};

test('연결 값 9개와 관리자 issuer/sub를 같은 환경으로 내보내고 비밀값의 특수문자를 보존한다', () => {
  const entries = Object.fromEntries(connectionEntries(values));
  assert.equal(Object.keys(entries).length, 9);
  assert.equal(entries.PLATFORM_ENVIRONMENT_ID, 'prod-uuid');
  assert.equal(entries.ADMIN_OIDC_ISSUER, values.issuer);
  assert.equal(entries.ADMIN_OIDC_SUBJECT, values.adminSubject);
  assert.equal(entries.PLATFORM_OIDC_CLIENT_ID, 'app');
  assert.ok(connectionEnv(values).includes("PLATFORM_API_KEY='test-only$literal#key'"));
  assert.equal(connectionEnv(values).split('\n').length, 9);
});

test('미지정 키와 관리자 값은 빈 값이며 환경변수 줄 주입은 거부한다', () => {
  const result = connectionEnv({...values, apiKey: '', adminSubject: ''});
  assert.ok(result.includes('PLATFORM_API_KEY=\n'));
  assert.ok(result.endsWith('ADMIN_OIDC_ISSUER=\nADMIN_OIDC_SUBJECT='));
  for (const apiKey of ['key\nADMIN_OIDC_SUBJECT=attacker', "key'", 'key\0', 'key\r']) {
    assert.throws(() => connectionEnv({...values, apiKey}));
  }
});

test('복귀 주소에 실행 스킴·자격증명·조각·줄바꿈을 허용하지 않는다', () => {
  for (const uri of ['', 'https://blog.example/auth/callback', 'http://localhost:3000/callback']) assert.equal(connectionUrl(uri), true);
  for (const uri of ['javascript:alert(1)', '//example.com', 'https://user:secret@example.com/', 'https://example.com/#token', 'https://example.com/\n']) assert.equal(connectionUrl(uri), false);
  assert.equal(defaultLogout(values.redirectUri), values.logoutUri);
  assert.equal(defaultLogout(''), '');
});
