import Keycloak from "keycloak-js";
export let auth: Keycloak;
export let mode = "";
export async function initialize() {
  const response = await fetch("/api/v1/config", { cache: "no-store" });
  if (!response.ok)
    throw new Error(
      "서버 설정을 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.",
    );
  const config = await response.json();
  mode = config.mode;
  auth = new Keycloak(config);
  await auth.init({
    onLoad: "check-sso",
    pkceMethod: "S256",
    checkLoginIframe: false,
    redirectUri: location.origin + "/",
  });
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  try {
    await auth.updateToken(30);
  } catch {
    throw new Error(
      "로그인이 만료되었습니다. 로그아웃 후 다시 로그인해 주세요.",
    );
  }
  const response = await fetch("/api/v1/admin" + path, {
    method,
    headers: {
      Authorization: "Bearer " + auth.token,
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: "입력값을 확인해 주세요. 콜백은 정확한 HTTPS 주소여야 하며, DEV는 localhost의 HTTP도 허용합니다.",
      401: "로그인이 만료되었습니다. 다시 로그인해 주세요.",
      403: "플랫폼 관리자 권한이 필요합니다.",
      404: "대상을 찾을 수 없습니다. 목록을 새로고침해 주세요.",
      409: "이미 있는 코드이거나 설정이 변경되었습니다. 중지된 프로젝트는 환경·키를 만들 수 없습니다. 새로고침 후 확인해 주세요.",
    };
    throw new Error(
      messages[response.status] ??
        "서버에서 처리하지 못했습니다. 상태를 새로고침한 뒤 다시 시도해 주세요.",
    );
  }
  return response.status === 204 ? (undefined as T) : response.json();
}
