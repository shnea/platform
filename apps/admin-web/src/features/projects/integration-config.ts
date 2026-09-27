export type ConnectionValues = {
  projectId: string; environmentId: string; apiKey: string; issuer: string;
  redirectUri: string; logoutUri: string; adminSubject: string;
};

export function connectionEntries(value: ConnectionValues): [string, string][] {
  return [
    ['PLATFORM_PROJECT_ID', value.projectId],
    ['PLATFORM_ENVIRONMENT_ID', value.environmentId],
    ['PLATFORM_API_KEY', value.apiKey],
    ['PLATFORM_OIDC_ISSUER', value.issuer],
    ['PLATFORM_OIDC_CLIENT_ID', 'app'],
    ['PLATFORM_OIDC_REDIRECT_URI', value.redirectUri],
    ['OIDC_POST_LOGOUT_REDIRECT_URI', value.logoutUri],
    ['ADMIN_OIDC_ISSUER', value.adminSubject ? value.issuer : ''],
    ['ADMIN_OIDC_SUBJECT', value.adminSubject],
  ];
}

export function connectionEnv(value: ConnectionValues): string {
  return connectionEntries(value).map(([name, content]) => {
    // Single quotes preserve literal dollar signs and # in dotenv values.
    if (/[\r\n\0']/.test(content)) throw new Error('연결 값에 줄바꿈이나 작은따옴표를 사용할 수 없습니다.');
    return `${name}=${content ? `'${content}'` : ''}`;
  }).join('\n');
}

export function connectionUrl(value: string): boolean {
  if (!value) return true;
  try {
    const url = new URL(value);
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password && !url.hash && !/[\r\n\0']/.test(value);
  } catch { return false; }
}

export function defaultLogout(callback: string): string {
  try { return `${new URL(callback).origin}/`; } catch { return ''; }
}
