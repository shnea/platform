---
name: SHNEA Platform
description: 프로젝트와 로그인 환경을 관리하는 어두운 기본 운영 화면
colors:
  bg: "#111918"
  surface: "#182220"
  raised: "#202d29"
  line: "#34443e"
  text: "#e8eee8"
  muted: "#a5b7ae"
  accent: "#b0e1c7"
  accent-ink: "#123b2c"
  danger: "#ffada5"
  warning-bg: "#342b1c"
  warning: "#efd6a6"
  light-bg: "#f5f6f1"
  light-surface: "#fff"
  light-raised: "#e9eee7"
  light-line: "#cdd7ce"
  light-text: "#183126"
  light-muted: "#52695c"
  light-accent: "#245c43"
  light-accent-ink: "#fff"
  light-danger: "#a32b24"
  light-warning-bg: "#fff3d9"
  light-warning: "#704b12"
typography:
  body:
    fontFamily: '"Apple SD Gothic Neo","Malgun Gothic",system-ui,sans-serif'
    fontSize: "15px"
    lineHeight: 1.6
  page-title:
    fontSize: "32px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "-.035em"
  section-title:
    fontSize: "18px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "-.035em"
  subsection-title:
    fontSize: "17px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "-.035em"
  label:
    fontSize: "14px"
    fontWeight: 600
  identifier:
    fontFamily: "ui-monospace,monospace"
    fontSize: "12px"
rounded:
  field: "5px"
  button: "6px"
  panel: "8px"
  dialog: "12px"
spacing:
  small: "8px"
  control-gap: "10px"
  compact: "12px"
  medium: "16px"
  section: "24px"
  large: "32px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.accent-ink}"
    rounded: "{rounded.button}"
    padding: "9px 15px"
  button-secondary:
    backgroundColor: "transparent"
    textColor: "{colors.text}"
    rounded: "{rounded.button}"
    padding: "9px 15px"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.muted}"
    rounded: "{rounded.button}"
    padding: "9px 10px"
  button-destructive:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.bg}"
    rounded: "{rounded.button}"
    padding: "9px 15px"
  input:
    backgroundColor: "{colors.bg}"
    textColor: "{colors.text}"
    rounded: "{rounded.field}"
    padding: "0 12px"
  navigation-active:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.accent}"
    rounded: "{rounded.button}"
  status-ready:
    textColor: "{colors.accent}"
  settings-panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.panel}"
    padding: "4px 24px 24px"
---

# Design System: SHNEA Platform

## Overview

어두운 녹회색 작업 공간과 민트색 주요 동작을 기준으로 한다. 밝은 테마는 따뜻한 회백색 바탕과 짙은 녹색 동작으로 같은 정보 위계를 유지한다. 프로젝트 행, 환경 선택, 설정 영역과 확인 대화상자가 반복되는 운영 화면이다.

구현 근거는 `apps/admin-web/src/style.css`, `main.tsx`, `SectionTabs.tsx`, `ServiceMonitoring.tsx`, `JobMonitoring.tsx`, `MonitoringWorkspace.tsx`, `JobBacklogSettings.tsx`, `OperationalAlertsPanel.tsx`다. 이 문서는 구현된 화면 기준이며 별도로 승인된 시안이나 새 시각 방향을 뜻하지 않는다.

**Key Characteristics:**

- 행과 경계선으로 목록을 정리한다.
- 배경의 명도 차이로 설정 영역과 선택 상태를 구분한다.
- 상태는 색과 한국어 문구로 함께 표시한다.

## Colors

`accent`와 `accent-ink`는 주요 동작의 배경·글자 조합이다. `bg`는 작업 공간, `surface`는 상단·설정 영역·대화상자, `raised`는 선택 메뉴와 알림에 쓴다. `line`은 행·영역 경계, `text`와 `muted`는 본문과 보조 설명에 쓴다.

`danger`는 실패·중지·폐기, `warning-bg`와 `warning`은 반영 대기와 영향 설명에 쓴다. 상태의 의미는 문구로도 제공한다. `light-` 토큰은 밝은 테마의 동일 역할이며 실제 CSS에서는 같은 변수명을 테마 선택자로 교체한다.

## Typography

한국어 기본 시스템 글꼴을 사용하며 외부 폰트를 요청하지 않는다. 본문과 화면 제목·구역 제목은 frontmatter의 역할을 따른다. 모바일 화면 제목은 27px로 줄인다. 식별자는 고정폭 글꼴로, URL과 긴 프로젝트 이름은 줄바꿈을 허용한다. 안내 문구와 상태는 12~14px 범위다.

## Brand

SHNEA는 민트 바탕에 짙은 녹색 리본형 `S`와 소문자 `shnea` 워드마크를 사용한다. 기존 `s.` 텍스트 표시는 대체했다. 관리자 헤더의 심볼은 40px, 워드마크는 91 × 24px이며 `Platform` 보조 문구를 아래에 둔다. 워드마크는 Manrope 800을 윤곽선으로 변환한 SVG로, CSS mask가 현재 테마의 글자색을 따른다. 원본과 사용 기준은 `apps/admin-web/src/assets/brand/`에 있다.

