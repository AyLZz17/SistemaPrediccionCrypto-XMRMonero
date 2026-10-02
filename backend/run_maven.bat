@echo off
"C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe" ^
  -cp "C:\Maven\apache-maven-3.9.16\boot\plexus-classworlds-2.11.0.jar" ^
  "-Dclassworlds.conf=C:\Maven\apache-maven-3.9.16\bin\m2.conf" ^
  "-Dmaven.home=C:\Maven\apache-maven-3.9.16" ^
  "-Dlibrary.jansi.path=C:\Maven\apache-maven-3.9.16\lib\jansi-native" ^
  "-Dmaven.multiModuleProjectDirectory=%CD%" ^
  org.codehaus.plexus.classworlds.launcher.Launcher compile