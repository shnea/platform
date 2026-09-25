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

구현 근거는 `apps/admin-web/src/style.css`와 `main.tsx`다. 이 문서는 구현된 화면 기준이며 별도로 승인된 시안이나 새 시각 방향을 뜻하지 않는다.

**Key Characteristics:**

- 행과 경계선으로 목록을 정리한다.
- 배경의 명도 차이로 설정 영역과 선택 상태를 구분한다.
- 상태는 색과 한국어 문구로 함께 표시한다.

## Colors

`accent`와 `accent-ink`는 주요 동작의 배경·글자 조합이다. `bg`는 작업 공간, `surface`는 상단·설정 영역·대화상자, `raised`는 선택 메뉴와 알림에 쓴다. `line`은 행·영역 경계, `text`와 `muted`는 본문과 보조 설명에 쓴다.

`danger`는 실패·중지·폐기, `warning-bg`와 `warning`은 반영 대기와 영향 설명에 쓴다. 상태의 의미는 문구로도 제공한다. `light-` 토큰은 밝은 테마의 동일 역할이며 실제 CSS에서는 같은 변수명을 테마 선택자로 교체한다.

## Typography

한국어 기본 시스템 글꼴을 사용하며 외부 폰트를 요청하지 않는다. 본문과 화면 제목·구역 제목은 frontmatter의 역할을 따른다. 모바일 화면 제목은 27px로 줄인다. 식별자는 고정폭 글꼴로, URL과 긴 프로젝트 이름은 줄바꿈을 허용한다. 안내 문구와 상태는 12~14px 범위다.

## Layout

상단 높이는 82px, 왼쪽 메뉴는 224px다. 본문은 최대 1376px이며 좌우 여백은 `clamp(24px,4vw,64px)`다. 프로젝트 목록은 구분선이 있는 전체 너비 버튼 행으로 구성한다. 환경 선택은 가로로 배치하고 넘치면 해당 영역에서 스크롤한다.

900px 이하에서는 메뉴를 180px로 줄이고 제목·도구 모음을 세로로 배치한다. 600px 이하에서는 상단 72px, 가로 메뉴, 본문 좌우 20px를 사용한다. 설정 항목과 입력 폼은 한 열이 된다. 개발·운영 모드는 모바일 메뉴 옆에도 표시한다. 감사 표만 필요한 경우 가로로 스크롤한다.

## Elevation & Depth

기본 화면은 경계선과 배경 명도 차이로 구분한다. 그림자는 확인 대화상자에만 `0 24px 90px #0005`를 사용하며 배경막은 `#0009`다.

## Shapes

입력·알림, 버튼, 설정 영역, 대화상자는 frontmatter의 둥근 모서리 값을 따른다. 프로젝트 목록 행은 모서리를 둥글게 하지 않는다. 상태 표식은 작은 원과 문구를 조합한다.

## Components

- **버튼:** 최소 높이 42px. 주요 동작은 채운 버튼, 보조 동작은 경계선 버튼, 닫기·이동은 조용한 텍스트 버튼을 사용한다. 중지·폐기의 최종 확인은 위험색으로 표시한다. 비활성 상태는 불투명도 .5와 사용할 수 없는 커서로 구분한다.
- **포커스·동작:** 키보드 포커스는 강조색 3px 외곽선과 3px 간격으로 표시한다. 버튼의 배경·경계 전환은 .15s이며 움직임 줄이기 설정에서는 전환을 끈다.
- **입력:** 단일 행 입력과 선택 상자는 높이 48px, 좌우 패딩 12px로 통일한다. 환경 코드와 환경 종류는 같은 열 너비·상단 위치를 사용한다. 보이는 라벨, 형식 안내와 오류를 제공한다. 텍스트 영역은 기존 11px 패딩과 세로 크기 조절을 유지한다.
- **메뉴·환경 선택:** 현재 메뉴는 명도가 높은 바탕과 강조색으로 표시한다. 선택 환경은 강조색 경계와 `aria-pressed`로 구분한다. 같은 프로젝트에서 설정 저장·목록 새로고침·키 작업을 해도 유효한 선택 환경을 유지한다. 브라우저 페이지 재로딩까지 환경을 영구 저장하는 기능은 아니다.
- **대화상자:** 기본 `dialog`를 사용하고 너비는 `min(560px,calc(100vw - 32px))`, 최대 높이는 `90dvh`다. 입력으로 초기 포커스를 옮기고 Tab 순환·Escape 닫기·호출 위치 복귀를 제공한다. 처리 중에는 닫기를 막는다.
- **테마·비밀값:** 어두운 테마가 기본이며 상단 버튼으로 전환한다. `platform-theme` 선택만 브라우저에 저장한다. 키 원문은 발급 대화상자에서 한 번 표시하고 닫을 때 지운다.

## Do's and Don'ts

- Do 어두운 화면을 기본으로 제공하고 밝은 화면 선택을 유지한다.
- Do 모바일에서도 개발·운영 모드를 표시하고 같은 프로젝트의 설정·키 작업 후 선택 환경을 유지한다.
- Do 입력 라벨, 가시적 포커스, 오류·처리 결과 안내를 함께 제공한다.
- Don't 상태를 색만으로 전달한다.
- Don't 중지·키 폐기를 영향 설명과 확인 없이 실행한다.
- Don't 대화상자를 닫은 뒤 API 키 원문을 화면 상태나 영구 저장소에 남긴다.