Keycloak 로그인에는 같은 심볼·워드마크를 사용하며 프로젝트의 realm 이름을 그 아래에 유지한다. 관리자와 로그인 파비콘도 같은 심볼이다. 소셜 버튼은 네이버 N·구글 G·카카오 말풍선과 공식 색상을 사용하고, 너비와 최소 높이 48px를 맞춘다. 인증 URL과 로그인 동작은 기존 Keycloak 기능을 따른다.

## Layout

상단 높이는 82px, 왼쪽 메뉴는 224px다. 본문은 최대 1376px이며 좌우 여백은 `clamp(24px,4vw,64px)`다. 주요 메뉴는 프로젝트·비동기 작업·모니터링·운영 알림·감사 이력으로 나눈다. 프로젝트 목록은 구분선이 있는 전체 너비 버튼 행으로 구성한다. 프로젝트 내부는 목적별 탭으로 나누고 선택한 내용만 표시한다. 조회 환경은 보이는 라벨이 있는 기본 선택 상자로 제공하며, 환경 상태와 함께 본문 위에 배치한다. 환경 선택 영역과 탭은 공간이 부족하면 줄바꿈한다.

모니터링은 서비스 상태·프로젝트 작업 범위 탭으로 나눈다. 서비스 상태는 프로젝트·파일·알림을 같은 너비의 세 열에 32px 간격으로 배치하고, 아래 오류율·평균 처리 시간 차트는 같은 너비의 두 열에 40px 간격으로 배치한다. 1100px 이하에서는 서비스와 차트를 각각 한 열로 바꾸고 간격은 서비스 24px, 차트 32px로 줄인다. 프로젝트 작업 안의 작업 현황 탭은 현재 작업 상태와 최근 24시간 처리 결과를 같은 두 열 차트 배치와 반응형 규칙으로 표시한다. 적체 경보 설정 탭은 기존 수신 설정과 같은 한 열 폼을 사용하며 최대 너비는 68ch다.

900px 이하에서는 메뉴를 180px로 줄이고 제목·도구 모음을 세로로 배치한다. 600px 이하에서는 상단 72px, 본문 좌우 20px를 사용한다. 주요 메뉴는 현재 메뉴 이름이 있는 버튼으로 펼치고 접으며, 펼친 메뉴는 두 열로 배치한다. 프로젝트·인증 탭은 줄바꿈하고 설정 항목과 입력 폼은 한 열이 된다. 개발·운영 모드는 모바일 메뉴 옆에도 표시한다. 감사 표만 필요한 경우 가로로 스크롤한다.

## Elevation & Depth

기본 화면은 경계선과 배경 명도 차이로 구분한다. 그림자는 확인 대화상자에만 `0 24px 90px #0005`를 사용하며 배경막은 `#0009`다.

## Shapes

입력·알림, 버튼, 설정 영역, 대화상자는 frontmatter의 둥근 모서리 값을 따른다. 프로젝트 목록 행은 모서리를 둥글게 하지 않는다. 상태 표식은 작은 원과 문구를 조합한다.

## Components

