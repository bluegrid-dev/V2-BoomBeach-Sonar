#!/system/bin/sh
cd /data/local/tmp/sonar-independent || exit 1
export CLASSPATH=/data/local/tmp/sonar-independent/sonar.jar
nohup app_process /system/bin sonar.Main run > console.log 2>&1 < /dev/null &
echo $! > worker.pid
