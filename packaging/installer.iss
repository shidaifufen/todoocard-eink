; 土豆片卡片工坊 —— Inno Setup 安装脚本
; 编译：ISCC.exe /DMyVersion=1.0.0 packaging\installer.iss
; 产物：release\土豆片卡片工坊-Setup-<版本>.exe

#define AppName "土豆片卡片工坊"
#define AppExe "土豆片卡片工坊.exe"
#ifndef MyVersion
  #define MyVersion "1.0.0"
#endif

[Setup]
; 同一个 AppId 的安装包会被认成"同一个程序"（升级时不会装出两份）
AppId={{9C3A7F52-6E4B-4D18-9A21-7B5C0E8D4F31}
AppName={#AppName}
AppVersion={#MyVersion}
AppVerName={#AppName} {#MyVersion}
AppPublisher=土豆片
AppPublisherURL=https://github.com/shidaifufen/todoocard-eink
AppSupportURL=https://github.com/shidaifufen/todoocard-eink/issues
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; 装到当前用户目录，不需要管理员权限，也不会弹 UAC
PrivilegesRequired=lowest
OutputDir=..\release
OutputBaseFilename=土豆片卡片工坊-Setup-{#MyVersion}
SetupIconFile=app.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; 程序开着的时候安装/卸载：交给 Restart Manager 自动关掉它（不要用 AppMutex，
; 那会弹"请先关闭程序"让人手动点，安装体验差）
CloseApplications=yes
RestartApplications=no
; 卸载时不要动用户数据目录，配置和卡片历史留着
MinVersion=10.0

[Languages]
; ChineseSimplified.isl 取自 jrsoftware/issrc（官方翻译，6.5.0+），跟着仓库一起放，离线可编译
Name: "zh"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加图标："
Name: "autostart"; Description: "开机自动启动（定时推送卡片需要）"; GroupDescription: "附加图标："; Flags: unchecked

[Files]
; 目录版整套（exe + _internal\），启动快、杀毒误报少
Source: "..\dist\土豆片卡片工坊\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "使用说明.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; Comment: "六色墨水屏卡片工坊"
Name: "{autoprograms}\使用说明"; Filename: "{app}\使用说明.txt"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon
Name: "{userstartup}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: autostart

[Run]
Filename: "{app}\{#AppExe}"; Description: "立即运行 {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; 卸载前先让程序自己优雅退出，避免文件被占用删不干净
Filename: "{app}\{#AppExe}"; Parameters: "--shutdown"; Flags: runhidden waituntilterminated; RunOnceId: "GracefulShutdown"

[UninstallDelete]
Type: files; Name: "{app}\*.log"
