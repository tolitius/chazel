#!/bin/bash

# just to get the ball rolling
# update for your actual OPS needs (i.e. start|stop, paths, java home, jvm args, etc..)

export HZ_VERSION=3.6
export CLASSPATH=~/.m2/repository/com/hazelcast/hazelcast/$HZ_VERSION/hazelcast-$HZ_VERSION.jar

export SERVER_HOME=.
export RESOURCES=.

export LOG_FILE="${SERVER_HOME}/logs/hz-server-$(date +%Y-%m-%d.%H-%M).log"

read -d '' JAVA_OPS << OPS_END
-cp $CLASSPATH -Xms4g -Xmx8g -server
-XX:+TieredCompilation -XX:+DoEscapeAnalysis -XX:+UseBiasedLocking
-XX:+UseTLAB -XX:+UseCompressedOops -XX:+OptimizeStringConcat -XX:+AggressiveOpts
-XX:+UseFastAccessorMethods -XX:+HeapDumpOnOutOfMemoryError -XX:-UsePerfData
-Duser.timezone=UTC
-Dlogback.configurationFile=$RESOURCES/logback.xml
-Dhazelcast.config=$RESOURCES/hz-conf.xml
OPS_END

mkdir -p $SERVER_HOME/logs

export START_SERVER="java ${JAVA_OPS} com.hazelcast.core.server.StartServer"

/usr/bin/nohup $START_SERVER > $LOG_FILE 2>&1 &

tail -f $LOG_FILE
