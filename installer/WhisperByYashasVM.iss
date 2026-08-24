#define AppName "Whisper -By YashasVM"
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#define AppPublisher "YashasVM"
#define AppExeName "WhisperByYashasVM.exe"

[Setup]
AppId={{6C2C2C44-2E70-4FA2-AB8E-7FA10A06B3CB}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={localappdata}\Programs\WhisperByYashasVM
DefaultGroupName=Whisper -By YashasVM
DisableProgramGroupPage=yes
OutputDir=dist
OutputBaseFilename=Whisper-By-YashasVM-Setup-v{#AppVersion}
Compression=lzma
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest

[Files]
Source: "..\src\WhisperByYashasVM\bin\Release\net8.0-windows\win-x64\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Whisper -By YashasVM"; Filename: "{app}\{#AppExeName}"
Name: "{autodesktop}\Whisper -By YashasVM"; Filename: "{app}\{#AppExeName}"

[Run]
Filename: "{app}\{#AppExeName}"; Description: "Launch Whisper -By YashasVM"; Flags: nowait postinstall skipifsilent
