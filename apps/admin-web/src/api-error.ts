export class ApiError extends Error {
  readonly code: string;
  readonly requestId: string | undefined;
  constructor(message: string, code: string, requestId?: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.requestId = requestId;
  }
}

export async function readApiError(response: Response): Promise<ApiError> {
  const fallback: Record<number, string> = {
    400: "요청 형식과 입력값을 확인해 주세요.",
    401: "로그인이 만료되었습니다. 다시 로그인해 주세요.",
    403: "이 요청을 처리할 권한이 없습니다.",
    404: "대상을 찾을 수 없습니다. 목록을 새로고침해 주세요.",
    409: "현재 상태가 변경되었거나 충돌합니다. 새로고침 후 확인해 주세요.",
    413: "요청 크기가 허용 범위를 초과했습니다.",
    429: "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
  };
  const id = response.headers.get("X-Request-ID");
  const requestId = id && /^[a-f0-9]{32}$/.test(id) ? id : undefined;
  let message = fallback[response.status] ?? "서버에서 처리하지 못했습니다. 현재 상태를 먼저 확인해 주세요.";
  let code = "HTTP_ERROR";
  if (response.headers.get("Content-Type")?.split(";")[0].trim() === "application/problem+json") {
    try {
      const problem: unknown = await response.json();
      if (problem && typeof problem === "object" && "code" in problem && "detail" in problem && "status" in problem
          && problem.status === response.status && typeof problem.code === "string"
          && /^[A-Z][A-Z0-9_]{0,79}$/.test(problem.code) && typeof problem.detail === "string"
          && problem.detail.length > 0 && problem.detail.length <= 1000) {
        code = problem.code;
        message = problem.detail;
      }
    } catch { /* Proxies may return an empty or malformed body; preserve a useful Korean fallback. */ }
  }
  // Displayed as React text, never HTML. The ID also remains available to callers as metadata.
  if (requestId) message += ` (요청 ID: ${requestId})`;
  return new ApiError(message, code, requestId);
}
