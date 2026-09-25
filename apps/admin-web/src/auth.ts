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
      400: "입력값을 확인해 주세요. 콜백은 정확한 HTTPS 주소(DEV는 HTTP localhost도 허용), 만료일은 현재 이후여야 합니다.",
      401: "로그인이 만료되었습니다. 다시 로그인해 주세요.",
      403: "플랫폼 관리자 권한이 필요합니다.",
      404: "대상을 찾을 수 없습니다. 목록을 새로고침해 주세요.",
      409: "이미 있는 코드이거나 설정이 변경되었습니다. 중지된 프로젝트는 환경·키를 만들 수 없습니다. 새로고침 후 확인해 주세요.",
    };
    if (path.includes("/users")) {
      messages[400] = "검색어는 200자 이내로 입력해 주세요. 회원·세션 식별자와 요청값을 확인하세요.";
      messages[409] = "회원 상태가 바뀌었거나 환경이 미반영 상태입니다. 중지된 프로젝트에서는 회원을 활성화할 수 없습니다. 새로고침 후 확인해 주세요.";
    }
    if (path.includes("/mock-users/")) {
      messages[400] = "초기화 대상과 확인 정보를 다시 불러와 주세요. 한 번에 최대 20명까지 삭제할 수 있습니다.";
      messages[403] = "개발 모드의 DEV 환경에서 플랫폼 관리자만 초기화할 수 있습니다.";
      messages[409] = "대상 계정·보호 설정이 바뀌었거나 프로젝트·환경을 사용할 수 없습니다. 상태를 확인하고 대상을 다시 불러와 주세요.";
    }
    if (path.includes("/social-providers")) {
      messages[400] =
        "공통 소셜 키 등록 상태를 확인해 주세요. 실제 로그인은 키가 준비된 운영 모드의 PROD 환경에서만 켤 수 있습니다.";
      messages[409] =
        "환경이 미반영 상태이거나 소셜 설정이 변경되었습니다. 새로고침 후 확인해 주세요. 계속되면 Keycloak의 동일 별칭 설정을 확인하세요.";
    }
    if (path.includes("/authentication-policy")) {
      messages[400] =
        "비밀번호 최소 길이는 12~128자여야 합니다. 이메일 인증·복구는 전달 경로가 준비된 dev/DEV 또는 prod/PROD 환경에서 켤 수 있습니다.";
      messages[409] =
        "환경이 미반영·중지 상태이거나 정책이 변경되었습니다. 새로고침 후 확인해 주세요. 계속되면 Keycloak의 별도 비밀번호 규칙·이메일 중복 설정을 확인하세요.";
    }
    throw new Error(
      messages[response.status] ??
        "서버에서 처리하지 못했습니다. 상태를 새로고침한 뒤 다시 시도해 주세요.",
    );
  }
  return response.status === 204 ? (undefined as T) : response.json();
}
