# Load the local Android command-line toolchain for this PowerShell session.
$env:JAVA_HOME = 'D:\APP\Android\jdk-17'
$env:ANDROID_HOME = 'D:\APP\Android\sdk'
$env:GRADLE_USER_HOME = 'D:\APP\Android\gradle-cache'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;D:\APP\Android\gradle-8.11.1\bin;" + $env:PATH
