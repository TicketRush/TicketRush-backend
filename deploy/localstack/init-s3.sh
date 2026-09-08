#!/bin/sh
# LocalStack 기동 완료 후 자동 실행된다(/etc/localstack/init/ready.d).
#
# 로컬 공연 등록(#636)이 쓰는 버킷을 만들고, 업로드한 파일을 인증 없이 GET 할 수 있도록
# 퍼블릭 읽기 정책을 건다. prod 버킷에 걸어야 하는 정책도 아래와 같은 형태다
# (버킷 이름만 실제 값으로 바꾼다) — deploy/localstack/bucket-policy.example.json 참고.
set -e

BUCKET=ticket-rush-local-bucket

awslocal s3 mb "s3://${BUCKET}"

awslocal s3api put-bucket-policy --bucket "${BUCKET}" --policy "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    {
      \"Sid\": \"PublicReadGetObject\",
      \"Effect\": \"Allow\",
      \"Principal\": \"*\",
      \"Action\": \"s3:GetObject\",
      \"Resource\": \"arn:aws:s3:::${BUCKET}/*\"
    }
  ]
}"

echo "LocalStack S3 준비 완료: ${BUCKET}"
