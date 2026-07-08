# 1. 아키텍처 결정을 기록한다

날짜: 2026-07-08

## 상태

승인됨

## 맥락

TicketRush는 MSA + 이벤트 기반 구조라, "왜 이 기술/구조를 택했는가"에 대한 결정이 계속 쌓인다(예: Outbox 패턴, Kafka DLT 정책, 결제 웹훅 검증 방식 전환). 하지만 지금은 그 **결정 근거**를 남기는 표준 자리가 없어서, 시간이 지나면 배경을 잃어버리거나 이미 폐기된 선택지를 다시 논의하게 된다.

컨벤션·디렉토리 구조·워크플로우 문서는 SSOT로 잘 분담돼 있지만, "결정 이력(ADR)"을 담는 문서는 부재했다.

## 결정

Michael Nygard가 제안한 **아키텍처 결정 기록(ADR, Architecture Decision Record)** 방식을 도입한다. 각 결정을 번호 매긴 Markdown 파일로 `docs/adr/`에 축적한다.
(참고: http://thinkrelevance.com/blog/2011/11/15/documenting-architecture-decisions)

도구는 Nat Pryce의 [adr-tools](https://github.com/npryce/adr-tools)를 사용해 자동 넘버링·상태(supersede) 링크·목차 생성을 활용한다. 도구 설치법과 사용법은 [README.md](README.md)를 참고한다.

## 결과

- 아키텍처 결정의 배경·트레이드오프가 코드와 함께 버전 관리되어, 새 팀원도 "왜 이렇게 됐는지"를 추적할 수 있다.
- 결정을 번복할 때 기존 ADR을 삭제하지 않고 새 ADR로 **supersede** 처리해 이력을 보존한다.
- adr-tools는 bash 스크립트라 팀원 각자 설치가 필요하다(→ [README.md](README.md)). 도구가 없어도 ADR 파일 자체는 누구나 읽고 형식에 맞춰 손으로 작성할 수 있다.
