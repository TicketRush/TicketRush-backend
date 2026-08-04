1. EC2 운영 설정을 직접 수정하지 않는다.
2. deploy/ 아래 파일을 먼저 수정한다.
3. PR 검토와 병합을 거친다.
4. 호스트에 반영하기 전 기존 파일을 백업한다.
5. nginx 설정은 nginx -t 통과 후 reload한다.
6. 반영 후 health endpoint를 확인한다.
7. 저장소와 호스트 파일의 MD5를 대조한다.
8. Docker 재생성 전에 check-image-tag-drift.sh를 실행한다.
9. 드리프트가 발견되면 pull 또는 force-recreate를 진행하지 않는다.