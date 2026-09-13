#ifndef AppImage
  #error AppImage must be supplied with /DAppImage=...
#endif
#ifndef OutputDir
  #error OutputDir must be supplied with /DOutputDir=...
#endif
#ifndef AppVersion
  #define AppVersion "1.2.0"
#endif

[Setup]
AppId=ru.vrmn.kasha
AppName=Kasha
AppVersion={#AppVersion}
AppVerName=Kasha {#AppVersion}
AppPublisher=Vyacheslav Verman
AppPublisherURL=https://github.com/vvverman/Kasha
DefaultDirName={localappdata}\Programs\Kasha
DefaultGroupName=Kasha
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
ArchitecturesAllowed=x64compatible
SetupArchitecture=x64
OutputDir={#OutputDir}
OutputBaseFilename=Kasha-{#AppVersion}-Windows-x64-Setup
Compression=lzma2/fast
SolidCompression=no
UseSetupLdr=x64
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
UninstallDisplayName=Kasha
VersionInfoVersion={#AppVersion}.0
VersionInfoCompany=Vyacheslav Verman
VersionInfoDescription=Kasha local-first voice notes and tasks
VersionInfoProductName=Kasha
VersionInfoProductVersion={#AppVersion}

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Kasha"; Filename: "{app}\Kasha.exe"; WorkingDir: "{app}"

[Run]
Filename: "{app}\Kasha.exe"; Description: "Запустить Kasha"; Flags: nowait postinstall skipifsilent
