import Keycloak from "keycloak-js";
import { readApiError } from "./api-error";
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
  if (!response.ok) throw await readApiError(response);
  return response.status === 204 ? (undefined as T) : response.json();
}
