#!/bin/sh
# MixAuth 登录测试应用：一键编译 + 运行
# 用法: ./run.sh qq | ./run.sh wechat [额外参数...]
set -e
cd "$(dirname "$0")"

mkdir -p out
echo ">> 编译 ..."
javac -encoding UTF-8 -d out $(find src -name '*.java')
echo ">> 运行 ..."
exec java -cp out com.example.mixauth.MixAuthLoginApp "$@"