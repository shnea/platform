# SHNEA 서비스 관계도 안내

- 범위: 핵심 공개 문서·Compose 6개에서 추출한 서비스 수준 지도. 전체 소스의 함수 호출 그래프가 아닙니다.
- 기준: 2026-09-28 저장소. 실시간 운영·배포 상태는 조회하지 않았습니다.
- 연결은 문서에 명시된 관계이며, 자동 분석의 중심성·군집은 운영 중요도나 장애 확률을 뜻하지 않습니다.
- 토큰 사용량: 현재 세션의 추출 토큰은 도구에서 계측하지 못했습니다. 아래 자동 생성된 0은 미계측 값이며 무사용·무료라는 뜻이 아닙니다. 별도 LLM API는 호출하지 않았습니다.
- HTML 지도는 vis-network CDN을 사용하므로 인터넷이 필요합니다. JSON과 이 보고서는 오프라인으로 읽을 수 있습니다.

# Graph Report - platform  (2026-09-28)

## Corpus Check
- Corpus is ~3,601 words - fits in a single context window. You may not need a graph.

## Summary
- 31 nodes · 46 edges · 6 communities (5 shown, 1 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- 호스트·에디터·외부 작업
- 프로젝트·권한·로그
- 데이터 저장·인증 메일
- 진입점·관리자·인증
- 파일 서비스·보관
- 아직 미제공인 API

## God Nodes (most connected - your core abstractions)
1. `프로젝트 서비스` - 10 edges
2. `외부 호스트 서비스` - 7 edges
3. `관리자 웹` - 7 edges
4. `환경별 서버 키` - 5 edges
5. `외부 작업 대기열` - 5 edges
6. `문서 JSON` - 4 edges
7. `파일 서비스` - 4 edges
8. `인증 엔진 Keycloak` - 4 edges
9. `공용 PostgreSQL 서버` - 4 edges
10. `공통 로그 수집·조회` - 4 edges

## Surprising Connections (you probably didn't know these)
- `관리자 웹` --체험 화면을 제공한다--> `에디터 패키지`  [EXTRACTED]
  docs/ARCHITECTURE.md → packages/editor/README.md
- `프로젝트 서비스` --내부 네트워크에 연결한다--> `내부 로그 네트워크`  [EXTRACTED]
  docs/ARCHITECTURE.md → compose.yml
- `외부 호스트 서비스` --화면에 연결한다--> `에디터 패키지`  [EXTRACTED]
  docs/SERVICE_INTEGRATION.md → packages/editor/README.md
- `문서 JSON` --호스트 저장 API에 전달한다--> `호스트의 본문 저장소`  [EXTRACTED]
  packages/editor/README.md → docs/SERVICE_INTEGRATION.md
- `파일 서비스` --원본과 변환 파일을 저장한다--> `파일 영속 볼륨`  [EXTRACTED]
  docs/ARCHITECTURE.md → compose.yml

## Communities (6 total, 1 thin omitted)

### Community 0 - "호스트·에디터·외부 작업"
Cohesion: 0.28
Nodes (9): 호스트의 업무 실행, 외부 작업 대기열, 외부 프로젝트 워커, 호스트의 본문 저장소, 외부 호스트 서비스, 첨부 파일 참조, 문서 JSON, 에디터 패키지 (+1 more)

### Community 1 - "프로젝트·권한·로그"
Cohesion: 0.39
Nodes (8): 내부 로그 네트워크, 플랫폼 내부 작업·Outbox, 프로젝트 서비스, 프로젝트, 환경별 서버 키, 공통 로그 수집·조회, 내부 로그 저장소 Loki, 개발·운영 환경

### Community 2 - "데이터 저장·인증 메일"
Cohesion: 0.33
Nodes (6): 인증 메일, 알림 서비스, 인증 전용 DB, 알림 전용 DB, 프로젝트 전용 DB, 공용 PostgreSQL 서버

### Community 3 - "진입점·관리자·인증"
Cohesion: 0.67
Nodes (4): 관리자 웹, 인증 엔진 Keycloak, 외부 진입점 Nginx, 프로젝트별 인증 영역

### Community 4 - "파일 서비스·보관"
Cohesion: 0.67
Nodes (3): 파일 영속 볼륨, 파일 서비스, 파일 전용 DB

## Knowledge Gaps
- **7 isolated node(s):** `문서 뷰어`, `첨부 파일 참조`, `프로젝트별 인증 영역`, `플랫폼 내부 작업·Outbox`, `파일 영속 볼륨` (+2 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 7 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `관리자 웹` connect `진입점·관리자·인증` to `호스트·에디터·외부 작업`, `프로젝트·권한·로그`?**
  _High betweenness centrality (0.014) - this node is a cross-community bridge._
- **Why does `프로젝트 서비스` connect `프로젝트·권한·로그` to `호스트·에디터·외부 작업`, `데이터 저장·인증 메일`?**
  _High betweenness centrality (0.012) - this node is a cross-community bridge._
- **What connects `문서 뷰어`, `첨부 파일 참조`, `프로젝트별 인증 영역` to the rest of the system?**
  _7 weakly-connected nodes found - possible documentation gaps or missing edges._