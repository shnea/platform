import assert from "node:assert/strict";
import test from "node:test";
import { readApiError } from "../src/shared/api-error.ts";

test("표준 오류의 한국어 안내·코드·요청 ID를 보존한다", async () => {
  const id = "1".repeat(32);
  const response = new Response(JSON.stringify({ status: 409, code: "MOCK_RESET_CHANGED", detail: "대상을 다시 확인해 주세요." }), {
    status: 409, headers: { "Content-Type": "application/problem+json", "X-Request-ID": id },
  });
  const error = await readApiError(response);
  assert.equal(error.code, "MOCK_RESET_CHANGED");
  assert.equal(error.requestId, id);
  assert.match(error.message, /대상을 다시 확인/);
  assert.ok(error.message.includes(id));
});

test("프록시 HTML·잘못된 JSON·형식 불일치는 안전한 한국어 안내로 처리한다", async () => {
  for (const [content, type] of [
    ["<html>private-proxy-debug</html>", "text/html"],
    ["invalid-json", "application/problem+json"],
    ["null", "application/problem+json"],
    [JSON.stringify({ status: 200, code: "FAILED", detail: "private-debug" }), "application/problem+json"],
  ]) {
    const error = await readApiError(new Response(content, { status: 502, headers: { "Content-Type": type, "X-Request-ID": "invalid" } }));
    assert.equal(error.code, "HTTP_ERROR");
    assert.equal(error.requestId, undefined);
    assert.doesNotMatch(error.message, /private|html|invalid-json/);
    assert.match(error.message, /현재 상태/);
  }
});
