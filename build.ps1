# Release APK (signed with keystore.properties when present):  .\build.ps1
# Test APK that installs next to the released app:             .\build.ps1 -Test
param([switch]$Test)
$args = @("assembleRelease")
if ($Test) { $args += "-Ptestbuild" }
.\gradlew.bat @args
