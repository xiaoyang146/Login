#!/bin/sh
# 一键构建可运行 fat JAR：mixauth-login.jar
# 用法: ./build.sh
set -e
cd "$(dirname "$0")"

rm -rf out build mixauth-login.jar
mkdir -p out build/META-INF

echo ">> 编译 ..."
javac -encoding UTF-8 -d out $(find src -name '*.java')

echo ">> 打包 ..."
echo "Main-Class: com.example.mixauth.MixAuthLoginApp" > build/META-INF/MANIFEST.MF
jar cfm mixauth-login.jar build/META-INF/MANIFEST.MF -C out .

echo ">> 完成:"
ls -la mixauth-login.jar
echo
echo "运行: java -jar mixauth-login.jar qq"
echo "      java -jar mixauth-login.jar wechat --no-gui"