- **버튼:** 최소 높이 42px. 주요 동작은 채운 버튼, 보조 동작은 경계선 버튼, 닫기·이동은 조용한 텍스트 버튼을 사용한다. 중지·폐기의 최종 확인은 위험색으로 표시한다. 비활성 상태는 불투명도 .5와 사용할 수 없는 커서로 구분한다.
- **포커스·동작:** 키보드 포커스는 강조색 3px 외곽선과 3px 간격으로 표시한다. 버튼의 배경·경계 전환은 .15s이며 움직임 줄이기 설정에서는 전환을 끈다.
- **입력:** 단일 행 입력과 선택 상자는 높이 48px, 좌우 패딩 12px로 통일한다. 환경 코드와 환경 종류는 같은 열 너비·상단 위치를 사용한다. 보이는 라벨, 형식 안내와 오류를 제공한다. 텍스트 영역은 기존 11px 패딩과 세로 크기 조절을 유지한다.
- **주요 메뉴:** 현재 메뉴는 명도가 높은 바탕과 강조색, `aria-current="page"`로 표시한다. 메뉴 버튼은 최소 높이 44px다. 모바일 펼침 버튼은 `aria-expanded`와 `aria-controls`를 제공하며, Escape로 메뉴를 닫으면 호출 버튼으로 포커스를 돌린다.
- **프로젝트·인증 탭:** 프로젝트는 개요·인증 설정·회원·API 키·개발 테스트·프로젝트 설정으로 나눈다. 개발 테스트는 개발 모드의 DEV 환경에서만 표시한다. 인증 설정은 로그인 주소·가입/복구 정책·소셜 로그인으로 다시 나눈다. 탭은 최소 높이 44px이며 선택 상태는 명도가 높은 바탕·강조색 글자와 경계·`aria-selected`로 구분한다. 좌우 방향키와 Home·End는 포커스를 옮기고 Enter·Space로 선택한다. 개요에는 요약과 관련 화면으로 이동하는 행을 배치한다.
- **환경 선택·범위 유지:** 조회 환경은 기본 `select`로 선택한다. 주요 메뉴와 개요의 이동 행을 오가도 프로젝트·환경 선택을 유지한다. 같은 프로젝트에서 설정 저장·목록 새로고침·키 작업을 해도 유효한 선택 환경을 유지하며, 다른 프로젝트를 선택하면 이전 환경 선택을 비운다. 브라우저 페이지 재로딩까지 선택을 영구 저장하는 기능은 아니다.
- **서비스 모니터링:** 서비스 상태·프로젝트 작업은 기존 구역 탭의 선택 표시와 키보드 동작을 재사용한다. 서비스 상태는 프로젝트 선택과 무관한 개발·운영 플랫폼 전체 범위를 안내한다. 서비스별 준비 상태와 누적 요청·4xx·5xx 건수는 구분선 행과 한국어 문구로 표시하며, 상태 확인·집계 시작·지표 측정 시각을 함께 제공한다. 서버 오류율은 위험색으로 0~100% 고정 눈금, 평균 처리 시간은 강조색으로 0부터 서비스 최댓값까지의 공통 눈금을 사용한다. 기존 높이 24px 가로 막대와 숫자 정렬을 재사용하고 단위가 붙은 수치 영역은 88px로 넓힌다. 실제 0, 요청 없음, 미수집을 문구로 구분하며 지표 수집 미설정과 조회 실패는 별도 안내한다. 수동 새로고침·30초 자동 갱신 선택을 제공하고 갱신 중·조회 실패 시 이전 값과 1분 이상 지난 값을 명시한다. 측정 범위와 한계는 접고 펼치는 설명으로 제공한다.
- **작업 모니터링:** 현재 상태는 경계선이 있는 이름·수치 행으로 표시한다. 최근 24시간의 완료·최종 실패·취소는 0에서 시작하는 같은 눈금의 가로 막대로 비교하며, 각각 강조색·위험색·보조 글자색을 사용한다. 높이 24px 막대 옆에 한국어 이름과 정확한 건수를 함께 제공하고 숫자는 같은 폭으로 정렬한다. 집계 기간과 측정 시각을 표시하며, 결과가 모두 0이면 빈 막대와 안내 문구를 보여 준다. 수동 새로고침과 30초 자동 갱신 선택을 제공하고, 조회 오류·갱신 중 이전 값·1분 이상 지난 값은 문구로 구분한다. 관련 작업과 운영 알림 이동은 선택한 프로젝트·환경 범위를 유지한다.
- **대화상자:** 기본 `dialog`를 사용하고 너비는 `min(560px,calc(100vw - 32px))`, 최대 높이는 `90dvh`다. 입력으로 초기 포커스를 옮기고 Tab 순환·Escape 닫기·호출 위치 복귀를 제공한다. 처리 중에는 닫기를 막는다.
- **적체 경보 설정:** 작업 현황·적체 경보 설정은 기존 구역 탭을 재사용한다. 감시 선택 상자와 단위가 표시된 숫자 입력, 입력 아래의 범위·조건 설명을 세로로 배치한다. 저장된 감시 상태·마지막 검사 시각과 수정 중인 입력을 구분하고, 저장 전 대상 환경과 영향을 기존 대화상자에서 확인한다. 저장 충돌·실패는 대화상자를 닫아도 오류 안내를 남기며 설정 새로고침으로 확인하게 한다. 최근 변경 기록은 접고 펼치는 상세 영역으로 제공한다.
- **운영 알림 상태:** 적체 발생·해소·감시 종료는 알림 제목과 설명으로 구분한다. 읽음 확인 상태는 별도 문구로 표시하며, 실제 해소와 설정 변경에 따른 감시 종료를 같은 성공 상태로 표현하지 않는다. 목록은 기존 구분선 행과 상세 기록 펼침을 사용한다.
- **테마·비밀값:** 어두운 테마가 기본이며 상단 버튼으로 전환한다. `platform-theme` 선택만 브라우저에 저장한다. 키 원문은 발급 대화상자에서 한 번 표시하고 닫을 때 지운다.

## Do's and Don'ts

- Do 어두운 화면을 기본으로 제공하고 밝은 화면 선택을 유지한다.
- Do 모바일에서도 개발·운영 모드를 표시하고 같은 프로젝트의 설정·키 작업 후 선택 환경을 유지한다.
- Do 입력 라벨, 가시적 포커스, 오류·처리 결과 안내를 함께 제공한다.
- Don't 상태를 색만으로 전달한다.
- Don't 중지·키 폐기를 영향 설명과 확인 없이 실행한다.
- Don't 대화상자를 닫은 뒤 API 키 원문을 화면 상태나 영구 저장소에 남긴다.
