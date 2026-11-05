@echo off
cd /d d:\IDEA\workplace\yu-picture-master\yu-picture-backend
"D:\AZnvwS84_jdk-11.0.12_windows-x64_bin.zip\jdk-11.0.12\bin\java.exe" -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar target\yu-picture-backend-0.0.1-SNAPSHOT.jar >> d:\IDEA\workplace\yu-picture-master\backend.log 2>&1
