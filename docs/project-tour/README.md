# 플랫폼 그림 설명

먼저 [쉬운 그림 설명](index.html)을 브라우저로 연다. 기능 버튼과 상황별 예시를 선택하면 로그인·파일·외부 작업·로그·에디터의 역할을 볼 수 있다.

- [Graphify 서비스 관계도](graphify-out/graph.html): 31개 요소·46개 방향 연결. 검색·서비스 선택·묶음 필터·출처 확인.
- [분석 보고서](graphify-out/GRAPH_REPORT.md): 연결이 많은 요소, 군집, 관계 해석의 한계.
- [관계 데이터](graphify-out/graph.json): 노드·간선과 원본 파일·행 번호.

2026-09-28 저장소 기준이다. `docs/ARCHITECTURE.md`, `docs/SERVICE_INTEGRATION.md`, `docs/integration/JOBS.md`, `docs/integration/LOGS.md`, `packages/editor/README.md`, `compose.yml`만 Graphify 의미 추출에 사용했다. 전체 코드의 함수 호출 그래프가 아니며 자동 갱신되지 않는다. 쉬운 설명의 구현·검증 구분은 `docs/STATUS.md`를 함께 참고했다.

실제 환경변수·비밀값·운영 계정·실행 데이터는 읽거나 포함하지 않았다. 소스 경로는 저장소 상대 경로다. 그래프 무결성 검사에서 누락된 끝점·자기 연결·중복 간선은 없었다. 개념상 일부 요소의 연결이 적은 것은 이 요약의 범위이며 서비스 결함을 뜻하지 않는다.

쉬운 설명은 외부 라이브러리 없이 동작한다. Graphify HTML은 SRI가 고정된 vis-network 9.1.6 CDN을 사용하므로 인터넷 연결이 필요하다. 파일을 함께 전달할 때는 이 폴더 구조를 유지한다. 원본 문서 링크까지 필요하면 저장소의 해당 문서도 함께 전달한다.

Graphify 라이브러리로 그래프 생성·군집화·분석·HTML 내보내기를 수행하고, HTML에 한국어 안내·키보드용 선택 목록·모바일 배치·출처 설명을 보완했다. 별도 LLM API 호출은 없었으며 세션 내 의미 추출 토큰은 미계측이다. 보고서의 자동 출력 `0`을 토큰 사용량으로 해석하지 않는다.
