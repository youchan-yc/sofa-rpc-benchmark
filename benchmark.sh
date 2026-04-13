#!/usr/bin/env bash

usage() {
    echo "Usage: ${PROGRAM_NAME} command dirname"
    echo "command: [m|s|p|f|t|M|A|R]"
    echo "         -m [profiling|benchmark], specify benchmark mode"
    echo "         -s hostname, host name"
    echo "         -p port, port number"
    echo "         -f output file path"
    echo "         -t client thread nums"
    echo "         -S serialization type (e.g. hessian2, protobuf, json)"
    echo "         -e other system property"
    echo "         -a other args"
    echo "         -M enable mosn3 sidecar mode"
    echo "         -A mosn3 API address (default: http://127.0.0.1:13330)"
    echo "         -N mosn3 app name"
    echo "dirname: test module name"
}

build() {
    mvn --projects benchmark-base,client-base,server-base,${PROJECT_DIR} clean package
}

java_options() {
    JAVA_OPTIONS="-server -Xmx1g -Xms1g -XX:MaxDirectMemorySize=1g -XX:+UseG1GC -Djmh.ignoreLock=true"
    if [ "x${MODE}" = "xprofiling" ]; then
        JAVA_OPTIONS="${JAVA_OPTIONS} \
            -XX:+UnlockCommercialFeatures \
            -XX:+FlightRecorder \
            -XX:StartFlightRecording=duration=30s,filename=${PROJECT_DIR}.jfr \
            -XX:FlightRecorderOptions=stackdepth=256"
    fi
}

run() {
    if [ -d "${PROJECT_DIR}/target" ]; then
        JAR=`find ${PROJECT_DIR}/target/*.jar | head -n 1`
        echo
        echo "RUN ${PROJECT_DIR} IN ${MODE:-benchmark} MODE"

        MOSN_PROPS=""
        if [ "x${MOSN_ENABLED}" = "xtrue" ]; then
            MOSN_PROPS="-Dmosn.enabled=true -Dmosn.api.address=${MOSN_API_ADDRESS} -Dmosn.app.name=${MOSN_APP_NAME}"
            echo "MOSN3 sidecar mode enabled: api=${MOSN_API_ADDRESS}, app=${MOSN_APP_NAME}"
        fi

        CMD="java ${JAVA_OPTIONS} -Dserver.host=${SERVER} -Dserver.port=${PORT} -Dserver.serialization=${SERIALIZATION} -Dbenchmark.output=${OUTPUT} -Dthread.num=${THREADNUM} ${MOSN_PROPS} ${SYSTEM_PROPS} -jar ${JAR} ${OTHERARGS}"
        echo "command is: ${CMD}"
        echo
        ${CMD}
    fi
}

PROGRAM_NAME=$0
MODE="benchmark"
SERVER="localhost"
PORT="12200"
OUTPUT=""
OPTIND=1
OTHERARGS=""
THREADNUM=""
SERIALIZATION=""
SYSTEM_PROPS=""
MOSN_ENABLED="false"
MOSN_API_ADDRESS="http://127.0.0.1:13330"
MOSN_APP_NAME="sofa-rpc-benchmark"

while getopts "m:s:p:f:t:S:e:a:MA:N:" opt; do
    case "$opt" in
        m)
            MODE=${OPTARG}
            ;;
        s)
            SERVER=${OPTARG}
            ;;
        p)
            PORT=${OPTARG}
            ;;
        f)
            OUTPUT=${OPTARG}
            ;;
        t)
            THREADNUM=${OPTARG}
            ;;
        S)
            SERIALIZATION=${OPTARG}
            ;;
        e)
            SYSTEM_PROPS=${OPTARG}
            ;;
        a)
            OTHERARGS=${OPTARG}
            ;;
        M)
            MOSN_ENABLED="true"
            ;;
        A)
            MOSN_API_ADDRESS=${OPTARG}
            ;;
        N)
            MOSN_APP_NAME=${OPTARG}
            ;;
        ?)
            usage
            exit 0
            ;;
    esac
done

shift $((OPTIND-1))
PROJECT_DIR=$1

if [ ! -d "${PROJECT_DIR}" ]; then
    usage
    exit 0
fi

build
java_options
run






