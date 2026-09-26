#!/bin/sh
set -eu
mkdir -p /tmp/job-workspace
cp -R /source/. /tmp/job-workspace/
cd /tmp/job-workspace
result=0
gradle --no-daemon :services:project-service:test :services:notification-service:test --rerun-tasks || result=$?
mkdir -p /reports/results
cp -R services/project-service/build/test-results/test/. /reports/results/
mkdir -p /reports/notification-results
if [ -d services/notification-service/build/test-results/test ]; then
    cp -R services/notification-service/build/test-results/test/. /reports/notification-results/
fi
exit "$result"
